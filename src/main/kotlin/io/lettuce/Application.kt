/*
 * Copyright 2018 the original author or authors.
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
package io.lettuce

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpStatusClass
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.util.StreamUtils
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.ipc.netty.NettyContext
import reactor.ipc.netty.http.client.HttpClient
import reactor.ipc.netty.http.client.HttpClientException
import reactor.ipc.netty.http.client.HttpClientResponse
import reactor.ipc.netty.http.server.HttpServer
import reactor.ipc.netty.http.server.HttpServerRequest
import reactor.ipc.netty.http.server.HttpServerResponse
import reactor.ipc.netty.resources.PoolResources
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.*
import java.util.function.BiFunction
import java.util.zip.ZipInputStream
import javax.xml.bind.JAXB

/**
 * Main Application for the Lettuce home site.
 */
class Application {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val redisHost = System.getProperty("redis.host", "localhost")
    private val server = HttpServer.create("0.0.0.0", Integer.getInteger("server.port", 8080)!!)
    private val client = HttpClient.create { opts -> opts.poolResources(PoolResources.elastic("proxy")) }
    private val contentPath = ServerUtils.resolveContentPath()
    private val templateEngine = TemplateEngine()
    private val mapper = ObjectMapper()
    private val redisClient: RedisClient
    private val redisConnection: StatefulRedisConnection<String, ByteArray>
    private val context: Mono<out NettyContext>

    private val modules = mutableMapOf<String, Module>()
    private val artifacts: List<Artifact>
    private val fileTypeMap = ServerUtils.mimeTypes
    private val piwikCode: ByteArray
    private val versionsPageContent: String
    private val gettingStartedPageContent: String
    private val docsPageContent: String

