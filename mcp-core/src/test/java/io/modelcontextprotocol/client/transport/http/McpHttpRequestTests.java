/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpHttpRequestTests {

	@Test
	void builderRetainsMethodUriHeadersAndBody() {
		URI uri = URI.create("https://example.test/mcp");
		McpHttpHeaders headers = McpHttpHeaders.builder().add("X-Test", "value").build();

		McpHttpRequest request = McpHttpRequest.builder()
			.method("POST")
			.uri(uri)
			.headers(headers)
			.body("{\"jsonrpc\":\"2.0\"}")
			.build();

		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.uri()).isEqualTo(uri);
		assertThat(request.headers()).isSameAs(headers);
		assertThat(request.body()).contains("{\"jsonrpc\":\"2.0\"}");
	}

	@Test
	void defaultsToGetWithEmptyHeadersAndBody() {
		McpHttpRequest request = McpHttpRequest.builder().uri(URI.create("https://example.test/mcp")).build();

		assertThat(request.method()).isEqualTo("GET");
		assertThat(request.headers().map()).isEmpty();
		assertThat(request.body()).isEmpty();
	}

}
