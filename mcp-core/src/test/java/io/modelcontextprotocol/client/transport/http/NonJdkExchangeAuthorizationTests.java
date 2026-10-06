/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class NonJdkExchangeAuthorizationTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String AUTHORIZATION = "Bearer non-jdk-token";

	private static final String CHALLENGE = "Bearer resource_metadata=\"https://auth.example/metadata\"";

	@Test
	void retriesUsingNeutralAuthorizationMetadata() {
		URI endpoint = URI.create("https://mcp.example/mcp");
		ScriptedExchange exchange = new ScriptedExchange();
		AtomicInteger handlerInvocations = new AtomicInteger();
		AtomicReference<HttpRequestSnapshot> requestSnapshot = new AtomicReference<>();
		AtomicReference<HttpResponse.ResponseInfo> responseInfo = new AtomicReference<>();
		McpHttpClientTransportAuthorizationErrorHandler handler = (request, response, context) -> {
			handlerInvocations.incrementAndGet();
			requestSnapshot.set(request);
			responseInfo.set(response);
			return Mono.just(true);
		};
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("https://mcp.example")
			.endpoint("/mcp")
			.jsonMapper(new GsonMcpJsonMapper())
			.httpExchange(exchange)
			.authorizationErrorHandler(handler)
			.build();
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		StepVerifier
			.create(transport.sendMessage(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", "1", null)))
			.verifyComplete();

		assertThat(exchange.postInvocations).hasValue(2);
		assertThat(handlerInvocations).hasValue(1);
		assertThat(requestSnapshot.get().method()).isEqualTo("POST");
		assertThat(requestSnapshot.get().requestUri()).isEqualTo(endpoint);
		assertThat(requestSnapshot.get().headers().firstValue("Authorization")).contains(AUTHORIZATION);
		assertThat(responseInfo.get().statusCode()).isEqualTo(401);
		assertThat(responseInfo.get().headers().firstValue("WWW-Authenticate")).contains(CHALLENGE);
		assertThat(responseInfo.get().version().name()).isEqualTo("HTTP_2");
	}

	private static final class ScriptedExchange implements McpHttpExchange {

		private final AtomicInteger invocations = new AtomicInteger();

		private final AtomicInteger postInvocations = new AtomicInteger();

		@Override
		public Mono<McpHttpResponse> exchange(McpHttpRequest request, McpTransportContext context) {
			if ("POST".equals(request.method())) {
				this.postInvocations.incrementAndGet();
			}
			McpHttpRequest sentRequest = customize(request);
			if (this.invocations.getAndIncrement() == 0) {
				return Mono
					.just(new McpHttpResponse(401, McpHttpHeaders.builder().add("WWW-Authenticate", CHALLENGE).build(),
							body(""), sentRequest, "HTTP/2"));
			}
			return Mono
				.just(new McpHttpResponse(200, McpHttpHeaders.builder().add("Content-Type", "application/json").build(),
						body("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}"), sentRequest, "HTTP/2"));
		}

		private McpHttpRequest customize(McpHttpRequest request) {
			return McpHttpRequest.builder()
				.method(request.method())
				.uri(request.uri())
				.headers(McpHttpHeaders.builder()
					.addAll(request.headers().map())
					.add("Authorization", AUTHORIZATION)
					.build())
				.body(request.body().orElse(null))
				.build();
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
