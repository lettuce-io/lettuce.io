/*
 * Copyright 2018-2020 the original author or authors.
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

import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec

import java.nio.ByteBuffer

/**
 * @author Mark Paluch
 */
internal class StringByteCodec : RedisCodec<String, ByteArray> {

    override fun decodeKey(bytes: ByteBuffer): String {
        return StringCodec.ASCII.decodeKey(bytes)
    }

    override fun decodeValue(bytes: ByteBuffer): ByteArray {
        return ByteArrayCodec.INSTANCE.decodeValue(bytes)
    }

    override fun encodeKey(key: String): ByteBuffer {
        return StringCodec.ASCII.encodeKey(key)
    }

    override fun encodeValue(value: ByteArray): ByteBuffer {
        return ByteArrayCodec.INSTANCE.encodeValue(value)
    }

    companion object {

        val INSTANCE = StringByteCodec()
    }
}
