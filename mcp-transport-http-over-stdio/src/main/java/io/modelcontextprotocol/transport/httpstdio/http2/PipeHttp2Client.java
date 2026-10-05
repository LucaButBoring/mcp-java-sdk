package io.modelcontextprotocol.transport.httpstdio.http2;

import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.NettyPipeChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.PipeChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStreamException;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;

/** Prior-knowledge HTTP/2 client carried by a {@link NettyPipeChannel}. */
public final class PipeHttp2Client {

	public static final long DEFAULT_MAX_RESPONSE_BODY_BYTES = 16L * 1024 * 1024;

	private final NettyPipeChannel channel;

	private final long maxResponseBodyBytes;

	private final CompletableFuture<Void> settingsReceived = new CompletableFuture<>();

	private final CompletableFuture<Void> goAwayReceived = new CompletableFuture<>();

	private final AtomicBoolean goAwaySent = new AtomicBoolean();

	private final AtomicBoolean terminal = new AtomicBoolean();

	private PipeHttp2Client(NettyPipeChannel channel, long maxResponseBodyBytes) {
		this.channel = channel;
		this.maxResponseBodyBytes = positive(maxResponseBodyBytes, "maxResponseBodyBytes");
	}

	public static CompletableFuture<PipeHttp2Client> connect(DuplexByteChannel duplex, EventLoopGroup group,
			PipeChannelConfig config) {
		return connect(duplex, group, config, DEFAULT_MAX_RESPONSE_BODY_BYTES);
	}

	public static CompletableFuture<PipeHttp2Client> connect(DuplexByteChannel duplex, EventLoopGroup group,
			PipeChannelConfig config, long maxResponseBodyBytes) {
		Objects.requireNonNull(duplex, "duplex");
		Objects.requireNonNull(group, "group");
		NettyPipeChannel channel = new NettyPipeChannel(duplex, config);
		PipeHttp2Client client = new PipeHttp2Client(channel, maxResponseBodyBytes);
		channel.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
		channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext context, Object message) {
				if (message instanceof Http2SettingsFrame) {
					client.settingsReceived.complete(null);
					ReferenceCountUtil.release(message);
					return;
				}
				if (message instanceof Http2GoAwayFrame) {
					client.goAwayReceived.complete(null);
					ReferenceCountUtil.release(message);
					return;
				}
				if (message instanceof Http2SettingsAckFrame) {
					ReferenceCountUtil.release(message);
					return;
				}
				context.fireChannelRead(message);
			}

			@Override
			public void channelInactive(ChannelHandlerContext context) {
				client.failConnection(new ClosedChannelException());
				context.fireChannelInactive();
			}

