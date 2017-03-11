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

import lombok.RequiredArgsConstructor;
import lombok.Value;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Mark Paluch
 */
@Value
@RequiredArgsConstructor
public class Versions {

	private final List<Version> versions;

	public static Versions create(MavenMetadata meta, Module module) {

		String milestonePattern = module.getMilestone().replace(" ", "").replace(',', '|');

		Pattern milestone = Pattern.compile(String.format("(%s)(\\d+)?", milestonePattern));

		List<Version> versions = meta.getVersioning().getVersions().stream()
				.filter(identifier -> identifier.startsWith(module.getBranch())).map(identifier -> {

					Classifier classifier = Classifier.Release;

					if (identifier.endsWith("-SNAPSHOT")) {
						classifier = Classifier.Snapshot;
					} else if (milestone.matcher(identifier).find()) {
						classifier = Classifier.Milestone;
					}

					return Version.create(identifier, classifier);
				}).collect(Collectors.toList());

		versions.sort(Comparator.<Version> naturalOrder().reversed());

		return new Versions(versions);
	}

	public Version getLatest(Classifier versionType) {
		return versions.stream().filter(version -> version.getClassifier() == versionType).findFirst().orElse(null);
	}

	public Version getVersion(String version) {
		return versions.stream().filter(v -> v.getVersion().equals(version)).findFirst().orElse(null);
	}

	@Value
	static class Version implements Comparable<Version> {

		private final String version;
		private final Classifier classifier;
		private final int major;

		public static Version create(String version, Classifier classifier) {

			int firstDot = version.indexOf('.');
			int major = 0;
			if (firstDot != -1) {
				major = Integer.parseInt(version.substring(0, firstDot));
			}

			return new Version(version, classifier, major);
		}

		@Override
		public int compareTo(Version o) {

			int major = Integer.compare(this.major, o.major);
			return major != 0 ? major : this.version.compareTo(o.version);
		}

	}

	enum Classifier {
		Release, Milestone, Snapshot,
	}
}
