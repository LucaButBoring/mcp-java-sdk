/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.client.transport.LoopbackMcpHttpServer;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class JdkAuthorizationMetadataTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String AUTHORIZATION = "Bearer jdk-token";

	private static final String CHALLENGE = "Bearer resource_metadata=\"https://auth.example/metadata\"";

	@Test
	void preservesCustomizedRequestAndResponseMetadata() throws IOException {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			AtomicInteger requests = new AtomicInteger();
			AtomicInteger postRequests = new AtomicInteger();
			AtomicReference<String> observedMethod = new AtomicReference<>();
			AtomicReference<URI> observedUri = new AtomicReference<>();
			AtomicReference<String> observedAuthorization = new AtomicReference<>();
			server.createContext("/authorized", exchange -> {
				try (exchange) {
					if ("POST".equals(exchange.getRequestMethod())) {
						postRequests.incrementAndGet();
					}
					if (requests.getAndIncrement() == 0) {
						observedMethod.set(exchange.getRequestMethod());
						observedUri.set(server.baseUri().resolve(exchange.getRequestURI()));
						observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
						exchange.getResponseHeaders().add("WWW-Authenticate", CHALLENGE);
						exchange.sendResponseHeaders(401, -1);
					}
					else {
						exchange.sendResponseHeaders(202, -1);
					}
				}
			});
			AtomicInteger handlerInvocations = new AtomicInteger();
			AtomicReference<HttpRequestSnapshot> requestSnapshot = new AtomicReference<>();
			AtomicReference<HttpResponse.ResponseInfo> responseInfo = new AtomicReference<>();
			McpHttpClientTransportAuthorizationErrorHandler handler = (request, response, context) -> {
				handlerInvocations.incrementAndGet();
				requestSnapshot.set(request);
				responseInfo.set(response);
				return Mono.just(true);
			};
			HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder(server.baseUri().toString())
				.endpoint("/authorized")
				.jsonMapper(new GsonMcpJsonMapper())
				.httpRequestCustomizer(
						(builder, method, uri, body, context) -> builder.header("Authorization", AUTHORIZATION))
				.authorizationErrorHandler(handler)
				.build();

			StepVerifier
				.create(transport
					.sendMessage(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", "1", null)))
				.verifyComplete();

			assertThat(postRequests).hasValue(2);
			assertThat(handlerInvocations).hasValue(1);
			assertThat(requestSnapshot.get().method()).isEqualTo(observedMethod.get());
			assertThat(requestSnapshot.get().requestUri()).isEqualTo(observedUri.get());
			assertThat(requestSnapshot.get().headers().firstValue("Authorization"))
				.contains(observedAuthorization.get())
				.contains(AUTHORIZATION);
			assertThat(responseInfo.get().statusCode()).isEqualTo(401);
			assertThat(responseInfo.get().headers().firstValue("WWW-Authenticate")).contains(CHALLENGE);
			assertThat(responseInfo.get().version().name()).isEqualTo("HTTP_1_1");
		}
	}

}
