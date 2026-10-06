/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * {@link McpHttpExchange} backed by the JDK {@link HttpClient}.
 *
 * <p>
 * The existing JDK request customizer is applied after the neutral request has been
 * adapted and immediately before it is sent.
 */
public final class JdkHttpClientExchange implements McpHttpExchange {

	private final HttpClient httpClient;

	private final HttpRequest.Builder requestBuilder;

	private final McpAsyncHttpClientRequestCustomizer requestCustomizer;

	/**
	 * Create a JDK exchange adapter.
	 * @param httpClient JDK HTTP client
	 * @param requestBuilder template request builder
	 * @param requestCustomizer existing JDK request customizer
	 */
	public JdkHttpClientExchange(HttpClient httpClient, HttpRequest.Builder requestBuilder,
			McpAsyncHttpClientRequestCustomizer requestCustomizer) {
		Assert.notNull(httpClient, "HttpClient must not be null");
		Assert.notNull(requestBuilder, "Request builder must not be null");
		Assert.notNull(requestCustomizer, "Request customizer must not be null");
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
		this.requestCustomizer = requestCustomizer;
	}

	@Override
	public Mono<McpHttpResponse> exchange(McpHttpRequest request, McpTransportContext context) {
		return Mono.defer(() -> {
			HttpRequest.Builder builder = this.requestBuilder.copy().uri(request.uri());
			request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
			String body = request.body().orElse(null);
			builder.method(request.method(),
					body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
			return Mono.from(this.requestCustomizer.customize(builder, request.method(), request.uri(), body, context));
		})
			.map(HttpRequest.Builder::build)
			.flatMap(jdkRequest -> JdkHttpClientSend.sendAsync(this.httpClient, jdkRequest).map(jdkResponse -> {
				McpHttpRequest sentRequest = McpHttpRequest.builder()
					.method(jdkRequest.method())
					.uri(jdkRequest.uri())
					.headers(McpHttpHeaders.of(jdkRequest.headers().map()))
					.body(request.body().orElse(null))
					.build();
				String protocolVersion = jdkResponse.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
				return new McpHttpResponse(jdkResponse.statusCode(), McpHttpHeaders.of(jdkResponse.headers().map()),
						jdkResponse.body(), sentRequest, protocolVersion);
			}));
	}

}
