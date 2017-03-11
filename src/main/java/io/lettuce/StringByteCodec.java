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

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import java.nio.ByteBuffer;


/**
 * @author Mark Paluch
 */
class StringByteCodec implements RedisCodec<String, byte[]>
{

	public final static StringByteCodec INSTANCE = new StringByteCodec();

	@Override
	public String decodeKey(ByteBuffer bytes) {
		return StringCodec.ASCII.decodeKey(bytes);
	}

	@Override
	public byte[] decodeValue(ByteBuffer bytes) {
		return ByteArrayCodec.INSTANCE.decodeValue(bytes);
	}

	@Override
	public ByteBuffer encodeKey(String key) {
		return StringCodec.ASCII.encodeKey(key);
	}

	@Override
	public ByteBuffer encodeValue(byte[] value) {
		return ByteArrayCodec.INSTANCE.encodeValue(value);
	}
}
