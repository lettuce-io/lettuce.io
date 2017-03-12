/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpStatusClass;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerRequest;
import reactor.ipc.netty.http.server.HttpServerResponse;
import reactor.ipc.netty.resources.PoolResources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.activation.MimetypesFileTypeMap;
import javax.xml.bind.JAXB;

import org.reactivestreams.Publisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/**
 * Main Application for the Lettuce home site.
 */
public final class Application {

	private final Map<String, Module> modules = new HashMap<>();
	private final String redisHost = System.getProperty("redis.host", "localhost");
	private final HttpServer server = HttpServer.create("0.0.0.0", Integer.getInteger("server.port", 8080));
	private final HttpClient client = HttpClient.create(opts -> opts.poolResources(PoolResources.elastic("proxy")));
	private final Path contentPath = resolveContentPath();
	private final RedisClient redisClient;
	private final StatefulRedisConnection<String, byte[]> redisConnection;
	private final Mono<? extends NettyContext> context;
	private final MimetypesFileTypeMap fileTypeMap = new MimetypesFileTypeMap();
	private final byte[] piwikCode;
	private final String versionsPageContent;
	private final String versionContent;

	Application() throws IOException {

		context = server.newRouter(r -> r.file("/favicon.ico", contentPath.resolve("favicon.ico"))
				.file("/KEYS", contentPath.resolve("KEYS")).get("/docs", rewrite("/docs", "/docs/"))
				.get("/{module}/{version}/reference", rewrite("/reference", "/reference/index.html"))
				.get("/{module}/{version}/api", rewrite("/api", "/api/index.html"))
				.get("/{module}/{version}/download", rewrite("/download", "/download/"))
				.get("/{module}/release/api/**", this::repoProxy) //
				.get("/{module}/release/reference/**", this::repoProxy)
				.get("/{module}/release/download/**", this::downloadRedirect) //
				.get("/{module}/milestone/api/**", this::repoProxy) //
				.get("/{module}/milestone/reference/**", this::repoProxy)
				.get("/{module}/milestone/download/**", this::downloadRedirect)
				.get("/{module}/snapshot/api/**", this::repoProxy) //
				.get("/{module}/snapshot/reference/**", this::repoProxy)
				.get("/{module}/snapshot/download/", this::downloadRedirect).get("/{module}/{version}/api/**", this::repoProxy) //
				.get("/{module}/{version}/reference/**", this::repoProxy)
				.get("/{module}/{version}/download/", this::downloadRedirect)
				.get("/docs/",
						(req, res) -> res.header(HttpHeaderNames.CONTENT_TYPE, "text/html")
								.sendFile(contentPath.resolve("docs/index.html")))
				.get("/assets/**", this::assets) //
				.get("/{module}/", this::versionsPage) //
				.get("/", (req, res) -> res.header(HttpHeaderNames.CONTENT_TYPE, "text/html")
						.sendFile(contentPath.resolve("index.html"))));

		Yaml yaml = new Yaml(new Constructor(Module.class));
		yaml.loadAll(new ClassPathResource("modules.yml").getInputStream()).forEach(o -> {
			Module module = (Module) o;
			modules.put(module.getName(), module);
		});

		fileTypeMap.addMimeTypes("text/css css text CSS");
		fileTypeMap.addMimeTypes("text/javascript js text JS");
		fileTypeMap.addMimeTypes("image/png png image PNG");
		fileTypeMap.addMimeTypes("application/x-font-woff woff font WOFF");
		fileTypeMap.addMimeTypes("application/x-font-woff woff2 font WOFF2");

		redisClient = RedisClient.create(RedisURI.create(redisHost, 6379));

		ClientOptions clientOptions = ClientOptions.builder().requestQueueSize(100)
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();

		redisClient.setOptions(clientOptions);
		redisConnection = redisClient.connect(StringByteCodec.INSTANCE);
		try (InputStream is = getClass().getResourceAsStream("/piwik.html")) {
			piwikCode = StreamUtils.copyToByteArray(is);
		}

		try (InputStream is = getClass().getResourceAsStream("/version.html")) {
			versionContent = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
		}

		try (InputStream is = getClass().getResourceAsStream("/versions.html")) {
			versionsPageContent = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
		}
	}

	public static void main(String... args) throws Exception {
		Application app = new Application();
		app.startAndAwait();
	}

