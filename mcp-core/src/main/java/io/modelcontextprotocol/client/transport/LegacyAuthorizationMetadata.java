/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;

import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.client.transport.http.McpHttpResponse;

/**
 * Bridges transport-neutral HTTP metadata to the pre-existing JDK-typed authorization
 * handler contract, allowing any HTTP exchange implementation to satisfy that contract.
 */
final class LegacyAuthorizationMetadata {

	private LegacyAuthorizationMetadata() {
	}

	static HttpRequestSnapshot requestSnapshot(McpHttpResponse response) {
		McpHttpRequest sentRequest = response.sentRequest();
		return new HttpRequestSnapshot(sentRequest.uri(), sentRequest.method(),
				HttpHeaders.of(sentRequest.headers().map(), (name, value) -> true));
	}

	static HttpResponse.ResponseInfo responseInfo(McpHttpResponse response) {
		HttpClient.Version version = response.protocolVersion()
			.filter("HTTP/2"::equals)
			.map(ignored -> HttpClient.Version.HTTP_2)
			.orElse(HttpClient.Version.HTTP_1_1);
		return new LegacyResponseInfo(response.statusCode(),
				HttpHeaders.of(response.headers().map(), (name, value) -> true), version);
	}

	private record LegacyResponseInfo(int statusCode, HttpHeaders headers,
			HttpClient.Version version) implements HttpResponse.ResponseInfo {
	}

}
