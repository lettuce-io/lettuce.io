/*
 * Copyright 2018-2019 the original author or authors.
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

import org.springframework.core.io.ClassPathResource
import org.springframework.util.StreamUtils

import javax.activation.MimetypesFileTypeMap
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections

/**
 * @author Mark Paluch
 */
internal object ServerUtils {

    val mimeTypes: MimetypesFileTypeMap
        get() {

            val fileTypeMap = MimetypesFileTypeMap()

            fileTypeMap.addMimeTypes("text/css css text CSS")
            fileTypeMap.addMimeTypes("text/javascript js text JS")
            fileTypeMap.addMimeTypes("image/png png image PNG")
            fileTypeMap.addMimeTypes("application/x-font-woff woff font WOFF")
            fileTypeMap.addMimeTypes("application/x-font-woff woff2 font WOFF2")

            return fileTypeMap
        }

    @Throws(IOException::class)
    fun resolveContentPath(): Path {
        val cp = ClassPathResource("static")
        if (cp.isFile) {
            return Paths.get(cp.uri)
        }
        val fs = FileSystems.newFileSystem(cp.uri, emptyMap<String, Any>())
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                fs.close()
            } catch (io: IOException) {
                // ignore
            }
        })
        return fs.getPath("static")
    }

    @Throws(IOException::class)
    fun getResourceAsString(resource: String): String {
        ServerUtils::class.java.getResourceAsStream(resource).use { `is` -> return StreamUtils.copyToString(`is`, StandardCharsets.UTF_8) }
    }
}