	public void startAndAwait() {
		context.doOnNext(this::startLog).block().onClose().block();
		redisConnection.close();
		redisClient.shutdown();
	}

	private BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> rewrite(String originalPath,
			String newPath) {
		return (req, resp) -> resp.sendRedirect(req.uri().replace(originalPath, newPath));
	}

	private Publisher<Void> repoProxy(HttpServerRequest req, HttpServerResponse resp) {

		String name = req.param("module");
		String version = req.param("version");
		String path = req.path();

		Module module = modules.get(name);

		if (module == null) {
			return send404(resp);
		}

		boolean isJavadoc = path.contains("/api/") || path.endsWith("/api");
		RequestedVersion requestedVersion = new RequestedVersion(version, path, module);
		Versions.Classifier versionType = requestedVersion.getVersionType();
		boolean pinnedVersion = requestedVersion.isPinnedVersion();

		int requestFileOffset = isJavadoc ? 7 : 13;
		if (version == null) {
			requestFileOffset += versionType.name().length() + name.length();
		} else {
			requestFileOffset += version.length() + name.length();
		}

		final int offset = requestFileOffset;
		Mono<MavenMetadata> mavenMetadata = mavenMetadata(module, versionType);

		return mavenMetadata //
				.then(meta -> Mono.justOrEmpty(pinnedVersion ? Versions.create(meta, module).getVersion(version)
						: Versions.create(meta, module).getLatest(versionType))) //
				.otherwiseIfEmpty(Mono.defer(() -> send404(resp).cast(Versions.Version.class))) //
				.then(artifactVersion -> {

					String file = req.uri().substring(offset);
					if (file.isEmpty()) {
						file = "index.html";
					}
					String contentType = fileTypeMap.getContentType(file);
					return jarEntry(module, artifactVersion, isJavadoc ? "javadoc" : "docs", file).defaultIfEmpty(new byte[0])
							.then(bytes -> {
								if (bytes.length == 0) {
									return send404(resp);
								}

								return resp.status(200).header(HttpHeaderNames.CONTENT_TYPE, contentType)
										.send(Mono.just(Unpooled.wrappedBuffer(bytes))).then();
							});
				});
	}

	private Mono<Void> send404(HttpServerResponse resp) {
		return resp.header(HttpHeaderNames.CONTENT_TYPE, "text/html").sendFile(contentPath.resolve("404.html")).then();
	}

	private Publisher<Void> downloadRedirect(HttpServerRequest req, HttpServerResponse resp) {

		String name = req.param("module");
		String version = req.param("version");
		String path = req.path();

		Module module = modules.get(name);

		if (module == null) {
			return send404(resp);
		}

		RequestedVersion requestedVersion = new RequestedVersion(version, path, module);
		Versions.Classifier versionType = requestedVersion.getVersionType();
		boolean pinnedVersion = requestedVersion.isPinnedVersion();

		Mono<MavenMetadata> mavenMetadata = mavenMetadata(module, versionType);

		return mavenMetadata //
				.then(meta -> Mono.justOrEmpty(pinnedVersion ? Versions.create(meta, module).getVersion(version)
						: Versions.create(meta, module).getLatest(versionType))) //
				.otherwiseIfEmpty(Mono.defer(() -> send404(resp).cast(Versions.Version.class))) //
				.then(artifactVersion -> {

					String downloadUrl;
					if (artifactVersion.getClassifier() == Versions.Classifier.Release
							|| artifactVersion.getClassifier() == Versions.Classifier.Milestone) {
						downloadUrl = String.format("https://github.com/mp911de/lettuce/releases/download/%s/lettuce-%s-bin.zip",
								artifactVersion.getVersion(), artifactVersion.getVersion());

					} else {

						downloadUrl = String.format("https://oss.sonatype.org/content/repositories/snapshots/%s/%s/%s",
								module.getGroupId().replace('.', '/'), module.getArtifactId(), artifactVersion.getVersion());
					}

					return resp.sendRedirect(downloadUrl).then();
				});
	}

