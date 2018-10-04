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

import reactor.ipc.netty.http.server.HttpServerRequest
import java.util.*
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * @author Mark Paluch
 */
data class Artifact(
        val label: String,
        val module: String,
        val classifier: Classifier,
        val description: String,
        val ghBranch: String,
        val deprecated: Boolean = false,
        val reference: Boolean = false,
        val wiki: Boolean = false) {

    fun isReferenceAndWiki() = reference && wiki
}

enum class Classifier {
    Release, Milestone, Snapshot
}

data class Module(val name: String,
                  val groupId: String,
                  val artifactId: String,
                  val branch: String,
                  val ga: String,
                  val milestone: String)

/**
 * @author Mark Paluch
 */
class ModuleRequest(val name: String,
                    val version: String?,
                    val path: String,
                    val module: Module?) {

    companion object {

        fun create(req: HttpServerRequest, modules: Map<String, Module>): ModuleRequest {
            val name = req.param("module")
            return ModuleRequest(name, req.param("version"), req.path(), modules[name])
        }
    }
}

/**
 * @author Mark Paluch
 */
class RequestedVersion(version: String?, path: String?, module: Module?) {

    var versionType: Classifier?
    var isPinnedVersion: Boolean = false

    constructor(moduleRequest: ModuleRequest) : this(moduleRequest.version, moduleRequest.path, moduleRequest.module) {}

    init {
        var versionType: Classifier? = null

        if (version == null) {
            versionType = if (path!!.contains("/snapshot"))
                Classifier.Snapshot
            else
                if (path.contains("/milestone")) Classifier.Milestone else Classifier.Release
            isPinnedVersion = false
        } else {
            isPinnedVersion = true
            getVersionType(version, module!!)
        }
        this.versionType = versionType
    }

    companion object {

        fun getVersionType(version: String, module: Module): Classifier {

            val milestones = module.milestone.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            if (version.contains("-SNAPSHOT")) {
                return Classifier.Snapshot
            }

            return if (contains(version, milestones)) {
                Classifier.Milestone
            } else Classifier.Release
        }

        private fun contains(version: String, needles: Array<String>): Boolean {
            return Stream.of(*needles).anyMatch { version.contains(it) }
        }
    }
}

/**
 * @author Mark Paluch
 */
class Versions(val versions: List<Version>) {

    fun size(): Int {
        return versions.size
    }

    fun stream(): Stream<Version> {
        return versions.stream()
    }

    fun mergeWith(other: Versions): Versions {

        val result = ArrayList<Version>(this.versions.size + other.versions.size)
        result.addAll(this.versions)
        result.addAll(other.versions)

        return Versions(result.reversed())
    }

    fun getLatest(versionType: Classifier): Version? {
        return versions.stream().filter { version -> version.classifier == versionType }.findFirst().orElse(null)
    }

    fun getVersion(version: String): Version? {
        return versions.stream().filter { v -> v.version == version }.findFirst().orElse(null)
    }

    companion object {

        fun create(meta: MavenMetadata, module: Module): Versions {

            val milestonePattern = module.milestone.replace(" ", "").replace(',', '|')

            val milestone = Pattern.compile(String.format("(%s)(\\d+)?", milestonePattern))

            val versions = meta.versioning!!.versions
                    .filter { identifier -> identifier.startsWith(module.branch) }.map { identifier ->

                        var classifier = Classifier.Release

                        if (identifier.endsWith("-SNAPSHOT")) {
                            classifier = Classifier.Snapshot
                        } else if (milestone.matcher(identifier).find()) {
                            classifier = Classifier.Milestone
                        }

                        Version.create(identifier, classifier)
                    }.reversed()

            return Versions(versions)
        }
    }
}

class Version(val version: String,
              val classifier: Classifier,
              val major: Int = 0) : Comparable<Version> {


    override fun compareTo(o: Version): Int {

        val major = Integer.compare(this.major, o.major)
        return if (major != 0) major else this.version.compareTo(o.version)
    }

    companion object {

        fun create(version: String, classifier: Classifier): Version {

            val firstDot = version.indexOf('.')
            var major = 0
            if (firstDot != -1) {
                major = Integer.parseInt(version.substring(0, firstDot))
            }

            return Version(version, classifier, major)
        }
    }

}

/**
 * @author Mark Paluch
 */
data class DocumentedArtifact(val artifact: Artifact,
                              val module: Module,
                              val version: Version) {

}