    init {

        context = server.newRouter { r ->
            r.file("/favicon.ico", contentPath.resolve("favicon.ico"))
                    .file("/KEYS", contentPath.resolve("KEYS")).get("/docs", rewrite("/docs", "/docs/"))
                    .get("/{module}/{version}/reference", rewrite("/reference", "/reference/index.html"))
                    .get("/{module}/{version}/api", rewrite("/api", "/api/index.html"))
                    .get("/{module}/{version}/download", rewrite("/download", "/download/"))
                    .get("/{module}/release/api/**") { req, resp -> this.repoProxy(req, resp) } //
                    .get("/{module}/release/reference/**") { req, resp -> this.repoProxy(req, resp) }
                    .get("/{module}/release/download/**") { req, resp -> this.downloadRedirect(req, resp) } //
                    .get("/{module}/milestone/api/**") { req, resp -> this.repoProxy(req, resp) } //
                    .get("/{module}/milestone/reference/**") { req, resp -> this.repoProxy(req, resp) }
                    .get("/{module}/milestone/download/**") { req, resp -> this.downloadRedirect(req, resp) }
                    .get("/{module}/snapshot/api/**") { req, resp -> this.repoProxy(req, resp) } //
                    .get("/{module}/snapshot/reference/**") { req, resp -> this.repoProxy(req, resp) }
                    .get("/{module}/snapshot/download/") { req, resp -> this.downloadRedirect(req, resp) }
                    .get("/{module}/{version}/api/**") { req, resp -> this.repoProxy(req, resp) } //
                    .get("/{module}/{version}/reference/**") { req, resp -> this.repoProxy(req, resp) }
                    .get("/{module}/{version}/download/") { req, resp -> this.downloadRedirect(req, resp) } //
                    .get("/docs/getting-started.html") { _, resp ->
                        gettingStarted(resp)
                    } //
                    .get("/docs/") { _, resp -> this.docs(resp) } //
                    .get("/assets/**") { req, resp -> this.assets(req, resp) } //
                    .get("/{module}/") { req, resp -> this.versionsPage(req, resp) } //
                    .get("/") { _, res ->
                        res.header(HttpHeaderNames.CONTENT_TYPE, TEXT_HTML)
                                .header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_INDEX))
                                .sendFile(contentPath.resolve("index.html"))
                    }
        }

        mapper.registerKotlinModule()

        val modulesType = mapper.typeFactory.constructCollectionLikeType(List::class.java, Module::class.java)
        val modules = mapper.reader().forType(modulesType).readValue<List<Module>>(ClassPathResource("modules.json").inputStream)

        modules.forEach { module -> this.modules[module.name] = module }

        val artifactsType = mapper.typeFactory.constructCollectionLikeType(List::class.java, Artifact::class.java)
        artifacts = mapper.reader().forType(artifactsType)
                .readValue(ClassPathResource("artifacts.json").inputStream)

        redisClient = RedisClient.create(RedisURI.create(redisHost, 6379))

        val clientOptions = ClientOptions.builder().requestQueueSize(100)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build()

        redisClient.options = clientOptions
        redisConnection = redisClient.connect(StringByteCodec.INSTANCE)

        piwikCode = ServerUtils.getResourceAsString("/piwik.html").toByteArray()
        versionsPageContent = ServerUtils.getResourceAsString("/versions.html")
        docsPageContent = ServerUtils.getResourceAsString("/docs.html")
        gettingStartedPageContent = ServerUtils.getResourceAsString("/getting-started.html")
    }

    private fun startAndAwait() {
        context.doOnNext { this.startLog(it) }.block()!!.onClose().block()
        redisConnection.close()
        redisClient.shutdown()
    }

    private fun rewrite(originalPath: String,
                        newPath: String): BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

        return BiFunction { req: HttpServerRequest, resp: HttpServerResponse -> resp.sendRedirect(req.uri().replace(originalPath, newPath)) }
    }

    private fun gettingStarted(resp: HttpServerResponse): Publisher<Void> {

        return getVersionsMono(modules["core"]!!, Classifier.Release, false).map { t -> t.getLatest(Classifier.Release) }.flatMap {
            val context = Context(Locale.US)
            context.setVariable("version", it!!.version)
            val html = templateEngine.process(gettingStartedPageContent, context)

            resp.header(HttpHeaderNames.CONTENT_TYPE, TEXT_HTML)
                    .header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_DOCS))
                    .sendByteArray(Mono.just(html.toByteArray())).then()
        }
    }

    private fun docs(resp: HttpServerResponse): Publisher<Void> {

        val artifacts = Flux.fromIterable(this.artifacts).flatMapSequential { it ->

            val module = modules[it.module]!!
            val versions = getVersionsMono(module, Classifier.Release, false)
            versions.map<Version> { v -> v.getLatest(Classifier.Release) }.map { v ->
                DocumentedArtifact(it, module, v)
            }
        }.collectList()
        
        val latestStable = getVersionsMono(modules["core"]!!, Classifier.Release, false).map { it.getLatest(Classifier.Release) }

        return artifacts.zipWith(latestStable).flatMap { it ->

            val context = Context(Locale.US)
            context.setVariable("items", it.t1)
            context.setVariable("latestStable", it.t2.version)
            val html = templateEngine.process(docsPageContent, context)

            resp.header(HttpHeaderNames.CONTENT_TYPE, TEXT_HTML)
                    .header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_DOCS))
                    .sendByteArray(Mono.just(html.toByteArray())).then()
        }
    }

    private fun repoProxy(req: HttpServerRequest, resp: HttpServerResponse): Publisher<Void> {

        val moduleRequest = ModuleRequest.create(req, modules)
        val module = moduleRequest.module ?: return send404(resp)

        val isJavadoc = moduleRequest.path.contains("/api/") || moduleRequest.path.endsWith("/api")
        val requestedVersion = RequestedVersion(moduleRequest)
        val versionType = requestedVersion.versionType
        val pinnedVersion = requestedVersion.isPinnedVersion

        val versions = getVersionsMono(module, versionType, pinnedVersion)

        return versions //
                .flatMap<Version> { v ->
                    Mono
                            .justOrEmpty(if (pinnedVersion) v.getVersion(moduleRequest.version!!) else v.getLatest(versionType!!))
                } //
                .switchIfEmpty(Mono.defer<Version> { send404(resp).cast<Version>(Version::class.java) }) //
                .flatMap { artifactVersion ->

                    var file = getFilename(req, moduleRequest, module, isJavadoc, versionType)
                    if (file.isEmpty()) {
                        file = "index.html"
                    }
                    val type = if (isJavadoc) "javadoc" else "docs"
                    val contentType = fileTypeMap.getContentType(file)
                    jarEntry(module, artifactVersion, type, file)
                            .defaultIfEmpty(ByteArray(0)).flatMap { bytes ->
                                if (bytes.isEmpty()) {
                                    send404(resp)
                                } else {

                                    resp.status(200).header(HttpHeaderNames.CONTENT_TYPE, contentType)

                                    if (pinnedVersion) {
                                        resp.header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_ASSETS))
                                    } else {
                                        resp.header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_DOCS))
                                    }

                                    resp.send(Mono.just<ByteBuf>(Unpooled.wrappedBuffer(bytes))).then()
                                }
                            }.onErrorResume({ throwable ->

                                if (throwable is HttpClientException) {
                                    (throwable.status() != null && HttpResponseStatus.NOT_FOUND.equals(throwable.status()));
                                } else {
                                    false
                                }

                            }, { _: Throwable -> send404(resp) })
                }
    }

    private fun send404(resp: HttpServerResponse): Mono<Void> {
        return resp.header(HttpHeaderNames.CONTENT_TYPE, TEXT_HTML).status(404)
                .sendFile(contentPath.resolve("404.html")).then()
    }

    private fun downloadRedirect(req: HttpServerRequest, resp: HttpServerResponse): Publisher<Void> {

        val moduleRequest = ModuleRequest.create(req, modules)

        val module = moduleRequest.module ?: return send404(resp)

        val requestedVersion = RequestedVersion(moduleRequest)
        val versionType = requestedVersion.versionType
        val pinnedVersion = requestedVersion.isPinnedVersion

        val versions = getVersionsMono(module, versionType, pinnedVersion)

        return versions //
                .flatMap<Version> { v ->
                    Mono
                            .justOrEmpty(if (pinnedVersion) v.getVersion(moduleRequest.version!!) else v.getLatest(versionType!!))
                } //
                .switchIfEmpty(Mono.defer<Version> { send404(resp).cast<Version>(Version::class.java) }) //
                .flatMap { artifactVersion ->

                    val downloadUrl = getDownloadUrl(module, artifactVersion)

                    resp.sendRedirect(downloadUrl).then()
                }
    }

    private fun getVersionsMono(module: Module, versionType: Classifier?, pinnedVersion: Boolean): Mono<Versions> {
        if (pinnedVersion) {
            val releases = getMavenMetadata(module, Classifier.Release).map { meta -> Versions.create(meta, module) }
            val snapshots = getMavenMetadata(module, Classifier.Snapshot).map { meta -> Versions.create(meta, module) }

            return Mono.zip(releases, snapshots).map { tuple -> tuple.t1.mergeWith(tuple.t2) }
        }

        return getMavenMetadata(module, versionType!!).map { meta -> Versions.create(meta, module) }
    }

    private fun versionsPage(req: HttpServerRequest, resp: HttpServerResponse): Publisher<Void> {

        val name = req.param("module")
        val module = modules[name] ?: return send404(resp)
        val releases = getMavenMetadata(module, Classifier.Release)

        return releases.map { meta -> Versions.create(meta, module) }.switchIfEmpty(Mono.defer { send404(resp).cast(Versions::class.java) }).flatMap { versions ->

            val context = Context(Locale.US)
            context.setVariable("module", module)
            context.setVariable("versions", versions.versions)

            val pageContent = templateEngine.process(versionsPageContent, context)

            resp.header(HttpHeaderNames.CONTENT_TYPE, TEXT_HTML)
                    .header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_DOCS))
                    .sendByteArray(Mono.just(pageContent.toByteArray())).then()
        }
    }

    private fun assets(req: HttpServerRequest, resp: HttpServerResponse): Publisher<Void> {

        var prefix = URI.create(req.uri()).path

        if (prefix.contains("..") || prefix.contains("//")) {
            return send404(resp)
        }

        if (prefix[0] == '/') {
            prefix = prefix.substring(1)
        }

        val p = contentPath.resolve(prefix)
        if (Files.isReadable(p)) {

            val contentType = fileTypeMap.getContentType(p.toString())

            return resp.header(HttpHeaderNames.CONTENT_TYPE, contentType)
                    .header(HttpHeaderNames.CACHE_CONTROL, cacheControl(CLIENT_CACHE_ASSETS)).sendFile(p)
        }

        return resp.sendNotFound()
    }

    private fun startLog(c: NettyContext) {
        log.info(String.format("Server started in %d ms on: %s\n", Duration.ofNanos(
                ManagementFactory.getThreadMXBean().getThreadCpuTime(Thread.currentThread().id)).toMillis(),
                c.address()))
    }

    private fun getMavenMetadata(module: Module, classifier: Classifier): Mono<MavenMetadata> {

        val cacheKey = "maven-metadata:${module.name}-${classifier}"
        val repo = getRepo(classifier)
        val url = String.format("%s/%s/%s/maven-metadata.xml", repo, module.groupId.replace('.', '/'),
                module.artifactId)

        val setArgs = SetArgs().ex(MAVEN_METADATA_CACHING_DURATION.seconds)

        return withCaching(cacheKey, setArgs, client.get(url) { r ->

            r.failOnClientError(false)
            Mono.empty()
        }.flatMap { httpClientResponse ->

            if (httpClientResponse.status() != null && httpClientResponse.status().codeClass() === HttpStatusClass.SUCCESS) {
                client.get(url) { r ->

                    r.failOnClientError(false);
                    Mono.empty();
                }.flatMap {
                    httpClientResponse.receive().asByteArray().collectList().map<ByteArray> {
                        getBytes(it)
                    }
                }
            } else {
                Mono.empty<ByteArray>()
            }
        }).map {
            JAXB.unmarshal(ByteArrayInputStream(it), MavenMetadata::class.java)
        }
    }

    private fun snapshot(module: Module, version: Version): Mono<Snapshot> {

        val repo = getRepo(version.classifier)
        val url = String.format("%s/%s/%s/%s/maven-metadata.xml", repo, module.groupId.replace('.', '/'),
                module.artifactId, version.version)

        return client.get(url).flatMap<InputStream> { httpClientResponse ->
            httpClientResponse.receive().asInputStream()
                    .collect<InputStream, QueueBackedInputStream>(QueueBackedInputStream.toInputStream())
        }
                .map { JAXB.unmarshal<MavenMetadata>(it, MavenMetadata::class.java).versioning!!.snapshot }
    }

    private fun jarEntry(module: Module, version: Version, type: String, path: String): Mono<ByteArray> {

        val cacheKey = "${module.name}-${version.version}-${type}"
        val jarCacheKey = "jar:${cacheKey}"
        val repo = getRepo(version.classifier)

        val setArgs = SetArgs.Builder.nx()

        if (version.classifier == Classifier.Snapshot) {
            setArgs.ex(SNAPSHOT_CACHING_DURATION.seconds)
        }

        val contentLoader = withCaching(jarCacheKey, setArgs, Mono.defer {

            getFilename(module, version, type).flatMap<HttpClientResponse> { s ->

                val url = String.format("%s/%s/%s/%s/%s", repo, module.groupId.replace('.', '/'),
                        module.artifactId, version.version, s)
                log.info("Downloading from $url")
                client.get(url)
            }.flatMap<ByteArray> { httpClientResponse ->
                httpClientResponse.receive().asByteArray().collectList()
                        .map { getBytes(it) }
            }
        }).flatMap { content -> getBytes(content, path) }

        return withCaching("file:$cacheKey:$path", setArgs, contentLoader)
    }

    private fun getBytes(content: ByteArray, path: String): Mono<ByteArray> {

        try {
            val zis = ZipInputStream(ByteArrayInputStream(content))

            while (true) {
                val entry = zis.nextEntry ?: break

                if (entry.name != path) {
                    continue
                }

                var bytes = StreamUtils.copyToByteArray(zis)
                if (path.endsWith(".htm") || path.endsWith(".html")) {
                    bytes = addTrackingCode(bytes)
                }

                return Mono.just(bytes);
            }

        } catch (e: IOException) {
            return Mono.error<ByteArray>(e)
        }

        return Mono.empty()
    }

    private fun addTrackingCode(content: ByteArray): ByteArray {
        val html = String(content, StandardCharsets.UTF_8)

        val index = html.lastIndexOf("</html>")
        if (index != -1) {

            val result = html.substring(0, index) + String(piwikCode) + html.substring(index)
            return result.toByteArray(StandardCharsets.UTF_8)
        }

        return content
    }

    private fun getFilename(module: Module, version: Version, type: String): Mono<String> {

        val extension = if (type == "docs") "zip" else "jar"
        if (version.classifier == Classifier.Snapshot) {
            return snapshot(module, version).map { snapshot ->
                String.format("%s-%s-%s-%s-%s.%s", module.artifactId,
                        version.version.replace("-SNAPSHOT", ""), snapshot.timestamp,
                        snapshot.buildNumber, type, extension)
            }
        }
        return Mono.just(
                "${module.artifactId}-${version.version}-${type}.${extension}")
    }

    private fun withCaching(cacheKey: String, setArgs: SetArgs, cacheLoader: Mono<ByteArray>): Mono<ByteArray> {
        return redisConnection.reactive().get(cacheKey).onErrorResume { _ -> cacheLoader }
                .switchIfEmpty(cacheLoader.flatMap { value ->
                    redisConnection.reactive().set(cacheKey, value, setArgs).map { _ -> value }
                            .onErrorResume { _ -> Mono.justOrEmpty(value) }
                })
    }

    companion object {
        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val app = Application()
            app.startAndAwait()
        }

        val TEXT_HTML = "text/html"

        val SNAPSHOT_CACHING_DURATION = Duration.ofDays(1)
        val MAVEN_METADATA_CACHING_DURATION = Duration.ofDays(1)

        val CLIENT_CACHE_INDEX = Duration.ofDays(5)
        val CLIENT_CACHE_DOCS = Duration.ofHours(8)
        val CLIENT_CACHE_ASSETS = Duration.ofDays(30)

        private fun getFilename(req: HttpServerRequest, moduleRequest: ModuleRequest, module: Module?,
                                isJavadoc: Boolean, versionType: Classifier?): String {
            val offset = getFileNameOffset(moduleRequest, module, isJavadoc, versionType)
            return req.uri().substring(offset)
        }

        private fun getFileNameOffset(moduleRequest: ModuleRequest, module: Module?, isJavadoc: Boolean,
                                      versionType: Classifier?): Int {
            var requestFileOffset = if (isJavadoc) 7 else 13
            if (moduleRequest.version == null) {
                requestFileOffset += versionType!!.name.length + module!!.name.length
            } else {
                requestFileOffset += moduleRequest.version.length + module!!.name.length
            }

            return requestFileOffset
        }

        private fun getRepo(classifier: Classifier?): String {

            if (classifier == Classifier.Snapshot) {
                return "https://oss.sonatype.org/content/repositories/snapshots"
            }

            return "https://oss.sonatype.org/content/repositories/releases"
        }

        private fun cacheControl(duration: Duration): String {
            return "max-age=" + duration.seconds + ", public"
        }

        private fun getBytes(listOfByteArrays: List<ByteArray>): ByteArray {
            val size = listOfByteArrays.map { bytes -> bytes.size }.sum()

            val bytes = ByteArray(size)
            var offset = 0
            for (bb in listOfByteArrays) {

                System.arraycopy(bb, 0, bytes, offset, bb.size)
                offset += bb.size
            }

            return bytes
        }

        private fun getDownloadUrl(module: Module, artifactVersion: Version): String {

            if (artifactVersion.classifier == Classifier.Release || artifactVersion.classifier == Classifier.Milestone) {

                if (module.branch == "3" || module.branch == "4") {
                    return "https://github.com/lettuce-io/lettuce-core/releases/download/${artifactVersion.version}/lettuce-${artifactVersion.version}-bin.zip"
                }
                return "https://github.com/lettuce-io/lettuce-core/releases/download/${artifactVersion.version}/lettuce-core-${artifactVersion.version}-bin.zip"
            }

            return String.format("https://oss.sonatype.org/content/repositories/snapshots/%s/%s/%s",
                    module.groupId.replace('.', '/'), module.artifactId,
                    artifactVersion.version)
        }
    }
}
