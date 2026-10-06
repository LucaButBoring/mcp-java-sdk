/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow.Publisher;

import io.modelcontextprotocol.util.Assert;

/**
 * Immutable transport-neutral HTTP response.
 *
 * <p>
 * The body is single-subscriber and may be consumed exactly once.
 */
public final class McpHttpResponse {

	private final int statusCode;

	private final McpHttpHeaders headers;

	private final Publisher<List<ByteBuffer>> body;

	private final McpHttpRequest sentRequest;

	private final String protocolVersion;

	/**
	 * Create a transport-neutral response.
	 * @param statusCode HTTP status code
	 * @param headers response headers
	 * @param body single-subscriber response body
	 * @param sentRequest request as actually sent, after customization
	 * @param protocolVersion HTTP protocol version, or {@code null} if unavailable
	 */
	public McpHttpResponse(int statusCode, McpHttpHeaders headers, Publisher<List<ByteBuffer>> body,
			McpHttpRequest sentRequest, String protocolVersion) {
		Assert.notNull(headers, "Response headers must not be null");
		Assert.notNull(body, "Response body must not be null");
		Assert.notNull(sentRequest, "Sent request must not be null");
		this.statusCode = statusCode;
		this.headers = headers;
		this.body = body;
		this.sentRequest = sentRequest;
		this.protocolVersion = protocolVersion;
	}

	/**
	 * @return the HTTP status code
	 */
	public int statusCode() {
		return this.statusCode;
	}

	/**
	 * @return immutable response headers
	 */
	public McpHttpHeaders headers() {
		return this.headers;
	}

	/**
	 * @return the single-subscriber response body
	 */
	public Publisher<List<ByteBuffer>> body() {
		return this.body;
	}

	/**
	 * @return the request as actually sent, after customization
	 */
	public McpHttpRequest sentRequest() {
		return this.sentRequest;
	}

	/**
	 * @return the HTTP protocol version, if available
	 */
	public Optional<String> protocolVersion() {
		return Optional.ofNullable(this.protocolVersion);
	}

}