	private Publisher<Void> versionsPage(HttpServerRequest req, HttpServerResponse resp) {

		String name = req.param("module");

		Module module = modules.get(name);

		if (module == null) {
			return send404(resp);
		}

		Mono<MavenMetadata> releases = mavenMetadata(module, Versions.Classifier.Release);

		return releases.map(meta -> {
			return Versions.create(meta, module);
		}).otherwiseIfEmpty(Mono.defer(() -> send404(resp).cast(Versions.class))).map(versions -> {

			List<String> versionElements = versions.stream().map(version -> {

				return versionContent.replaceAll("%branch%", module.getBranch()) //
						.replaceAll("%version%", version.getVersion()) //
						.replaceAll("%versionclass%",
								version.getClassifier() == Versions.Classifier.Release ? "stable" : "milestone")
						.replaceAll("%versionclassifier%",
								version.getClassifier() == Versions.Classifier.Release ? "Stable" : version.getClassifier().name()) //
						.replaceAll("%artifactid%", module.getArtifactId()) //
						.replaceAll("%moduleid%", module.getName());
			}).collect(Collectors.toList());

			return StringUtils.arrayToDelimitedString(versionElements.toArray(), "");
		}).flatMap(s -> {

			String pageContent = versionsPageContent.replaceFirst("%versions%", s).replaceAll("%branch%", module.getBranch());

			return resp.header(HttpHeaderNames.CONTENT_TYPE, "text/html").sendByteArray(Mono.just(pageContent.getBytes()));
		}).then();
	}

	private Publisher<Void> assets(HttpServerRequest req, HttpServerResponse resp) {

		String prefix = URI.create(req.uri()).getPath();

		if (prefix.contains("..") || prefix.contains("//")) {
			return send404(resp);
		}

		if (prefix.charAt(0) == '/') {
			prefix = prefix.substring(1);
		}

		Path p = contentPath.resolve(prefix);
		if (Files.isReadable(p)) {

			String contentType = fileTypeMap.getContentType(p.toString());

			return resp.header(HttpHeaderNames.CONTENT_TYPE, contentType).sendFile(p);
		}

		return resp.sendNotFound();

	}

	private static boolean contains(String version, String[] needles) {
		return Stream.of(needles).anyMatch(version::contains);
	}

	private void startLog(NettyContext c) {
		System.out.printf(
				"Server started in %d ms on: %s\n", Duration
						.ofNanos(ManagementFactory.getThreadMXBean().getThreadCpuTime(Thread.currentThread().getId())).toMillis(),
				c.address());
	}

