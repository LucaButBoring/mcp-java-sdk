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
