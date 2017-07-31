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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

/**
 * @author Mark Paluch
 */
class QueueBackedInputStream extends InputStream {

	final BlockingQueue<InputStream> stream;
	volatile InputStream lastStream;

	private InputStream current;
	private volatile boolean closed;

	public QueueBackedInputStream(BlockingQueue<InputStream> stream) {
		this.stream = stream;
	}

	static Collector<InputStream, QueueBackedInputStream, InputStream> toInputStream() {

		return new Collector<InputStream, QueueBackedInputStream, InputStream>() {

			@Override
			public Supplier<QueueBackedInputStream> supplier() {
				return () -> new QueueBackedInputStream(new LinkedBlockingQueue<>());
			}

			@Override
			public BiConsumer<QueueBackedInputStream, InputStream> accumulator() {
				return (q, i) -> q.stream.add(i);
			}

			@Override
			public BinaryOperator<QueueBackedInputStream> combiner() {
				return (q1, q2) -> {

					while (!q2.stream.isEmpty()) {
						q2.stream.drainTo(q1.stream);
					}

					return q1;
				};
			}

			@Override
			public Function<QueueBackedInputStream, InputStream> finisher() {
				return q -> {
					if (!q.stream.isEmpty()) {
						q.lastStream = q.stream.stream().skip(q.stream.size() - 1).findAny().orElse(null);
						q.lastElementReceived();
					}

					return q;
				};
			}

			@Override
			public Set<Characteristics> characteristics() {
				return Collections.emptySet();
			}
		};
	}

	@Override
	public int available() throws IOException {

		if (closed) {
			return -1;
		}

		advance();

		return current.available();
	}

	private void advance() {

		if (lastStream == current || current instanceof LastInputStream) {
			return;
		}

		while (current == null) {
			try {
				current = stream.take();

				if (current instanceof LastInputStream) {
					current.close();
					current = null;
					return;
				}

				if (current.available() == -1) {
					current.close();
					current = null;
				}
			} catch (Exception e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
	}

	@Override
	public int read() throws IOException {

		if (closed) {
			return -1;
		}

		advance();

		if (current == null) {
			return -1;
		}

		return current.read();
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {

		if (closed) {
			return -1;
		}

		advance();

		if (current == null) {
			return -1;
		}

		int read = current.read(b, off, len);

		if (read < len) {
			current.close();
			current = null;
		}

		return read;
	}

	@Override
	public void close() throws IOException {

		if (current != null) {
			current.close();
			current = null;
		}
		closed = true;
	}

	public void lastElementReceived() {
		stream.add(LastInputStream.INSTANCE);
	}

	static class LastInputStream extends InputStream {

		static final LastInputStream INSTANCE = new LastInputStream();

		@Override
		public int read() throws IOException {
			throw new UnsupportedOperationException();
		}
	}
}