	private Path resolveContentPath() throws IOException {
		ClassPathResource cp = new ClassPathResource("static");
		if (cp.isFile()) {
			return Paths.get(cp.getURI());
		}
		FileSystem fs = FileSystems.newFileSystem(cp.getURI(), Collections.emptyMap());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				fs.close();
			} catch (IOException io) {
				// ignore
			}
		}));
		return fs.getPath("static");
	}

	Mono<MavenMetadata> mavenMetadata(Module module, Versions.Classifier classifier) {

		String cacheKey = String.format("%s-%s", module.getName(), classifier);
		String repo = getRepo(classifier);
		String url = String.format("%s/%s/%s/maven-metadata.xml", repo, module.getGroupId().replace('.', '/'),
				module.getArtifactId());

		return withCaching(cacheKey, new SetArgs(),
				client.get(url, r -> r.failOnClientError(false)).then(httpClientResponse -> {

					if (httpClientResponse.status().codeClass() == HttpStatusClass.SUCCESS) {
						return httpClientResponse.receive().asByteArray().collectList().map(this::getBytes);
					}

					return Mono.empty();
				})).map(bytes -> JAXB.unmarshal(new ByteArrayInputStream(bytes), MavenMetadata.class));
	}

	private byte[] getBytes(List<byte[]> bbs) {
		int size = (int) bbs.stream().map(bytes -> bytes.length).collect(Collectors.summarizingInt(value -> value))
				.getSum();

		byte[] bytes = new byte[size];
		int offset = 0;
		for (byte[] bb : bbs) {

			System.arraycopy(bb, 0, bytes, offset, bb.length);
			offset += bb.length;
		}

		return bytes;
	}

	Mono<MavenMetadata.Snapshot> snapshot(Module module, Versions.Version version) {

		String repo = getRepo(version.getClassifier());
		String url = String.format("%s/%s/%s/%s/maven-metadata.xml", repo, module.getGroupId().replace('.', '/'),
				module.getArtifactId(), version.getVersion());

		return client.get(url)
				.flatMap(httpClientResponse -> httpClientResponse.receive().asInputStream()
						.collect(QueueBackedInputStream.toInputStream()))
				.map(is -> JAXB.unmarshal(is, MavenMetadata.class).getVersioning().getSnapshot()).next();

	}

	Mono<byte[]> jarEntry(Module module, Versions.Version version, String type, String path) {

		String cacheKey = String.format("%s-%s-%s", module.getName(), version.getVersion(), type);
		String repo = getRepo(version.getClassifier());

		SetArgs setArgs = SetArgs.Builder.nx();
		if (version.getClassifier() == Versions.Classifier.Snapshot) {
			setArgs.ex(TimeUnit.SECONDS.convert(4, TimeUnit.HOURS));
		}

		Mono<byte[]> contentLoader = withCaching(cacheKey, setArgs, Mono.defer(() -> {

			return getFilename(module, version, type).then(s -> {

				String url = String.format("%s/%s/%s/%s/%s", repo, module.getGroupId().replace('.', '/'),
						module.getArtifactId(), version.getVersion(), s);
				return client.get(url);
			}).then(httpClientResponse -> httpClientResponse.receive().asByteArray().collectList().map(this::getBytes));
		})).then(content -> {

			try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {

				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {

					if (!entry.getName().equals(path)) {
						continue;
					}

					byte[] bytes = StreamUtils.copyToByteArray(zis);
					if (path.endsWith(".htm") || path.endsWith(".html")) {
						bytes = addTrackingCode(bytes);
					}

					return Mono.just(bytes);
				}
			} catch (IOException e) {
				return Mono.error(e);
			}
			return Mono.empty();
		});

		return withCaching(String.format("%s:%s", cacheKey, path), setArgs, contentLoader);
	}

	private byte[] addTrackingCode(byte[] content) {
		String html = new String(content, StandardCharsets.UTF_8);

		int index = html.lastIndexOf("</html>");
		if (index != -1) {

			String result = html.substring(0, index) + new String(piwikCode) + html.substring(index);
			return result.getBytes(StandardCharsets.UTF_8);
		}

		return content;
	}

	private Mono<String> getFilename(Module module, Versions.Version version, String type) {
		Mono<String> filename;
		String extension = type.equals("docs") ? "zip" : "jar";
		if (version.getClassifier() == Versions.Classifier.Snapshot) {
			filename = snapshot(module, version).map(snapshot -> {
				return String.format("%s-%s-%s-%s-%s.%s", module.getArtifactId(), version.getVersion().replace("-SNAPSHOT", ""),
						snapshot.getTimestamp(), snapshot.getBuildNumber(), type, extension);
			});
		} else {
			filename = Mono.just(String.format("%s-%s-%s.%s", module.getArtifactId(), version.getVersion(), type, extension));
		}
		return filename;
	}

	private Mono<byte[]> withCaching(String cacheKey, SetArgs setArgs, Mono<byte[]> cacheLoader) {
		return redisConnection.reactive().get(cacheKey).otherwise(throwable -> cacheLoader)
				.otherwiseIfEmpty(cacheLoader.then(value -> {
					return redisConnection.reactive().set(cacheKey, value, setArgs).map(ok -> value)
							.otherwise(throwable -> Mono.justOrEmpty(value));
				}));
	}

	private String getRepo(Versions.Classifier classifier) {
		return classifier == Versions.Classifier.Snapshot ? "https://oss.sonatype.org/content/repositories/snapshots"
				: "https://oss.sonatype.org/content/repositories/releases";
	}

	static class RequestedVersion {

		private Versions.Classifier versionType;
		private boolean pinnedVersion;

		public RequestedVersion(String version, String path, Module module) {

			if (version == null) {
				versionType = path.contains("/snapshot") ? Versions.Classifier.Snapshot
						: (path.contains("/milestone") ? Versions.Classifier.Milestone : Versions.Classifier.Release);
				pinnedVersion = false;
			} else {
				pinnedVersion = true;
				getVersionType(version, module);
			}
		}

		public static Versions.Classifier getVersionType(String version, Module module) {

			String[] milestones = module.getMilestone().split(",");
			if (version.contains("-SNAPSHOT")) {
				return Versions.Classifier.Snapshot;
			}

			if (contains(version, milestones)) {
				return Versions.Classifier.Milestone;
			}
			return Versions.Classifier.Release;
		}

		public Versions.Classifier getVersionType() {
			return versionType;
		}

		public boolean isPinnedVersion() {
			return pinnedVersion;
		}
	}
}