			@Override
			public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
				client.failConnection(cause);
				context.close();
			}
		});
		channel.pipeline().addLast(new Http2MultiplexHandler(new ChannelInboundHandlerAdapter()));
		group.register(channel).addListener(registered -> {
			if (!registered.isSuccess()) {
				client.failConnection(registered.cause());
			}
		});
		return client.settingsReceived.thenApply(ignored -> client);
	}

	public CompletableFuture<Http2Response> request(String method, String path, Http2Headers extraHeaders,
			ByteBuf body) {
		CompletableFuture<Http2Response> result = new CompletableFuture<>();
		CompositeByteBuf responseBody = this.channel.alloc().compositeBuffer();
		AtomicBoolean bodyReleased = new AtomicBoolean();
		final Http2Headers[] responseHeaders = new Http2Headers[1];
		StreamingResponse streaming = requestStreaming(method, path, extraHeaders, body,
				new StreamingResponse.Listener() {
					@Override
					public void onHeaders(Http2Headers headers, boolean endStream) {
						responseHeaders[0] = headers;
					}

					@Override
					public void onData(ByteBuf data, boolean endStream) {
						responseBody.addComponent(true, data);
					}

					@Override
					public void onComplete() {
						String status = responseHeaders[0] == null || responseHeaders[0].status() == null ? null
								: responseHeaders[0].status().toString();
						if (!result.complete(new Http2Response(status, responseHeaders[0], responseBody, true))) {
							releaseOnce(responseBody, bodyReleased);
						}
					}
				});
		streaming.completion().whenComplete((ignored, failure) -> {
			if (failure != null) {
				releaseOnce(responseBody, bodyReleased);
				result.completeExceptionally(unwrap(failure));
			}
		});
		return result;
	}

	/**
	 * Opens a response stream with its listener installed before any frame can arrive.
	 * @param listener listener that owns every delivered data buffer
	 */
	public StreamingResponse requestStreaming(String method, String path, Http2Headers extraHeaders, ByteBuf body,
			StreamingResponse.Listener listener) {
		StreamingResponse response = new StreamingResponse(listener);
		openStream(response, method, path, extraHeaders, body).exceptionally(failure -> {
			response.failed(unwrap(failure));
			return null;
		});
		return response;
	}

	private CompletableFuture<Void> openStream(StreamingResponse response, String method, String path,
			Http2Headers extraHeaders, ByteBuf body) {
		CompletableFuture<Void> opened = new CompletableFuture<>();
		if (this.goAwaySent.get()) {
			opened.completeExceptionally(new IllegalStateException("Cannot open a stream after GOAWAY"));
			return opened;
		}
		new Http2StreamChannelBootstrap(this.channel).handler(new ChannelInboundHandlerAdapter() {

			private long responseBytes;

			private boolean complete;

			private boolean peerBodyLimitExceeded;

			@Override
			public void channelRead(ChannelHandlerContext context, Object message) {
				try {
					if (message instanceof Http2HeadersFrame headers) {
						this.peerBodyLimitExceeded = headers.headers().contains("x-http2-body-too-large");
						response.headers(headers.headers(), headers.isEndStream());
						this.complete |= headers.isEndStream();
					}
					else if (message instanceof Http2DataFrame data) {
						int readable = data.content().readableBytes();
						if (this.responseBytes > maxResponseBodyBytes - readable) {
							this.complete = true;
							Http2BodyTooLargeException failure = new Http2BodyTooLargeException(
									"HTTP/2 response body exceeds " + maxResponseBodyBytes + " bytes",
									maxResponseBodyBytes);
							response.failed(failure);
							context.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.ENHANCE_YOUR_CALM));
						}
						else {
							this.responseBytes += readable;
							response.data(data.content().retain(), data.isEndStream());
							this.complete |= data.isEndStream();
						}
					}
					else if (message instanceof Http2ResetFrame reset) {
						this.complete = true;
						response.reset(reset.errorCode());
					}
				}
				catch (Throwable failure) {
					this.complete = true;
					response.failed(failure);
					context.close();
				}
				finally {
					ReferenceCountUtil.release(message);
				}
			}

			@Override
			public void channelInactive(ChannelHandlerContext context) {
				if (!this.complete) {
					if (this.peerBodyLimitExceeded) {
						response.failed(new Http2BodyTooLargeException(
								"Peer rejected an HTTP/2 body that exceeded its limit", -1));
					}
					else {
						response.failed(new Http2TransportException(
								"HTTP/2 response stream closed before end-of-stream", new ClosedChannelException()));
					}
				}
				context.fireChannelInactive();
			}

			@Override
			public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
				this.complete = true;
				if (cause instanceof Http2FrameStreamException streamFailure
						&& streamFailure.error() == Http2Error.ENHANCE_YOUR_CALM) {
					response.failed(
							new Http2BodyTooLargeException("Peer rejected an HTTP/2 body that exceeded its limit", -1));
				}
				else {
					response.failed(new Http2TransportException("HTTP/2 response stream failed", cause));
				}
				context.close();
			}
		}).open().addListener(future -> {
			if (!future.isSuccess()) {
				opened.completeExceptionally(
						new Http2TransportException("Failed to open HTTP/2 response stream", future.cause()));
				return;
			}
			Http2StreamChannel stream = (Http2StreamChannel) future.getNow();
			Http2Headers headers = new DefaultHttp2Headers().method(method).path(path).scheme("http").authority("pipe");
			if (extraHeaders != null) {
				headers.add(extraHeaders);
			}
			boolean empty = body == null || !body.isReadable();
			stream.write(new DefaultHttp2HeadersFrame(headers, empty));
			if (!empty) {
				stream.write(new DefaultHttp2DataFrame(body.retainedDuplicate(), true));
			}
			stream.flush();
			response.opened(() -> {
				CompletableFuture<Void> reset = new CompletableFuture<>();
				stream.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL))
					.addListener(resetWrite -> complete(resetWrite.isSuccess(), resetWrite.cause(), reset));
				return reset;
			});
			opened.complete(null);
		});
		return opened;
	}

	public CompletableFuture<Void> goAway() {
		this.goAwaySent.set(true);
		return write(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));
	}

	public CompletableFuture<Void> close() {
		CompletableFuture<Void> result = new CompletableFuture<>();
		this.channel.close().addListener(future -> complete(future.isSuccess(), future.cause(), result));
		return result;
	}

	public NettyPipeChannel channel() {
		return this.channel;
	}

	public CompletableFuture<Void> settingsReceived() {
		return this.settingsReceived;
	}

	public CompletableFuture<Void> goAwayReceived() {
		return this.goAwayReceived;
	}

	private void failConnection(Throwable cause) {
		if (this.terminal.compareAndSet(false, true)) {
			Http2TransportException failure = new Http2TransportException(
					"HTTP/2 connection closed before the initial SETTINGS exchange completed", cause);
			this.settingsReceived.completeExceptionally(failure);
		}
	}

	private CompletableFuture<Void> write(Object frame) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		this.channel.writeAndFlush(frame).addListener(future -> complete(future.isSuccess(), future.cause(), result));
		return result;
	}

	private static void complete(boolean success, Throwable failure, CompletableFuture<Void> target) {
		if (success) {
			target.complete(null);
		}
		else {
			target.completeExceptionally(failure);
		}
	}

	private static void releaseOnce(ByteBuf body, AtomicBoolean released) {
		if (released.compareAndSet(false, true)) {
			body.release();
		}
	}

	private static Throwable unwrap(Throwable failure) {
		return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
				? failure.getCause() : failure;
	}

	private static long positive(long value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}

}
