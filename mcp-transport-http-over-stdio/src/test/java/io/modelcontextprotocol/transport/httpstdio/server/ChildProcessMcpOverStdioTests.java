package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
import io.modelcontextprotocol.transport.httpstdio.client.PipeRequests;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 acceptance: both pipe ends wired onto the SDK's existing client and stateless
 * server across a real {@code ProcessBuilder}-spawned child JVM.
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
			HttpRequest request = PipeRequests.request("POST", LOGICAL_URL + "/mcp",
					Map.of("Accept", "application/json, text/event-stream", "Content-Type", "application/json",
							"MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list"),
					"{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"method\":\"tools/list\",\"params\":{\"_meta\":"
							+ "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}");
			var response = PipeRequests.sendNow(client.httpClient(), request);
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("content-type"))
				.hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
			String body = PipeRequests.read(response);
			assertThat(body).contains("\"id\":\"7\"").contains("\"method\":\"tools/list\"");
			assertThat(body).doesNotContain("\"pid\":" + ProcessHandle.current().pid() + "}");

			HttpRequest legacyShape = PipeRequests.request("POST", LOGICAL_URL + "/mcp",
					Map.of("Accept", "application/json, text/event-stream"),
					"{\"jsonrpc\":\"2.0\",\"id\":\"8\",\"method\":\"tools/list\"}");
			var rejected = PipeRequests.sendNow(client.httpClient(), legacyShape);
			assertThat(rejected.statusCode()).as("2026 binding is on by default in the child").isEqualTo(400);
			assertThat(PipeRequests.read(rejected)).contains("\"code\":-32020");

			HttpRequest notification = PipeRequests.request("POST", LOGICAL_URL + "/mcp",
					Map.of("Accept", "application/json, text/event-stream"),
					"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
			var accepted = PipeRequests.sendNow(client.httpClient(), notification);
			assertThat(accepted.statusCode()).isEqualTo(202);
			assertThat(PipeRequests.read(accepted)).isEmpty();
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
			// Credentials are attached by the transport's existing request customizer,
			// and
			// the 401 reaches its existing authorization error handler.
			HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(LOGICAL_URL)
				.clientBuilder(client.httpClient().asClientBuilder())
				.httpRequestCustomizer(
						(builder, method, uri, body, context) -> builder.header("Authorization", "Bearer stale-token"))
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

	@Test
	void childExitMidRequestFailsTheExchangeInsteadOfHanging() throws Exception {
		CopyOnWriteArrayList<String> stderr = new CopyOnWriteArrayList<>();
		HttpOverStdioClientTransport client = HttpOverStdioClientTransport.launch(command(), Map.of(), stderr::add)
			.get(30, java.util.concurrent.TimeUnit.SECONDS);
		try {
			await(() -> stderr.contains("ready"));
			HttpRequest crash = PipeRequests.request("POST", LOGICAL_URL + "/mcp",
					Map.of("Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2026-07-28",
							"Mcp-Method", "test/crash"),
					"{\"jsonrpc\":\"2.0\",\"id\":\"9\",\"method\":\"test/crash\",\"params\":{\"_meta\":"
							+ "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}");
			long started = System.nanoTime();
			assertThatThrownBy(() -> PipeRequests.send(client.httpClient(), crash).block(Duration.ofSeconds(20)))
				.as("the peer-close failure, not the 20 s blocking timeout")
				.isInstanceOf(io.modelcontextprotocol.transport.httpstdio.http2.Http2TransportException.class)
				.hasMessageContaining("closed before end-of-stream");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
			Process child = client.childProcess().orElseThrow();
			assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			assertThat(child.exitValue()).isEqualTo(3);
			assertThat(stderr).contains("crashing");
		}
		finally {
			client.closeGracefully().block(Duration.ofSeconds(15));
		}
	}

	private static List<String> command(String... args) {
		String java = ProcessHandle.current().info().command().orElseThrow();
		List<String> command = new java.util.ArrayList<>(
				List.of(java, "-cp", System.getProperty("java.class.path"), StatelessEchoServerMain.class.getName()));
		command.addAll(List.of(args));
		return command;
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
