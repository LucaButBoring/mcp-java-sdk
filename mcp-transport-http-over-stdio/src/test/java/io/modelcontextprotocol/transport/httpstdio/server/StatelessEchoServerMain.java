package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.http2.PipeHttp2Server;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.ProcessPipeDuplexByteChannel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Real-process fixture: a stateless MCP server reachable only through HTTP/2 on its own
 * stdin and stdout. Diagnostics go to stderr.
 *
 * <p>
 * With the single argument {@code unauthorized}, every request is answered with HTTP 401
 * and a {@code WWW-Authenticate} challenge instead of reaching the MCP handler, which
 * lets a parent exercise its authorization handler against a real child.
 */
public final class StatelessEchoServerMain {

	/** Challenge returned in {@code unauthorized} mode. */
	public static final String CHALLENGE = "Bearer resource_metadata=\"https://child.example/.well-known/oauth-protected-resource/mcp\"";

	private StatelessEchoServerMain() {
	}

	public static void main(String[] args) throws Exception {
		// Reserve stdout for HTTP/2 before any logging backend initializes a console
		// appender bound to System.out; the protocol uses the raw descriptor instead.
		DuplexByteChannel stdio = ProcessPipeDuplexByteChannel.forCurrentProcess();
		System.setOut(System.err);
		InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
		boolean unauthorized = args.length > 0 && "unauthorized".equals(args[0]);
		DefaultEventLoopGroup group = new DefaultEventLoopGroup(2, new DefaultThreadFactory("child-mcp-loop"));
		PipeHttp2Server server;
		if (unauthorized) {
			server = PipeHttp2Server.start(stdio, group, null, (request, response) -> {
				response.headers("401", new DefaultHttp2Headers().add("www-authenticate", CHALLENGE));
				response.end();
			});
		}
		else {
			HttpOverStdioServerTransport transport = HttpOverStdioServerTransport.start(stdio, group, null,
					McpJsonDefaults.getMapper());
			transport.setMcpHandler(new EchoHandler());
			server = transport.server();
		}
		server.goAwayReceived().thenRun(() -> System.err.println("goaway-received"));
		server.clientSettingsReceived().get(20, TimeUnit.SECONDS);
		System.err.println("ready");
		server.closed().join();
		group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
	}

	private static final class EchoHandler implements McpStatelessServerHandler {

		@Override
		public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
				McpSchema.JSONRPCRequest request) {
			if ("test/crash".equals(request.method())) {
				// Simulates the child dying mid-request: no response, no GOAWAY.
				System.err.println("crashing");
				Runtime.getRuntime().halt(3);
			}
			return Mono.just(McpSchema.JSONRPCResponse.result(request.id(),
					Map.of("method", request.method(), "pid", ProcessHandle.current().pid())));
		}

		@Override
		public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
			System.err.println("notification=" + notification.method());
			return Mono.empty();
		}

	}

}
