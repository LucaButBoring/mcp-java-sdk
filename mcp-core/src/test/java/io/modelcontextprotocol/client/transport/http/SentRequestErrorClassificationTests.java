/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Error classification must be based on the request as actually sent, after request
 * customizers ran. Before the exchange extraction the transport inspected the customized
 * JDK request; these tests pin that behavior for any {@link McpHttpExchange}.
 */
class SentRequestErrorClassificationTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String CUSTOMIZER_SESSION_ID = "session-added-by-customizer";

	@ParameterizedTest
	@ValueSource(ints = { 400, 404 })
	void sessionIdAddedByCustomizerClassifiesAsSessionNotFound(int status) {
		HttpClientStreamableHttpTransport transport = transport(new SessionAddingExchange(status, true));
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		StepVerifier
			.create(transport.sendMessage(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", "1", null)))
			.expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
				.isInstanceOf(McpTransportSessionNotFoundException.class)
				.hasMessageContaining(CUSTOMIZER_SESSION_ID))
			.verify(TIMEOUT);
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404 })
	void withoutSessionIdOnSentRequestClassifiesAsPlainTransportError(int status) {
		HttpClientStreamableHttpTransport transport = transport(new SessionAddingExchange(status, false));
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		StepVerifier
			.create(transport.sendMessage(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", "1", null)))
			.expectErrorSatisfies(error -> org.assertj.core.api.Assertions.assertThat(error)
				.isInstanceOf(McpTransportException.class)
				.isNotInstanceOf(McpTransportSessionNotFoundException.class))
			.verify(TIMEOUT);
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404 })
	void getStreamClassifiesAgainstSentRequest(int status) {
		// The eager GET listener goes through reconnect(); its failures reach the
		// exception handler rather than a returned publisher.
		SessionAddingExchange exchange = new SessionAddingExchange(status, true);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("https://mcp.example")
			.endpoint("/mcp")
			.jsonMapper(new GsonMcpJsonMapper())
			.httpExchange(exchange)
			.openConnectionOnStartup(true)
			.build();
		CompletableFuture<Throwable> observed = new CompletableFuture<>();
		transport.setExceptionHandler(observed::complete);

		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		Throwable error = observed.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
		org.assertj.core.api.Assertions.assertThat(exchange.getInvocations).hasValue(1);
		org.assertj.core.api.Assertions.assertThat(error)
			.isInstanceOf(McpTransportSessionNotFoundException.class)
			.hasMessageContaining(CUSTOMIZER_SESSION_ID);
	}

	private static HttpClientStreamableHttpTransport transport(McpHttpExchange exchange) {
		return HttpClientStreamableHttpTransport.builder("https://mcp.example")
			.endpoint("/mcp")
			.jsonMapper(new GsonMcpJsonMapper())
			.httpExchange(exchange)
			.build();
	}

	/**
	 * Plays the role of an exchange whose customization step adds (or does not add) a
	 * session header the transport itself never set, then answers with the given status.
	 */
	private static final class SessionAddingExchange implements McpHttpExchange {

		private final int status;

		private final boolean addSessionId;

		final AtomicInteger getInvocations = new AtomicInteger();

		SessionAddingExchange(int status, boolean addSessionId) {
			this.status = status;
			this.addSessionId = addSessionId;
		}

		@Override
		public Mono<McpHttpResponse> exchange(McpHttpRequest request, McpTransportContext context) {
			if ("GET".equals(request.method())) {
				this.getInvocations.incrementAndGet();
			}
			McpHttpHeaders.Builder sentHeaders = McpHttpHeaders.builder().addAll(request.headers().map());
			if (this.addSessionId) {
				sentHeaders.add("Mcp-Session-Id", CUSTOMIZER_SESSION_ID);
			}
			McpHttpRequest sentRequest = McpHttpRequest.builder()
				.method(request.method())
				.uri(request.uri())
				.headers(sentHeaders.build())
				.body(request.body().orElse(null))
				.build();
			return Mono.just(new McpHttpResponse(this.status, McpHttpHeaders.builder().build(), body(""), sentRequest,
					"HTTP/1.1"));
		}

	}

	private static Flow.Publisher<List<ByteBuffer>> body(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {

			private boolean delivered;

			@Override
			public void request(long count) {
				if (!this.delivered && count > 0) {
					this.delivered = true;
					subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
					subscriber.onComplete();
				}
			}

			@Override
			public void cancel() {
				this.delivered = true;
			}

		});
	}

}
