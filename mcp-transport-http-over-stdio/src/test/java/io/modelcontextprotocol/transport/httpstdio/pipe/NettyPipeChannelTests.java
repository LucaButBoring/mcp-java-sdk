package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.ByteArrayOutputStream;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettyPipeChannelTests {

	private final CopyOnWriteArrayList<NettyPipeChannel> channels = new CopyOnWriteArrayList<>();

	private DefaultEventLoopGroup group;

	@AfterEach
	void tearDown() throws Exception {
		for (NettyPipeChannel channel : this.channels) {
			channel.close().await(5, TimeUnit.SECONDS);
		}
		if (this.group != null) {
			this.group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
		}
	}

	@Test
	void roundTripBytesInBothDirections() throws Exception {
		ChannelPair pair = newPair();
		byte[] fromA = randomBytes(1024 * 1024, 17);
		byte[] fromB = randomBytes(1024 * 1024, 23);
		CollectingHandler atA = collecting(fromB.length);
		CollectingHandler atB = collecting(fromA.length);
		pair.a.pipeline().addLast(atA);
		pair.b.pipeline().addLast(atB);

		CompletableFuture.allOf(writeChunks(pair.a, fromA), writeChunks(pair.b, fromB)).get(15, TimeUnit.SECONDS);

		assertThat(atA.result.get(15, TimeUnit.SECONDS)).isEqualTo(fromB);
		assertThat(atB.result.get(15, TimeUnit.SECONDS)).isEqualTo(fromA);
	}

	@Test
	void writabilityFlipsUnderBackpressure() throws Exception {
		ChannelPair pair = newPair();
		pair.a.config().setMaxOutboundQueuedBytes(32 * 1024);
		pair.b.config().setAutoRead(false);
		int total = 2 * 1024 * 1024;
		CollectingHandler receiver = collecting(total);
		pair.b.pipeline().addLast(receiver);
		byte[] block = randomBytes(16 * 1024, 31);

		for (int written = 0; written < total; written += block.length) {
			pair.a.writeAndFlush(Unpooled.wrappedBuffer(Arrays.copyOf(block, block.length)));
			if (!pair.a.isWritable()) {
				break;
			}
		}
		awaitCondition(() -> !pair.a.isWritable(), Duration.ofSeconds(5));

		pair.b.config().setAutoRead(true);
		pair.b.read();
		int alreadyRequested = (int) pair.a.metrics().bytesWritten();
		while (alreadyRequested < total) {
			int count = Math.min(block.length, total - alreadyRequested);
			pair.a.writeAndFlush(Unpooled.wrappedBuffer(Arrays.copyOf(block, count)));
			alreadyRequested += count;
		}
		assertThat(receiver.result.get(15, TimeUnit.SECONDS)).hasSize(total);
		awaitCondition(pair.a::isWritable, Duration.ofSeconds(5));
		// setMaxOutboundQueuedBytes also sets Netty's write-buffer watermarks (16/32
		// KiB),
		// so the same budget drives isWritable() and bounds the writer queue.
		assertThat(pair.a.config().getWriteBufferHighWaterMark()).isEqualTo(32 * 1024);
		assertThat(pair.a.config().getWriteBufferLowWaterMark()).isEqualTo(16 * 1024);
		assertThat(pair.a.metrics().peakOutboundQueuedBytes()).isLessThanOrEqualTo(32 * 1024);
	}

	@Test
	void inboundQueueBoundedWhenPipelineSlow() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setInboundReadChunkBytes(4096).setMaxInboundQueuedBytes(16 * 1024);
		CountDownLatch consumed = new CountDownLatch(128);
		pair.b.pipeline().addLast(new ReleasingHandler() {
			@Override
			protected void received(ByteBuf buffer) throws Exception {
				Thread.sleep(2);
				consumed.countDown();
			}
		});
		byte[] bytes = randomBytes(512 * 1024, 41);

		writeChunks(pair.a, bytes).get(15, TimeUnit.SECONDS);
		assertThat(consumed.await(15, TimeUnit.SECONDS)).isTrue();
		assertThat(pair.b.metrics().peakInboundQueuedBytes()).isLessThanOrEqualTo(
				pair.b.config().getMaxInboundQueuedBytes() + pair.b.config().getInboundReadChunkBytes());
	}

	@Test
	void shutdownOutputDeliversEofToPeerAndKeepsReadOpen() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setCloseOnInboundEof(false);
		CountDownLatch eof = new CountDownLatch(1);
		pair.b.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void userEventTriggered(ChannelHandlerContext context, Object event) {
				if (event == InboundEofEvent.INSTANCE) {
					eof.countDown();
				}
				context.fireUserEventTriggered(event);
			}
		});
		byte[] reverse = randomBytes(64 * 1024, 53);
		CollectingHandler atA = collecting(reverse.length);
		pair.a.pipeline().addLast(atA);

		pair.a.shutdownOutput().sync();
		assertThat(eof.await(5, TimeUnit.SECONDS)).isTrue();
		writeChunks(pair.b, reverse).get(5, TimeUnit.SECONDS);
		assertThat(atA.result.get(5, TimeUnit.SECONDS)).isEqualTo(reverse);
		assertThat(pair.a.isOpen()).isTrue();
	}

	@Test
	void closeJoinsThreadsAndFailsPendingWrites() throws Exception {
		ChannelPair pair = newPair();
		pair.a.close().sync();
		awaitCondition(() -> noPipeThreadFor(pair.a), Duration.ofSeconds(5));

		ChannelFuture write = pair.a.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 1 }));
		write.await(5, TimeUnit.SECONDS);
		assertThat(write.isSuccess()).isFalse();
		assertThat(write.cause()).isInstanceOf(ClosedChannelException.class);
	}

	@Test
	void noBlockingOnEventLoop() throws Exception {
		ChannelPair pair = newPair();
		byte[] bytes = randomBytes(4 * 1024 * 1024, 67);
		CollectingHandler receiver = collecting(bytes.length);
		CopyOnWriteArrayList<String> readThreads = new CopyOnWriteArrayList<>();
		pair.b.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext context, Object message) {
				readThreads.add(Thread.currentThread().getName());
				context.fireChannelRead(message);
			}
		}, receiver);
		AtomicLong maximumLagNanos = new AtomicLong();
		AtomicLong expected = new AtomicLong(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1));
		ScheduledFuture<?> ticker = pair.b.eventLoop().scheduleAtFixedRate(() -> {
			long now = System.nanoTime();
			maximumLagNanos.accumulateAndGet(Math.max(0, now - expected.getAndAdd(TimeUnit.MILLISECONDS.toNanos(1))),
					Math::max);
		}, 1, 1, TimeUnit.MILLISECONDS);

		writeChunks(pair.a, bytes).get(20, TimeUnit.SECONDS);
		assertThat(receiver.result.get(20, TimeUnit.SECONDS)).isEqualTo(bytes);
		ticker.cancel(false);
		assertThat(readThreads).isNotEmpty().allMatch(name -> !name.startsWith("pipe-reader-"));
		assertThat(maximumLagNanos.get()).isLessThan(TimeUnit.MILLISECONDS.toNanos(200));
	}

	/**
	 * A write future must mean "bytes are on the pipe", not "bytes are queued for the
	 * writer thread". The peer is paused so the 4 KiB pipe fills and the writer blocks.
	 */
	@Test
	void writeFutureCompletesOnlyAfterBytesReachPipe() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setAutoRead(false);
		CollectingHandler receiver = collecting(64 * 1024);
		pair.b.pipeline().addLast(receiver);

		ChannelFuture write = pair.a.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(64 * 1024, 71)));
		assertThat(write.await(300, TimeUnit.MILLISECONDS)).as("write completed while the pipe was full").isFalse();

		pair.b.config().setAutoRead(true);
		pair.b.read();
		assertThat(write.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(write.isSuccess()).isTrue();
		assertThat(receiver.result.get(5, TimeUnit.SECONDS)).hasSize(64 * 1024);
	}

	/**
	 * The HTTP/2 graceful-close shape: close as soon as the final write's future
	 * succeeds. Every byte of that write must still reach the peer.
	 */
	@RepeatedTest(20)
	void closeOnWriteSuccessDeliversEveryByte() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setCloseOnInboundEof(false).setAutoRead(false);
		byte[] bytes = randomBytes(64 * 1024, 73);
		CollectingHandler receiver = collecting(bytes.length);
		pair.b.pipeline().addLast(receiver);

		ChannelFuture write = pair.a.writeAndFlush(Unpooled.wrappedBuffer(bytes));
		write.addListener(future -> pair.a.close());
		Thread.sleep(50);
		pair.b.config().setAutoRead(true);
		pair.b.read();

		assertThat(receiver.result.get(5, TimeUnit.SECONDS)).isEqualTo(bytes);
		assertThat(write.isSuccess()).isTrue();
	}

	/**
	 * Writes held in Netty's outbound buffer by backpressure (behind a full writer queue)
	 * must be drained, not failed, by a subsequent shutdownOutput.
	 */
	@RepeatedTest(20)
	void shutdownOutputDrainsBackpressuredBacklog() throws Exception {
		ChannelPair pair = newPair();
		pair.a.config().setMaxOutboundQueuedBytes(16 * 1024);
		pair.b.config().setCloseOnInboundEof(false).setAutoRead(false);
		CountDownLatch eof = new CountDownLatch(1);
		byte[] bytes = randomBytes(256 * 1024, 79);
		CollectingHandler receiver = collecting(bytes.length);
		pair.b.pipeline().addLast(receiver, new ChannelInboundHandlerAdapter() {
			@Override
			public void userEventTriggered(ChannelHandlerContext context, Object event) {
				if (event == InboundEofEvent.INSTANCE) {
					eof.countDown();
				}
			}
		});

		CopyOnWriteArrayList<ChannelFuture> writes = new CopyOnWriteArrayList<>();
		for (int offset = 0; offset < bytes.length; offset += 8 * 1024) {
			writes.add(pair.a.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, 8 * 1024)));
		}
		ChannelFuture shutdown = pair.a.shutdownOutput();
		pair.b.config().setAutoRead(true);
		pair.b.read();

		assertThat(shutdown.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(shutdown.cause()).isNull();
		for (ChannelFuture write : writes) {
			assertThat(write.isSuccess()).isTrue();
		}
		assertThat(receiver.result.get(5, TimeUnit.SECONDS)).isEqualTo(bytes);
		assertThat(eof.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void writesAfterShutdownOutputFailAndWritesBeforeItSucceed() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setCloseOnInboundEof(false);
		CollectingHandler receiver = collecting(1024);
		pair.b.pipeline().addLast(receiver);

		ChannelFuture before = pair.a.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(1024, 83)));
		ChannelFuture shutdown = pair.a.shutdownOutput();
		ChannelFuture after = pair.a.writeAndFlush(Unpooled.wrappedBuffer(new byte[] { 1 }));

		assertThat(shutdown.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(shutdown.isSuccess()).isTrue();
		assertThat(before.isSuccess()).isTrue();
		assertThat(after.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(after.cause()).isInstanceOf(ClosedChannelException.class);
		assertThat(receiver.result.get(5, TimeUnit.SECONDS)).hasSize(1024);
	}

	/**
	 * A close that discards undelivered bytes must fail their futures, never succeed
	 * them.
	 */
	@Test
	void closeWithUndeliveredBytesFailsTheirFutures() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setAutoRead(false);
		ChannelFuture write = pair.a.writeAndFlush(Unpooled.wrappedBuffer(randomBytes(64 * 1024, 89)));
		Thread.sleep(100);
		pair.a.close().sync();

		assertThat(write.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(write.isSuccess()).isFalse();
	}

	/** Completing a write must not promote a later write that was never flushed. */
	@Test
	void writerProgressDoesNotFlushUnflushedWrites() throws Exception {
		ChannelPair pair = newPair();
		pair.b.config().setAutoRead(false);
		byte[] flushed = randomBytes(32 * 1024, 97);
		CollectingHandler receiver = collecting(flushed.length + 1);
		pair.b.pipeline().addLast(receiver);

		ChannelFuture first = pair.a.writeAndFlush(Unpooled.wrappedBuffer(flushed));
		ChannelFuture unflushed = pair.a.write(Unpooled.wrappedBuffer(new byte[] { 7 }));
		pair.b.config().setAutoRead(true);
		pair.b.read();
		assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(200);

		assertThat(unflushed.isDone()).isFalse();
		assertThat(pair.a.metrics().bytesWritten()).isEqualTo(flushed.length);
		pair.a.flush();
		assertThat(unflushed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receiver.result.get(5, TimeUnit.SECONDS)).hasSize(flushed.length + 1);
	}

	/**
	 * Close while the writer holds handed-off messages: every future fails, nothing
	 * leaks.
	 */
	@Test
	void closeWhileHandedOffFailsEveryFutureAndReleasesEveryBuffer() throws Exception {
		ScriptedDuplex duplex = new ScriptedDuplex(-1);
		NettyPipeChannel channel = register(duplex);
		ByteBuf[] buffers = { Unpooled.buffer().writeZero(1024), Unpooled.buffer().writeZero(1024),
				Unpooled.buffer().writeZero(1024) };
		ChannelFuture[] writes = new ChannelFuture[buffers.length];
		for (int i = 0; i < buffers.length; i++) {
			writes[i] = channel.writeAndFlush(buffers[i]);
		}
		assertThat(duplex.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();

		channel.close().sync();

		for (ChannelFuture write : writes) {
			assertThat(write.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(write.isSuccess()).isFalse();
		}
		awaitCondition(() -> Arrays.stream(buffers).allMatch(buffer -> buffer.refCnt() == 0), Duration.ofSeconds(5));
		awaitCondition(() -> noPipeThreadFor(channel), Duration.ofSeconds(5));
	}

	/**
	 * A writer IOException with more messages queued fails the rest and leaks nothing.
	 */
	@Test
	void writerFailureFailsQueuedFuturesAndReleasesEveryBuffer() throws Exception {
		ScriptedDuplex duplex = new ScriptedDuplex(1);
		duplex.gate.countDown();
		NettyPipeChannel channel = register(duplex);
		CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
		channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
				errors.add(cause);
			}
		});
		ByteBuf[] buffers = { Unpooled.buffer().writeZero(1024), Unpooled.buffer().writeZero(1024),
				Unpooled.buffer().writeZero(1024) };
		ChannelFuture[] writes = new ChannelFuture[buffers.length];
		for (int i = 0; i < buffers.length; i++) {
			writes[i] = channel.write(buffers[i]);
		}
		channel.flush();

		assertThat(writes[0].await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(writes[0].isSuccess()).isTrue();
		for (int i = 1; i < writes.length; i++) {
			assertThat(writes[i].await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(writes[i].isSuccess()).isFalse();
		}
		awaitCondition(() -> !channel.isOpen(), Duration.ofSeconds(5));
		assertThat(errors).anyMatch(error -> error instanceof java.io.IOException);
		awaitCondition(() -> Arrays.stream(buffers).allMatch(buffer -> buffer.refCnt() == 0), Duration.ofSeconds(5));
	}

	/** Unsupported and empty entries behind handed-off data neither stall nor reorder. */
	@Test
	void unsupportedAndEmptyMessagesBehindHandedOffDataDoNotStall() throws Exception {
		ScriptedDuplex duplex = new ScriptedDuplex(-1);
		NettyPipeChannel channel = register(duplex);
		ChannelFuture first = channel.write(Unpooled.buffer().writeBytes(new byte[] { 1, 2 }));
		ChannelFuture unsupported = channel.write("not a ByteBuf");
		ChannelFuture empty = channel.write(Unpooled.EMPTY_BUFFER);
		ChannelFuture last = channel.write(Unpooled.buffer().writeBytes(new byte[] { 3 }));
		channel.flush();
		assertThat(duplex.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(first.isDone()).isFalse();

		duplex.gate.countDown();

		assertThat(last.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(first.isSuccess()).isTrue();
		assertThat(unsupported.cause()).isInstanceOf(UnsupportedOperationException.class);
		assertThat(empty.isSuccess()).isTrue();
		assertThat(last.isSuccess()).isTrue();
		assertThat(duplex.written()).containsExactly(1, 2, 3);
	}

	/** A second shutdownOutput during a pending drain joins it instead of racing it. */
	@Test
	void repeatedShutdownOutputDuringDrainCompletesBothAfterDelivery() throws Exception {
		ScriptedDuplex duplex = new ScriptedDuplex(-1);
		NettyPipeChannel channel = register(duplex);
		ChannelFuture write = channel.writeAndFlush(Unpooled.buffer().writeBytes(new byte[] { 9 }));
		assertThat(duplex.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
		ChannelFuture firstShutdown = channel.shutdownOutput();
		ChannelFuture secondShutdown = channel.shutdownOutput();
		Thread.sleep(100);
		assertThat(firstShutdown.isDone()).isFalse();
		assertThat(secondShutdown.isDone()).isFalse();
		assertThat(duplex.outputShutdown).isFalse();

		duplex.gate.countDown();

		assertThat(firstShutdown.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(secondShutdown.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(firstShutdown.isSuccess()).isTrue();
		assertThat(secondShutdown.isSuccess()).isTrue();
		assertThat(write.isSuccess()).isTrue();
		assertThat(duplex.outputShutdown).isTrue();
		assertThat(channel.shutdownOutput().await(5, TimeUnit.SECONDS)).isTrue();
	}

	/**
	 * Documented policy: one message larger than maxOutboundQueuedBytes is still handed
	 * off alone so it cannot deadlock; the cap bounds everything queued behind it.
	 */
	@Test
	void oversizedSingleMessageIsDeliveredAndHoldsBackLaterMessages() throws Exception {
		ScriptedDuplex duplex = new ScriptedDuplex(-1);
		NettyPipeChannel channel = register(duplex, 4 * 1024);
		ChannelFuture oversized = channel.writeAndFlush(Unpooled.buffer().writeZero(16 * 1024));
		ChannelFuture next = channel.writeAndFlush(Unpooled.buffer().writeZero(1024));
		assertThat(duplex.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(100);
		assertThat(channel.metrics().peakOutboundQueuedBytes()).isEqualTo(16 * 1024);

		duplex.gate.countDown();

		assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(oversized.isSuccess()).isTrue();
		assertThat(next.isSuccess()).isTrue();
		assertThat(duplex.written()).hasSize(17 * 1024);
	}

	private NettyPipeChannel register(DuplexByteChannel duplex) throws Exception {
		return register(duplex, 0);
	}

	private NettyPipeChannel register(DuplexByteChannel duplex, int maxOutboundQueuedBytes) throws Exception {
		if (this.group == null) {
			this.group = new DefaultEventLoopGroup(2, new DefaultThreadFactory("pipe-test-event-loop"));
		}
		NettyPipeChannel channel = new NettyPipeChannel(duplex);
		if (maxOutboundQueuedBytes > 0) {
			channel.config().setMaxOutboundQueuedBytes(maxOutboundQueuedBytes);
		}
		this.channels.add(channel);
		this.group.register(channel).sync();
		return channel;
	}

	private ChannelPair newPair() throws Exception {
		if (this.group == null) {
			this.group = new DefaultEventLoopGroup(2, new DefaultThreadFactory("pipe-test-event-loop"));
		}
		DuplexByteChannel[] duplex = InMemoryDuplexByteChannel.pair(4096);
		NettyPipeChannel a = new NettyPipeChannel(duplex[0]);
		NettyPipeChannel b = new NettyPipeChannel(duplex[1]);
		this.channels.add(a);
		this.channels.add(b);
		this.group.register(a).sync();
		this.group.register(b).sync();
		return new ChannelPair(a, b);
	}

	private static CompletableFuture<Void> writeChunks(NettyPipeChannel channel, byte[] bytes) {
		CompletableFuture<Void> complete = new CompletableFuture<>();
		writeNext(channel, bytes, 0, complete);
		return complete;
	}

	private static void writeNext(NettyPipeChannel channel, byte[] bytes, int offset,
			CompletableFuture<Void> complete) {
		if (offset == bytes.length) {
			complete.complete(null);
			return;
		}
		int count = Math.min(16 * 1024, bytes.length - offset);
		channel.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, count)).addListener(future -> {
			if (!future.isSuccess()) {
				complete.completeExceptionally(future.cause());
			}
			else {
				writeNext(channel, bytes, offset + count, complete);
			}
		});
	}

	private static CollectingHandler collecting(int expectedBytes) {
		return new CollectingHandler(expectedBytes);
	}

	private static byte[] randomBytes(int length, long seed) {
		byte[] bytes = new byte[length];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}

	private static boolean noPipeThreadFor(NettyPipeChannel channel) {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(Thread::isAlive)
			.noneMatch(thread -> thread.getName().startsWith("pipe-reader-")
					|| thread.getName().startsWith("pipe-writer-"));
	}

	private static void awaitCondition(CheckedBoolean condition, Duration timeout) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(condition.get()).isTrue();
	}

	private record ChannelPair(NettyPipeChannel a, NettyPipeChannel b) {
	}

	/**
	 * A duplex whose writes block on {@link #gate} and can fail on a given write index.
	 * Inbound never produces data and returns EOF only once closed.
	 */
	private static final class ScriptedDuplex implements DuplexByteChannel {

		final CountDownLatch writeEntered = new CountDownLatch(1);

		final CountDownLatch gate = new CountDownLatch(1);

		final CountDownLatch closed = new CountDownLatch(1);

		volatile boolean outputShutdown;

		private final ByteArrayOutputStream written = new ByteArrayOutputStream();

		private final int failOnWriteIndex;

		private int writes;

		ScriptedDuplex(int failOnWriteIndex) {
			this.failOnWriteIndex = failOnWriteIndex;
		}

		synchronized byte[] written() {
			return this.written.toByteArray();
		}

		@Override
		public java.io.InputStream inbound() {
			return new java.io.InputStream() {
				@Override
				public int read() throws java.io.IOException {
					byte[] one = new byte[1];
					return read(one, 0, 1) < 0 ? -1 : one[0];
				}

				@Override
				public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
					try {
						ScriptedDuplex.this.closed.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new java.io.InterruptedIOException();
					}
					return -1;
				}
			};
		}

		@Override
		public java.io.OutputStream outbound() {
			return new java.io.OutputStream() {
				@Override
				public void write(int b) throws java.io.IOException {
					write(new byte[] { (byte) b }, 0, 1);
				}

				@Override
				public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
					ScriptedDuplex.this.writeEntered.countDown();
					try {
						ScriptedDuplex.this.gate.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new java.io.InterruptedIOException();
					}
					synchronized (ScriptedDuplex.this) {
						if (ScriptedDuplex.this.closed.getCount() == 0 || ScriptedDuplex.this.outputShutdown) {
							throw new java.io.IOException("closed");
						}
						if (ScriptedDuplex.this.writes++ == ScriptedDuplex.this.failOnWriteIndex) {
							throw new java.io.IOException("scripted write failure");
						}
						ScriptedDuplex.this.written.write(bytes, offset, length);
					}
				}
			};
		}

		@Override
		public void shutdownOutput() {
			this.outputShutdown = true;
		}

		@Override
		public void close() {
			this.outputShutdown = true;
			this.closed.countDown();
			this.gate.countDown();
		}

		@Override
		public String describe() {
			return "scripted";
		}

	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

	private abstract static class ReleasingHandler extends ChannelInboundHandlerAdapter {

		@Override
		public final void channelRead(ChannelHandlerContext context, Object message) throws Exception {
			ByteBuf buffer = (ByteBuf) message;
			try {
				received(buffer);
			}
			finally {
				buffer.release();
			}
		}

		protected abstract void received(ByteBuf buffer) throws Exception;

	}

	private static final class CollectingHandler extends ReleasingHandler {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		private final int expectedBytes;

		private final CompletableFuture<byte[]> result = new CompletableFuture<>();

		private CollectingHandler(int expectedBytes) {
			this.expectedBytes = expectedBytes;
		}

		@Override
		protected void received(ByteBuf buffer) {
			byte[] chunk = new byte[buffer.readableBytes()];
			buffer.readBytes(chunk);
			this.bytes.writeBytes(chunk);
			if (this.bytes.size() >= this.expectedBytes) {
				this.result.complete(this.bytes.toByteArray());
			}
		}

	}

}
