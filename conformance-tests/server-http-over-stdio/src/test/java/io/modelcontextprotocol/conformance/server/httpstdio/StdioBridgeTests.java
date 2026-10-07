/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.server.httpstdio;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test for the conformance bridge, runnable without the npm conformance runner: a
 * real child process behind the loopback front end answers the 2026-07-28 shape.
 */
@Timeout(120)
class StdioBridgeTests {

	private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":"
			+ "{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}";

	private static StdioBridge.Running bridge;

	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	@BeforeAll
	static void start() throws Exception {
		bridge = StdioBridge.start(0);
	}

	@AfterAll
	static void stop() {
		if (bridge != null) {
			bridge.close();
		}
	}

	@Test
	void hostHeaderBecomesTheForwardedAuthority() throws Exception {
		assertThat(raw("POST", "localhost:" + bridge.port(), LIST, "tools/list", "")).startsWith("HTTP/1.1 200");
		assertThat(raw("POST", "evil.example:" + bridge.port(), LIST, "tools/list", ""))
			.as("the child validates the runner's Host as :authority")
			.startsWith("HTTP/1.1 421");
	}

	@Test
	void headersNamedByConnectionAreNotForwarded() throws Exception {
		String response = raw("POST", "localhost:" + bridge.port(), LIST, "tools/list",
				"Connection: close, Mcp-Method\r\n");
		assertThat(response).startsWith("HTTP/1.1 400")
			.contains("\"code\":-32020")
			.contains("Mcp-Method header is required");
	}

	@Test
	void hopByHopFilteringIsCaseInsensitiveAndIncludesConnectionTokens() {
		Map<String, List<String>> headers = Map.of("CONNECTION", List.of("X-Internal, keep-alive"), "X-Internal",
				List.of("secret"), "Mcp-Method", List.of("tools/list"));
		assertThat(StdioBridge.droppedHeaders(headers))
			.contains("x-internal", "keep-alive", "connection", "host", "transfer-encoding", "content-length")
			.doesNotContain("mcp-method");
	}

	@Test
	void acceptedNotificationAnswers202WithNoBody() throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(endpoint())
			.header("Accept", "application/json, text/event-stream")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
			.build());
		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.body()).isEmpty();
	}

	@Test
	void childFailureAnswers502AndCloseStopsTheChild() throws Exception {
		try (StdioBridge.Running own = StdioBridge.start(0)) {
			Process child = own.child().childProcess().orElseThrow();
			child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
			HttpResponse<String> response = HTTP
				.send(HttpRequest.newBuilder(URI.create("http://localhost:" + own.port() + "/mcp"))
					.timeout(Duration.ofSeconds(30))
					.header("Accept", "application/json, text/event-stream")
					.header("MCP-Protocol-Version", "2026-07-28")
					.header("Mcp-Method", "tools/list")
					.POST(HttpRequest.BodyPublishers.ofString(LIST))
					.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(502);
			assertThat(response.body()).startsWith("bridge failure:");
		}
	}

	@Test
	void closeStopsTheFrontEndAndTheChild() throws Exception {
		StdioBridge.Running own = StdioBridge.start(0);
		Process child = own.child().childProcess().orElseThrow();
		int port = own.port();
		own.close();
		assertThat(child.waitFor(15, TimeUnit.SECONDS)).as("child exits after close").isTrue();
		assertThatThrownBy(() -> new Socket("127.0.0.1", port).close()).isInstanceOf(IOException.class);
	}

	/** Raw HTTP/1.1 so the test controls Host and Connection exactly. */
	private static String raw(String method, String host, String body, String mcpMethod, String extraHeaders)
			throws IOException {
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		String head = method + " /mcp HTTP/1.1\r\nHost: " + host + "\r\n"
				+ "Accept: application/json, text/event-stream\r\nContent-Type: application/json\r\n"
				+ "MCP-Protocol-Version: 2026-07-28\r\nMcp-Method: " + mcpMethod + "\r\n"
				+ (extraHeaders.toLowerCase().contains("connection:") ? "" : "Connection: close\r\n") + extraHeaders
				+ "Content-Length: " + payload.length + "\r\n\r\n";
		try (Socket socket = new Socket("127.0.0.1", bridge.port())) {
			socket.setSoTimeout(30_000);
			OutputStream out = socket.getOutputStream();
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.write(payload);
			out.flush();
			// Read the head, then exactly Content-Length body bytes: the connection may
			// stay
			// open when Connection carries more than "close".
			java.io.InputStream in = socket.getInputStream();
			java.io.ByteArrayOutputStream headBytes = new java.io.ByteArrayOutputStream();
			int matched = 0;
			while (matched < 4) {
				int b = in.read();
				if (b < 0) {
					break;
				}
				headBytes.write(b);
				matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
			}
			String responseHead = headBytes.toString(StandardCharsets.US_ASCII);
			java.util.regex.Matcher length = java.util.regex.Pattern.compile("(?im)^content-length:\\s*(\\d+)")
				.matcher(responseHead);
			byte[] responseBody = length.find() ? in.readNBytes(Integer.parseInt(length.group(1))) : new byte[0];
			return responseHead + new String(responseBody, StandardCharsets.UTF_8);
		}
	}

	@Test
	void forwardsA2026RequestToTheChild() throws Exception {
		HttpResponse<String> response = send(post(LIST, "tools/list").build());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("content-type"))
			.hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
		assertThat(response.body()).contains("\"test_headers\"").contains("\"x-mcp-header\":\"Region\"");
	}

	@Test
	void childValidationResultsReachTheRunnerUnchanged() throws Exception {
		HttpResponse<String> mismatch = send(post(LIST, "prompts/list").build());
		assertThat(mismatch.statusCode()).isEqualTo(400);
		assertThat(mismatch.body()).contains("\"code\":-32020");

		HttpResponse<String> hostileOrigin = send(
				post(LIST, "tools/list").header("Origin", "http://evil.example").build());
		assertThat(hostileOrigin.statusCode()).isEqualTo(403);

		HttpResponse<String> get = send(HttpRequest.newBuilder(endpoint()).GET().build());
		assertThat(get.statusCode()).isEqualTo(405);
	}

	private static HttpRequest.Builder post(String body, String method) {
		return HttpRequest.newBuilder(endpoint())
			.timeout(Duration.ofSeconds(30))
			.header("Accept", "application/json, text/event-stream")
			.header("Content-Type", "application/json")
			.header("MCP-Protocol-Version", "2026-07-28")
			.header("Mcp-Method", method)
			.POST(HttpRequest.BodyPublishers.ofString(body));
	}

	private static URI endpoint() {
		return URI.create("http://localhost:" + bridge.port() + "/mcp");
	}

	private static HttpResponse<String> send(HttpRequest request) throws Exception {
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

}
