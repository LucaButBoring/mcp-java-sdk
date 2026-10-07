package io.modelcontextprotocol.transport.httpstdio.process;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server;
import io.modelcontextprotocol.transport.httpstdio.pipe.ProcessPipeDuplexByteChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;

/** Real-process fixture that speaks only prior-knowledge HTTP/2 on stdin/stdout. */
public final class EchoHttp2ChildMain {

	private EchoHttp2ChildMain() {
	}

	public static void main(String[] args) throws Exception {
		InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
		DefaultEventLoopGroup group = new DefaultEventLoopGroup(2, new DefaultThreadFactory("child-http2-loop"));
		AtomicInteger cancellations = new AtomicInteger();
		PipeHttp2Server[] holder = new PipeHttp2Server[1];
		PipeHttp2Server server = PipeHttp2Server.start(ProcessPipeDuplexByteChannel.forCurrentProcess(), group, null,
				(request, response) -> {
					switch (request.method() + " " + request.path()) {
						case "POST /echo" -> {
							ByteBuf retainedBody = request.body().retainedDuplicate();
							holder[0].channel().eventLoop().execute(() -> {
								try {
									response.headers("200", new DefaultHttp2Headers().add("x-echo-stream", "1"));
									while (retainedBody.isReadable()) {
										int count = Math.min(1024, retainedBody.readableBytes());
										response.data(retainedBody.readRetainedSlice(count),
												!retainedBody.isReadable());
									}
									if (retainedBody.capacity() == 0) {
										response.end();
									}
								}
								finally {
									retainedBody.release();
								}
							});
						}
						case "GET /ping" -> {
							response.headers("200", null);
							response.data(Unpooled.wrappedBuffer(new byte[] { 'p', 'o', 'n', 'g' }), true);
						}
						case "GET /slow" -> {
							response.headers("200", null);
							AtomicBoolean stopped = new AtomicBoolean();
							response.onCancelled(() -> {
								if (stopped.compareAndSet(false, true)) {
									System.err.println("cancelled=" + cancellations.incrementAndGet());
								}
							});
							emitSlow(holder[0], response, stopped);
						}
						default -> response.headers("404", null);
					}
				});
		holder[0] = server;
		server.goAwayReceived().thenRun(() -> System.err.println("goaway-received"));
		server.clientSettingsReceived().get(20, TimeUnit.SECONDS);
		System.err.println("ready");
		server.closed().join();
		group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
	}

	private static void emitSlow(PipeHttp2Server server,
			io.modelcontextprotocol.transport.httpstdio.http2.Http2ResponseWriter response, AtomicBoolean stopped) {
		server.channel().eventLoop().schedule(() -> {
			if (!stopped.get() && server.channel().isActive()) {
				response.data(Unpooled.buffer(1024).writeZero(1024), false);
				emitSlow(server, response, stopped);
			}
		}, 20, TimeUnit.MILLISECONDS);
	}

}
