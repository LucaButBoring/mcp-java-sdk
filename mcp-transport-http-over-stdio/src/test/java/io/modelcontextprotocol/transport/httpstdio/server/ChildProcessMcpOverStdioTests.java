package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.http.McpHttpExchange;
import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 acceptance: both pipe ends wired onto the Phase 1 and Phase 2 seams across a
 * real {@code ProcessBuilder}-spawned child JVM.
 */
class ChildProcessMcpOverStdioTests {

	private static final String LOGICAL_URL = "https://child.example:8443";

	@Test
	void realChildAnswersMcpPostOverStdio() throws Exception {
		CopyOnWriteArrayList<String> stderr = new CopyOnWriteArrayList<>();
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport.launch(command(), Map.of(), stderr::add)
			.get(30, java.util.concurrent.TimeUnit.SECONDS);
		try {
			await(() -> stderr.contains("ready"));
			McpHttpRequest request = McpHttpRequest.builder()
				.method("POST")
				.uri(URI.create(LOGICAL_URL + "/mcp"))
				.headers(McpHttpHeaders.builder()
					.add("Accept", "application/json, text/event-stream")
					.add("Content-Type", "application/json")
					.build())
				.body("{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"method\":\"tools/list\"}")
				.build();
			var response = client.exchange().exchange(request, McpTransportContext.EMPTY).block(Duration.ofSeconds(10));
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("content-type"))
				.hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
			String body = read(response.body());
			assertThat(body).contains("\"id\":\"7\"").contains("\"method\":\"tools/list\"");
			assertThat(body).doesNotContain("\"pid\":" + ProcessHandle.current().pid() + "}");

			McpHttpRequest notification = McpHttpRequest.builder()
				.method("POST")
				.uri(URI.create(LOGICAL_URL + "/mcp"))
				.headers(McpHttpHeaders.builder().add("Accept", "application/json, text/event-stream").build())
				.body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
				.build();
			var accepted = client.exchange()
				.exchange(notification, McpTransportContext.EMPTY)
				.block(Duration.ofSeconds(10));
			assertThat(accepted.statusCode()).isEqualTo(202);
			assertThat(read(accepted.body())).isEmpty();
			await(() -> stderr.contains("notification=notifications/initialized"));
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(15));
		}
		// Child stderr is drained on its own thread, so the final line can land just
		// after
		// closeGracefully() returns.
		await(() -> stderr.contains("goaway-received"), () -> "child stderr: " + stderr);
		Process child = client.childProcess().orElseThrow();
		assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		assertThat(child.exitValue()).isZero();
		assertThat(stderr).noneMatch(line -> line.contains("PRI * HTTP/2.0"));
	}

	@Test
	void unauthorizedChildReachesExistingJdkAuthorizationHandlerUnchanged() throws Exception {
		CopyOnWriteArrayList<String> stderr = new CopyOnWriteArrayList<>();
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport
			.launch(command("unauthorized"), Map.of(), stderr::add)
			.get(30, java.util.concurrent.TimeUnit.SECONDS);
		AtomicReference<HttpRequestSnapshot> snapshot = new AtomicReference<>();
		AtomicReference<HttpResponse.ResponseInfo> info = new AtomicReference<>();
		try {
			await(() -> stderr.contains("ready"));
			// With httpExchange(...) set, the JDK request customizers are not consulted,
			// so a
			// host attaches credentials by decorating the functional exchange. The
			// decorated
			// request is what the pipe sends and what the authorization bridge reports.
			McpHttpExchange authorizing = (outbound, context) -> client.exchange()
				.exchange(withHeader(outbound, "Authorization", "Bearer stale-token"), context);
			HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(LOGICAL_URL)
				.httpExchange(authorizing)
				.authorizationErrorHandler((requestSnapshot, responseInfo, context) -> {
					snapshot.set(requestSnapshot);
					info.set(responseInfo);
					return Mono.just(false);
				})
				.build();
			transport.connect(message -> message).block(Duration.ofSeconds(10));
			McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/list",
					"auth-1", null);
			assertThatThrownBy(() -> transport.sendMessage(request).block(Duration.ofSeconds(10)))
				.isInstanceOf(McpHttpClientTransportAuthorizationException.class);
			transport.closeGracefully().block(Duration.ofSeconds(10));
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(15));
		}
		assertThat(snapshot.get()).isNotNull();
		assertThat(snapshot.get().method()).isEqualTo("POST");
		assertThat(snapshot.get().requestUri()).isEqualTo(URI.create(LOGICAL_URL + "/mcp"));
		assertThat(snapshot.get().headers().firstValue("Authorization")).contains("Bearer stale-token");
		assertThat(info.get().statusCode()).isEqualTo(401);
		assertThat(info.get().headers().firstValue("www-authenticate")).contains(StatelessEchoServerMain.CHALLENGE);
		assertThat(info.get().version()).isEqualTo(HttpClient.Version.HTTP_2);
		assertThat(stderr).noneMatch(line -> line.contains("PRI * HTTP/2.0"));
	}

	private static McpHttpRequest withHeader(McpHttpRequest request, String name, String value) {
		McpHttpRequest.Builder builder = McpHttpRequest.builder()
			.method(request.method())
			.uri(request.uri())
			.headers(McpHttpHeaders.builder().addAll(request.headers().map()).add(name, value).build());
		request.body().ifPresent(builder::body);
		return builder.build();
	}

	private static List<String> command(String... args) {
		String java = ProcessHandle.current().info().command().orElseThrow();
		List<String> command = new java.util.ArrayList<>(
				List.of(java, "-cp", System.getProperty("java.class.path"), StatelessEchoServerMain.class.getName()));
		command.addAll(List.of(args));
		return command;
	}

	private static String read(java.util.concurrent.Flow.Publisher<List<ByteBuffer>> body) {
		return JdkFlowAdapter.flowPublisherToFlux(body)
			.flatMapIterable(values -> values)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
			.collectList()
			.block(Duration.ofSeconds(10))
			.stream()
			.collect(Collectors.joining());
	}

	private static void await(CheckedBoolean condition) throws Exception {
		await(condition, () -> "condition not met");
	}

	private static void await(CheckedBoolean condition, java.util.function.Supplier<String> description)
			throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (!condition.get() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(condition.get()).as(description).isTrue();
	}

	@FunctionalInterface
	private interface CheckedBoolean {

		boolean get() throws Exception;

	}

}
