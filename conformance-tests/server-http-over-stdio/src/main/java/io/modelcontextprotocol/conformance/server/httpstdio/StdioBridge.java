/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.server.httpstdio;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

/**
 * Test-harness bridge that lets the URL-based MCP conformance runner drive
 * {@link ConformanceStdioServer}, which is reachable only over HTTP/2 on a child
 * process's stdio.
 *
 * <p>
 * The bridge launches the child, then serves HTTP/1.1 on the loopback interface only and
 * forwards each request unchanged through {@link HttpOverStdioClientTransport}: method,
 * path, query, body, and headers other than hop-by-hop ones. The incoming {@code Host}
 * becomes the forwarded {@code :authority}, so the child's own Origin and Host validation
 * sees what the runner sent. Responses stream back as they arrive, which keeps SSE
 * incremental.
 *
 * <p>
 * The endpoint has no authentication. It binds to 127.0.0.1 and exists only to run the
 * conformance suite on a developer machine or in CI; it is not a deployment component.
 *
 * <p>
 * Usage: {@code StdioBridge [port]} (default 3002). The conformance runner then targets
 * {@code http://localhost:<port>/mcp}.
 */
public final class StdioBridge {

	/** Hop-by-hop and connection-specific headers that HTTP/2 must not carry. */
	private static final Set<String> HOP_BY_HOP = Set.of("host", "connection", "keep-alive", "proxy-connection",
			"transfer-encoding", "upgrade", "te", "trailer", "content-length",
			// Restricted by java.net.http and meaningless over the pipe.
			"expect");

	private StdioBridge() {
	}

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 3002;
		Running bridge = start(port);
		Runtime.getRuntime().addShutdownHook(new Thread(bridge::close));
		System.err.println("stdio-bridge listening on http://localhost:" + bridge.port() + "/mcp");
	}

	/**
	 * Launches the child and starts the loopback front end.
	 * @param port loopback port, or 0 for an ephemeral port
	 * @return the running bridge
	 * @throws Exception if the child does not connect or the port cannot be bound
	 */
	public static Running start(int port) throws Exception {
		String java = ProcessHandle.current().info().command().orElse("java");
		List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
				ConformanceStdioServer.class.getName());
		HttpOverStdioClientTransport child = HttpOverStdioClientTransport
			.launch(command, Map.of(), line -> System.err.println("[child] " + line))
			.get(60, TimeUnit.SECONDS);
		ExecutorService workers = Executors.newCachedThreadPool();
		// Bound to the IPv4 loopback explicitly, never a wildcard address.
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64);
		int bound = server.getAddress().getPort();
		server.setExecutor(workers);
		server.createContext("/", exchange -> forward(child, bound, exchange));
		server.start();
		return new Running(bound, server, child, workers);
	}

	/**
	 * A running bridge.
	 *
	 * @param port bound loopback port
	 * @param server the loopback front end
	 * @param child the child connection
	 * @param workers request threads
	 */
	public record Running(int port, HttpServer server, HttpOverStdioClientTransport child,
			ExecutorService workers) implements AutoCloseable {

		/** Stops the front end, then shuts the child down gracefully. */
		@Override
		public void close() {
			this.server.stop(0);
			this.child.closeGracefully().block(Duration.ofSeconds(10));
			this.workers.shutdownNow();
		}

	}

	private static void forward(HttpOverStdioClientTransport child, int port, HttpExchange exchange) {
		try {
			String host = exchange.getRequestHeaders().getFirst("Host");
			URI target = URI
				.create("http://" + (host == null ? "127.0.0.1:" + port : host) + exchange.getRequestURI().toString());
			Set<String> dropped = droppedHeaders(exchange.getRequestHeaders());
			byte[] body = exchange.getRequestBody().readAllBytes();
			HttpRequest.Builder request = HttpRequest.newBuilder(target)
				.method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofByteArray(body));
			exchange.getRequestHeaders().forEach((name, values) -> {
				if (!dropped.contains(name.toLowerCase(Locale.ROOT))) {
					values.forEach(value -> request.header(name, value));
				}
			});
			HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = child.httpClient()
				.send(request.build(), HttpResponse.BodyHandlers.ofPublisher());
			respond(exchange, response);
		}
		catch (Exception failure) {
			System.err.println("bridge failure: " + failure);
			// -1 means no status line has been sent yet, so a 502 can still be reported.
			if (exchange.getResponseCode() == -1) {
				try {
					byte[] message = ("bridge failure: " + failure.getMessage()).getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(502, message.length);
					exchange.getResponseBody().write(message);
				}
				catch (IOException ignored) {
					// The runner disconnected; nothing more can be reported.
				}
			}
		}
		finally {
			exchange.close();
		}
	}

	/**
	 * The fixed hop-by-hop set plus every header the message's own {@code Connection}
	 * field names, compared case-insensitively (RFC 9110 section 7.6.1).
	 */
	static Set<String> droppedHeaders(Map<String, List<String>> headers) {
		Set<String> dropped = new java.util.HashSet<>(HOP_BY_HOP);
		headers.forEach((name, values) -> {
			if ("connection".equalsIgnoreCase(name) && values != null) {
				for (String value : values) {
					for (String token : value.split(",")) {
						String trimmed = token.trim().toLowerCase(Locale.ROOT);
						if (!trimmed.isEmpty()) {
							dropped.add(trimmed);
						}
					}
				}
			}
		});
		return dropped;
	}

	private static void respond(HttpExchange exchange, HttpResponse<Flow.Publisher<List<ByteBuffer>>> response)
			throws IOException {
		Set<String> dropped = droppedHeaders(response.headers().map());
		response.headers().map().forEach((name, values) -> {
			if (!dropped.contains(name.toLowerCase(Locale.ROOT))) {
				exchange.getResponseHeaders().put(name, values);
			}
		});
		Flux<ByteBuffer> chunks = JdkFlowAdapter.flowPublisherToFlux(response.body()).flatMapIterable(list -> list);
		boolean streaming = response.headers()
			.firstValue("content-type")
			.map(type -> type.toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
			.orElse(false);
		if (!streaming) {
			byte[] all = chunks.reduce(new java.io.ByteArrayOutputStream(), (out, buffer) -> {
				byte[] bytes = new byte[buffer.remaining()];
				buffer.get(bytes);
				out.writeBytes(bytes);
				return out;
			}).map(java.io.ByteArrayOutputStream::toByteArray).block(Duration.ofSeconds(60));
			exchange.sendResponseHeaders(response.statusCode(), all.length == 0 ? -1 : all.length);
			if (all.length > 0) {
				exchange.getResponseBody().write(all);
			}
			return;
		}
		exchange.sendResponseHeaders(response.statusCode(), 0);
		OutputStream out = exchange.getResponseBody();
		// Each HTTP/2 DATA chunk is written and flushed as it arrives, keeping SSE live.
		// Closing the stream cancels the child's HTTP/2 stream if the runner disconnects.
		try (java.util.stream.Stream<ByteBuffer> live = chunks.toStream()) {
			java.util.Iterator<ByteBuffer> iterator = live.iterator();
			while (iterator.hasNext()) {
				ByteBuffer buffer = iterator.next();
				byte[] bytes = new byte[buffer.remaining()];
				buffer.get(bytes);
				out.write(bytes);
				out.flush();
			}
		}
	}

}
