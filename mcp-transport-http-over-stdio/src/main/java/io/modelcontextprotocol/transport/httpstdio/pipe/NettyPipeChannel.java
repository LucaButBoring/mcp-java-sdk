package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.ReferenceCountUtil;

/**
 * Adapts a blocking {@link DuplexByteChannel} to Netty without performing blocking stream
 * operations on an event-loop thread.
 *
 * <p>
 * The channel activates during registration and starts one daemon reader and one daemon
 * writer. It is compatible with single-thread event loops such as
 * {@code DefaultEventLoop}; it has no selector or native-loop requirement.
 * </p>
 */
public final class NettyPipeChannel extends AbstractChannel {

	private static final ChannelMetadata METADATA = new ChannelMetadata(false);

	private static final AtomicLong CHANNEL_IDS = new AtomicLong();

	private final DuplexByteChannel duplex;

	private final PipeChannelConfig config;

	private final long channelId = CHANNEL_IDS.incrementAndGet();

	private final Object readMonitor = new Object();

	private final Object writeMonitor = new Object();

	private final Queue<ByteBuf> outboundQueue = new ArrayDeque<>();

	private final AtomicBoolean open = new AtomicBoolean(true);

	private final AtomicBoolean active = new AtomicBoolean();

	private final AtomicBoolean threadsStarted = new AtomicBoolean();

	private final AtomicBoolean outputShutdownRequested = new AtomicBoolean();

	private final AtomicBoolean outputShutdown = new AtomicBoolean();

	private final AtomicLong inboundQueuedBytes = new AtomicLong();

	private final AtomicLong outboundQueuedBytes = new AtomicLong();

	private final AtomicLong peakInboundQueuedBytes = new AtomicLong();

	private final AtomicLong peakOutboundQueuedBytes = new AtomicLong();

	private final AtomicLong bytesRead = new AtomicLong();

	private final AtomicLong bytesWritten = new AtomicLong();

	private final AtomicLong readerBlockedNanos = new AtomicLong();

	private final AtomicLong writerBlockedNanos = new AtomicLong();

	private volatile boolean readRequested;

	private volatile Thread readerThread;

	private volatile Thread writerThread;

	private volatile ChannelPromise shutdownOutputPromise;

	public NettyPipeChannel(DuplexByteChannel duplex) {
		super(null);
		this.duplex = Objects.requireNonNull(duplex, "duplex");
		this.config = new PipeChannelConfig(this);
	}

	/**
	 * Creates a channel whose configuration is copied from the supplied template.
	 * @param duplex blocking duplex byte transport
	 * @param template configuration to copy, or {@code null} for defaults
	 */
	public NettyPipeChannel(DuplexByteChannel duplex, PipeChannelConfig template) {
		this(duplex);
		if (template != null) {
			this.config.setInboundReadChunkBytes(template.getInboundReadChunkBytes())
				.setMaxInboundQueuedBytes(template.getMaxInboundQueuedBytes())
				.setMaxOutboundQueuedBytes(template.getMaxOutboundQueuedBytes())
				.setCloseOnInboundEof(template.isCloseOnInboundEof())
				.setThreadJoinTimeoutMillis(template.getThreadJoinTimeoutMillis())
				.setAutoRead(template.isAutoRead());
		}
	}

	@Override
	public PipeChannelConfig config() {
		return this.config;
	}

	@Override
	public boolean isOpen() {
		return this.open.get();
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	@Override
	public ChannelMetadata metadata() {
		return METADATA;
	}

	public PipeChannelMetrics metrics() {
		return new PipeChannelMetrics(this.peakInboundQueuedBytes.get(), this.peakOutboundQueuedBytes.get(),
				this.bytesRead.get(), this.bytesWritten.get(), this.readerBlockedNanos.get(),
				this.writerBlockedNanos.get());
	}

	public ChannelFuture shutdownOutput() {
		EventLoop loop = eventLoop();
		ChannelPromise promise = newPromise();
		Runnable request = () -> {
			if (!isOpen()) {
				promise.tryFailure(new ClosedChannelException());
				return;
			}
			if (this.outputShutdown.get()) {
				promise.trySuccess();
				return;
			}
			this.shutdownOutputPromise = promise;
			this.outputShutdownRequested.set(true);
			synchronized (this.writeMonitor) {
				this.writeMonitor.notifyAll();
			}
		};
		if (loop.inEventLoop()) {
			request.run();
		}
		else {
			loop.execute(request);
		}
		return promise;
	}

	@Override
	protected AbstractUnsafe newUnsafe() {
		return new AbstractUnsafe() {
			@Override
			public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
				promise.tryFailure(new UnsupportedOperationException("Pipe channels are connected at construction"));
			}
		};
	}

