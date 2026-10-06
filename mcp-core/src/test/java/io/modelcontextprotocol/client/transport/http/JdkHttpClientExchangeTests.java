/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.LoopbackMcpHttpServer;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class JdkHttpClientExchangeTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void exchangesJsonAndInvokesCustomizerOnceWithRequestDetails() throws Exception {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			byte[] responseBody = "{\"jsonrpc\":\"2.0\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
			server.createContext("/json", exchange -> {
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, responseBody.length);
				try (OutputStream output = exchange.getResponseBody()) {
					output.write(responseBody);
				}
			});
			AtomicInteger invocations = new AtomicInteger();
			AtomicReference<String> method = new AtomicReference<>();
			AtomicReference<URI> uri = new AtomicReference<>();
			McpAsyncHttpClientRequestCustomizer customizer = (builder, requestMethod, endpoint, body, context) -> {
				invocations.incrementAndGet();
				method.set(requestMethod);
				uri.set(endpoint);
				builder.header("X-Customized", "true");
				return Mono.just(builder);
			};
			JdkHttpClientExchange exchange = exchange(customizer);
			URI endpoint = server.baseUri().resolve("/json");
			McpHttpRequest request = McpHttpRequest.builder()
				.method("POST")
				.uri(endpoint)
				.headers(McpHttpHeaders.builder().add("Content-Type", "application/json").build())
				.body("{}")
				.build();

			McpHttpResponse response = exchange.exchange(request, McpTransportContext.EMPTY).block(TIMEOUT);

			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("content-type"))
				.hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
			assertThat(readBody(response)).isEqualTo(new String(responseBody, StandardCharsets.UTF_8));
			assertThat(invocations).hasValue(1);
			assertThat(method).hasValue("POST");
			assertThat(uri).hasValue(endpoint);
			assertThat(response.sentRequest().headers().firstValue("X-Customized")).contains("true");
		}
	}

	@Test
	void streamsSseResponseBody() throws Exception {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			McpHttpRequest request = McpHttpRequest.builder()
				.method("GET")
				.uri(server.baseUri().resolve("/mcp"))
				.headers(McpHttpHeaders.builder().add("Accept", "text/event-stream").build())
				.build();

			McpHttpResponse response = exchange(McpAsyncHttpClientRequestCustomizer.NOOP)
				.exchange(request, McpTransportContext.EMPTY)
				.block(TIMEOUT);

			assertThat(response).isNotNull();
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(value -> assertThat(value).startsWith("text/event-stream"));
			assertThat(readBody(response)).contains("event: message").contains("data:");
		}
	}

	@Test
	void cancellingExchangeBeforeHeadersReleasesUnderlyingRequest() throws Exception {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			CountDownLatch requestReceived = new CountDownLatch(1);
			CountDownLatch releaseResponse = new CountDownLatch(1);
			CountDownLatch connectionClosed = new CountDownLatch(1);
			server.createContext("/cancel", httpExchange -> {
				requestReceived.countDown();
				try {
					releaseResponse.await();
					httpExchange.sendResponseHeaders(200, 1);
					httpExchange.getResponseBody().write('x');
					httpExchange.getResponseBody().flush();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				catch (IOException ex) {
					connectionClosed.countDown();
				}
				finally {
					httpExchange.close();
				}
			});
			McpHttpRequest request = McpHttpRequest.builder().uri(server.baseUri().resolve("/cancel")).build();
			Disposable subscription = exchange(McpAsyncHttpClientRequestCustomizer.NOOP)
				.exchange(request, McpTransportContext.EMPTY)
				.subscribe();
			assertThat(requestReceived.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

			subscription.dispose();
			releaseResponse.countDown();

			assertThat(subscription.isDisposed()).isTrue();
			assertThat(connectionClosed.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
		}
	}

	private static JdkHttpClientExchange exchange(McpAsyncHttpClientRequestCustomizer customizer) {
		return new JdkHttpClientExchange(HttpClient.newHttpClient(), HttpRequest.newBuilder(), customizer);
	}

	private static String readBody(McpHttpResponse response) {
		return JdkFlowAdapter.flowPublisherToFlux(response.body())
			.flatMapIterable(List::copyOf)
			.map(ByteBuffer::slice)
			.reduce(ByteBuffer.allocate(0), JdkHttpClientExchangeTests::append)
			.map(StandardCharsets.UTF_8::decode)
			.map(Object::toString)
			.block(TIMEOUT);
	}

	private static ByteBuffer append(ByteBuffer left, ByteBuffer right) {
		ByteBuffer combined = ByteBuffer.allocate(left.remaining() + right.remaining());
		combined.put(left).put(right).flip();
		return combined;
	}

}
