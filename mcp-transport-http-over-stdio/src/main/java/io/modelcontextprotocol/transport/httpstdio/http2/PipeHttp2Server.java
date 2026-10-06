package io.modelcontextprotocol.transport.httpstdio.http2;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.NettyPipeChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.util.ReferenceCountUtil;

/** Prior-knowledge HTTP/2 server carried by a {@link NettyPipeChannel}. */
public final class PipeHttp2Server {

	public static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 16L * 1024 * 1024;

	private final NettyPipeChannel channel;

	private final CompletableFuture<Void> closed = new CompletableFuture<>();

	private final CompletableFuture<Void> clientSettingsReceived = new CompletableFuture<>();

	private final CompletableFuture<Void> goAwayReceived = new CompletableFuture<>();

	private PipeHttp2Server(NettyPipeChannel channel) {
		this.channel = channel;
		channel.closeFuture().addListener(ignored -> this.closed.complete(null));
	}

	public static PipeHttp2Server start(DuplexByteChannel duplex, EventLoopGroup group, PipeChannelConfig config,
			Http2RequestHandler handler) {
		return start(duplex, group, config, DEFAULT_MAX_REQUEST_BODY_BYTES, handler);
	}

	/**
	 * Starts a server whose aggregated request bodies are bounded. A stream exceeding
	 * {@code maxRequestBodyBytes} is reset with {@link Http2Error#ENHANCE_YOUR_CALM}; the
	 * connection and sibling streams remain usable.
	 */
	public static PipeHttp2Server start(DuplexByteChannel duplex, EventLoopGroup group, PipeChannelConfig config,
			long maxRequestBodyBytes, Http2RequestHandler handler) {
		Objects.requireNonNull(handler, "handler");
		if (maxRequestBodyBytes <= 0) {
			throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
		}
		NettyPipeChannel channel = new NettyPipeChannel(duplex, config);
		PipeHttp2Server server = new PipeHttp2Server(channel);
		channel.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
		channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext context, Object message) {
				if (message instanceof Http2SettingsFrame) {
					server.clientSettingsReceived.complete(null);
					ReferenceCountUtil.release(message);
					return;
				}
				if (message instanceof Http2GoAwayFrame) {
					server.goAwayReceived.complete(null);
					ReferenceCountUtil.release(message);
					return;
				}
				if (message instanceof Http2SettingsAckFrame) {
					ReferenceCountUtil.release(message);
					return;
				}
				context.fireChannelRead(message);
			}
		});
		channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel child) {
				child.pipeline().addLast(new RequestStreamHandler(handler, maxRequestBodyBytes));
			}
		}));
		group.register(channel).syncUninterruptibly();
		return server;
	}

	public CompletableFuture<Void> goAway() {
		return write(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));
	}

	public CompletableFuture<Void> close() {
		CompletableFuture<Void> result = new CompletableFuture<>();
		this.channel.close().addListener(future -> {
			if (future.isSuccess()) {
				result.complete(null);
			}
			else {
				result.completeExceptionally(future.cause());
			}
		});
		return result;
	}

	/**
	 * Half-closes the connection; see {@link PipeHttp2Client#drainOutput()}.
	 * @return a future completed once the drained output has been closed
	 */
	public CompletableFuture<Void> drainOutput() {
		CompletableFuture<Void> result = new CompletableFuture<>();
		this.channel.shutdownOutput().addListener(future -> {
			if (future.isSuccess()) {
				result.complete(null);
			}
			else {
				result.completeExceptionally(future.cause());
			}
		});
		return result;
	}

	public NettyPipeChannel channel() {
		return this.channel;
	}

	public CompletableFuture<Void> closed() {
		return this.closed;
	}

	public CompletableFuture<Void> clientSettingsReceived() {
		return this.clientSettingsReceived;
	}

	public CompletableFuture<Void> goAwayReceived() {
		return this.goAwayReceived;
	}

	private CompletableFuture<Void> write(Object frame) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		this.channel.writeAndFlush(frame).addListener(future -> {
			if (future.isSuccess()) {
				result.complete(null);
			}
			else {
				result.completeExceptionally(future.cause());
			}
		});
		return result;
	}

	@ChannelHandler.Sharable
	private static final class RequestStreamHandler extends ChannelInboundHandlerAdapter {

		private final Http2RequestHandler handler;

		private final long maxRequestBodyBytes;

		private Http2Headers headers;

		private CompositeByteBuf body;

		private long bodyBytes;

		private boolean requestComplete;

		private boolean rejected;

		private StreamWriter writer;

		private RequestStreamHandler(Http2RequestHandler handler, long maxRequestBodyBytes) {
			this.handler = handler;
			this.maxRequestBodyBytes = maxRequestBodyBytes;
		}

		@Override
		public void channelRead(ChannelHandlerContext context, Object message) {
			try {
				if (message instanceof Http2HeadersFrame frame) {
					this.headers = frame.headers();
					if (frame.isEndStream()) {
						dispatch(context);
					}
				}
				else if (message instanceof Http2DataFrame frame && !this.rejected) {
					int readable = frame.content().readableBytes();
					if (this.bodyBytes > this.maxRequestBodyBytes - readable) {
						rejectOversized(context);
					}
					else {
						this.bodyBytes += readable;
						if (this.body == null) {
							this.body = context.alloc().compositeBuffer();
						}
						this.body.addComponent(true, frame.content().retain());
						if (frame.isEndStream()) {
							dispatch(context);
						}
					}
				}
				else if (message instanceof Http2ResetFrame frame) {
					cancel(frame.errorCode());
				}
			}
			finally {
				ReferenceCountUtil.release(message);
			}
		}

		private void rejectOversized(ChannelHandlerContext context) {
			this.rejected = true;
			this.requestComplete = true;
			releaseBody();
			Http2Headers limitHeaders = new DefaultHttp2Headers().status("413").add("x-http2-body-too-large", "1");
			context.write(new DefaultHttp2HeadersFrame(limitHeaders, false));
			context.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.ENHANCE_YOUR_CALM));
		}

		private void dispatch(ChannelHandlerContext context) {
			if (this.rejected || this.requestComplete) {
				return;
			}
			this.requestComplete = true;
			this.writer = new StreamWriter(context);
			CompositeByteBuf requestBody = this.body == null ? context.alloc().compositeBuffer() : this.body;
			this.body = null;
			try {
				String method = this.headers.method() == null ? null : this.headers.method().toString();
				String path = this.headers.path() == null ? null : this.headers.path().toString();
				this.handler.handle(new Http2Request(method, path, this.headers, requestBody), this.writer);
			}
			catch (Exception failure) {
				this.writer.reset(Http2Error.INTERNAL_ERROR.code());
				context.fireExceptionCaught(failure);
			}
			finally {
				requestBody.release();
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext context) {
			if (this.writer != null && !this.writer.ended.get()) {
				this.writer.cancel();
			}
			else if (!this.requestComplete) {
				cancel(Http2Error.CANCEL.code());
			}
			releaseBody();
			context.fireChannelInactive();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
			releaseBody();
			if (this.writer != null) {
				this.writer.cancel();
			}
			context.close();
		}

		private void cancel(long errorCode) {
			if (this.writer != null) {
				this.writer.cancel();
			}
		}

		private void releaseBody() {
			if (this.body != null) {
				this.body.release();
				this.body = null;
			}
		}

	}

	private static final class StreamWriter implements Http2ResponseWriter {

		private final ChannelHandlerContext context;

		private final AtomicReference<Runnable> cancelled = new AtomicReference<>(() -> {
		});

		private final AtomicBoolean cancellationFired = new AtomicBoolean();

		private final AtomicBoolean ended = new AtomicBoolean();

		private final AtomicBoolean headersSent = new AtomicBoolean();

		private StreamWriter(ChannelHandlerContext context) {
			this.context = context;
		}

		@Override
		public void headers(String status, Http2Headers headers) {
			execute(() -> writeHeaders(status, headers));
		}

		@Override
		public void data(io.netty.buffer.ByteBuf data, boolean endStream) {
			execute(() -> {
				if (!this.headersSent.get()) {
					writeHeaders("200", null);
				}
				if (endStream) {
					this.ended.set(true);
				}
				this.context.writeAndFlush(new DefaultHttp2DataFrame(data, endStream));
			});
		}

		@Override
		public void end() {
			if (!this.ended.compareAndSet(false, true)) {
				return;
			}
			execute(() -> {
				if (!this.headersSent.get()) {
					this.context
						.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), true));
				}
				else {
					this.context.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true));
				}
			});
		}

		@Override
		public void reset(long errorCode) {
			this.ended.set(true);
			execute(() -> this.context.writeAndFlush(new DefaultHttp2ResetFrame(errorCode)));
		}

		private void writeHeaders(String status, Http2Headers headers) {
			Http2Headers response = new DefaultHttp2Headers().status(status);
			if (headers != null) {
				response.add(headers);
			}
			this.headersSent.set(true);
			this.context.writeAndFlush(new DefaultHttp2HeadersFrame(response, false));
		}

		private void execute(Runnable task) {
			if (this.context.executor().inEventLoop()) {
				task.run();
			}
			else {
				this.context.executor().execute(task);
			}
		}

		@Override
		public void onCancelled(Runnable callback) {
			this.cancelled.set(Objects.requireNonNull(callback, "callback"));
			if (this.cancellationFired.get()) {
				callback.run();
			}
		}

		private void cancel() {
			if (this.cancellationFired.compareAndSet(false, true)) {
				this.cancelled.get().run();
			}
		}

	}

}