	@Override
	protected boolean isCompatible(EventLoop loop) {
		return loop instanceof SingleThreadEventLoop;
	}

	@Override
	protected SocketAddress localAddress0() {
		return null;
	}

	@Override
	protected SocketAddress remoteAddress0() {
		return null;
	}

	@Override
	protected void doBind(SocketAddress localAddress) {
		throw new UnsupportedOperationException("Pipe channels cannot bind");
	}

	@Override
	protected void doDisconnect() throws Exception {
		doClose();
	}

	@Override
	protected void doClose() {
		if (!this.open.compareAndSet(true, false)) {
			return;
		}
		this.active.set(false);
		Thread reader = this.readerThread;
		Thread writer = this.writerThread;
		if (reader != null) {
			reader.interrupt();
		}
		if (writer != null) {
			writer.interrupt();
		}
		synchronized (this.readMonitor) {
			this.readMonitor.notifyAll();
		}
		synchronized (this.writeMonitor) {
			this.writeMonitor.notifyAll();
		}
		Thread closer = new Thread(() -> closeResourcesAndJoin(reader, writer), "pipe-closer-" + this.channelId);
		closer.setDaemon(true);
		closer.start();
	}

	@Override
	protected void doBeginRead() {
		this.readRequested = true;
		synchronized (this.readMonitor) {
			this.readMonitor.notifyAll();
		}
	}

	@Override
	protected void doWrite(ChannelOutboundBuffer in) throws Exception {
		if (this.outputShutdownRequested.get() || this.outputShutdown.get()) {
			throw new ClosedChannelException();
		}
		for (;;) {
			Object message = in.current();
			if (message == null) {
				return;
			}
			if (!(message instanceof ByteBuf buffer)) {
				in.remove(new UnsupportedOperationException("NettyPipeChannel accepts ByteBuf messages only"));
				continue;
			}
			int readable = buffer.readableBytes();
			if (readable == 0) {
				in.remove();
				continue;
			}
			synchronized (this.writeMonitor) {
				long queued = this.outboundQueuedBytes.get();
				if (queued + readable > this.config.getMaxOutboundQueuedBytes()) {
					in.setUserDefinedWritability(1, false);
					return;
				}
				ByteBuf queuedBuffer = buffer.readRetainedSlice(readable);
				this.outboundQueue.add(queuedBuffer);
				long nowQueued = this.outboundQueuedBytes.addAndGet(readable);
				updatePeak(this.peakOutboundQueuedBytes, nowQueued);
				in.progress(readable);
				in.remove();
				this.writeMonitor.notifyAll();
			}
		}
	}

	@Override
	protected void doRegister() {
		startThreads();
		this.active.set(true);
	}

	@Override
	protected void doDeregister() {
	}

	private void startThreads() {
		if (!this.threadsStarted.compareAndSet(false, true)) {
			return;
		}
		this.readerThread = daemonThread("pipe-reader-" + this.channelId, this::readLoop);
		this.writerThread = daemonThread("pipe-writer-" + this.channelId, this::writeLoop);
		this.readerThread.start();
		this.writerThread.start();
	}

	private Thread daemonThread(String name, Runnable task) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		return thread;
	}

	private void readLoop() {
		byte[] bytes = new byte[this.config.getInboundReadChunkBytes()];
		try {
			while (isOpen()) {
				awaitReadPermit();
				if (!isOpen()) {
					return;
				}
				assertNotOnEventLoop();
				int count = this.duplex.inbound().read(bytes);
				if (count < 0) {
					onInboundEof();
					return;
				}
				if (count == 0) {
					continue;
				}
				ByteBuf buffer = alloc().buffer(count, count).writeBytes(bytes, 0, count);
				long queued = this.inboundQueuedBytes.addAndGet(count);
				updatePeak(this.peakInboundQueuedBytes, queued);
				this.bytesRead.addAndGet(count);
				if (!this.config.isAutoRead()) {
					this.readRequested = false;
				}
				int delivered = count;
				Runnable delivery = () -> {
					try {
						if (isOpen()) {
							pipeline().fireChannelRead(buffer);
							pipeline().fireChannelReadComplete();
						}
						else {
							ReferenceCountUtil.release(buffer);
						}
					}
					finally {
						this.inboundQueuedBytes.addAndGet(-delivered);
						synchronized (this.readMonitor) {
							this.readMonitor.notifyAll();
						}
					}
				};
				try {
					eventLoop().execute(delivery);
				}
				catch (RuntimeException failure) {
					ReferenceCountUtil.release(buffer);
					this.inboundQueuedBytes.addAndGet(-delivered);
					if (isOpen()) {
						this.open.set(false);
						this.active.set(false);
						try {
							this.duplex.close();
						}
						catch (IOException ignored) {
						}
					}
					return;
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (IOException ex) {
			if (isOpen()) {
				fireExceptionAndClose(ex);
			}
		}
	}

	private void awaitReadPermit() throws InterruptedException {
		long started = System.nanoTime();
		boolean blocked = false;
		synchronized (this.readMonitor) {
			while (isOpen() && ((!this.config.isAutoRead() && !this.readRequested)
					|| this.inboundQueuedBytes.get() >= this.config.getMaxInboundQueuedBytes())) {
				blocked = true;
				assertNotOnEventLoop();
				this.readMonitor.wait();
			}
		}
		if (blocked) {
			this.readerBlockedNanos.addAndGet(System.nanoTime() - started);
		}
	}

	private void onInboundEof() {
		eventLoop().execute(() -> {
			pipeline().fireUserEventTriggered(InboundEofEvent.INSTANCE);
			if (this.config.isCloseOnInboundEof()) {
				close();
			}
		});
	}

	private void writeLoop() {
		try {
			while (isOpen()) {
				ByteBuf buffer = awaitOutboundBuffer();
				if (buffer == null) {
					if (this.outputShutdownRequested.get()) {
						completeOutputShutdown();
						return;
					}
					continue;
				}
				int size = buffer.readableBytes();
				try {
					assertNotOnEventLoop();
					buffer.readBytes(this.duplex.outbound(), size);
					assertNotOnEventLoop();
					this.duplex.outbound().flush();
					this.bytesWritten.addAndGet(size);
				}
				finally {
					ReferenceCountUtil.release(buffer);
					this.outboundQueuedBytes.addAndGet(-size);
				}
				eventLoop().execute(() -> {
					ChannelOutboundBuffer pending = unsafe().outboundBuffer();
					if (pending != null) {
						pending.setUserDefinedWritability(1, true);
					}
					unsafe().flush();
				});
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (IOException ex) {
			if (isOpen()) {
				fireExceptionAndClose(ex);
			}
		}
	}

	private ByteBuf awaitOutboundBuffer() throws InterruptedException {
		long started = System.nanoTime();
		boolean blocked = false;
		synchronized (this.writeMonitor) {
			while (isOpen() && this.outboundQueue.isEmpty() && !this.outputShutdownRequested.get()) {
				blocked = true;
				assertNotOnEventLoop();
				this.writeMonitor.wait();
			}
			if (blocked) {
				this.writerBlockedNanos.addAndGet(System.nanoTime() - started);
			}
			return this.outboundQueue.poll();
		}
	}

	private void completeOutputShutdown() throws IOException {
		assertNotOnEventLoop();
		this.duplex.shutdownOutput();
		this.outputShutdown.set(true);
		eventLoop().execute(() -> {
			pipeline().fireUserEventTriggered(OutboundShutdownEvent.INSTANCE);
			ChannelPromise promise = this.shutdownOutputPromise;
			if (promise != null) {
				promise.trySuccess();
			}
		});
	}

	private void fireExceptionAndClose(Throwable failure) {
		eventLoop().execute(() -> {
			pipeline().fireExceptionCaught(failure);
			close();
		});
	}

	private void closeResourcesAndJoin(Thread reader, Thread writer) {
		assertNotOnEventLoop();
		try {
			this.duplex.close();
		}
		catch (IOException ignored) {
		}
		releaseOutboundQueue();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.config.getThreadJoinTimeoutMillis());
		joinUntil(reader, deadline);
		joinUntil(writer, deadline);
		ChannelPromise promise = this.shutdownOutputPromise;
		if (promise != null) {
			promise.tryFailure(new ClosedChannelException());
		}
	}

	private void joinUntil(Thread thread, long deadlineNanos) {
		if (thread == null || thread == Thread.currentThread()) {
			return;
		}
		long remaining = deadlineNanos - System.nanoTime();
		if (remaining <= 0) {
			return;
		}
		try {
			assertNotOnEventLoop();
			thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void releaseOutboundQueue() {
		synchronized (this.writeMonitor) {
			ByteBuf buffer;
			while ((buffer = this.outboundQueue.poll()) != null) {
				this.outboundQueuedBytes.addAndGet(-buffer.readableBytes());
				ReferenceCountUtil.release(buffer);
			}
		}
	}

	private void assertNotOnEventLoop() {
		EventLoop loop = eventLoop();
		if (loop != null && loop.inEventLoop()) {
			throw new IllegalStateException("Blocking pipe operation attempted on the Netty event loop");
		}
	}

	private static void updatePeak(AtomicLong peak, long candidate) {
		peak.accumulateAndGet(candidate, Math::max);
	}

}
