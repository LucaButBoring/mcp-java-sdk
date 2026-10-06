/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.net.URI;
import java.util.Optional;

import io.modelcontextprotocol.util.Assert;

/**
 * Immutable transport-neutral HTTP request.
 *
 * <p>
 * The URI is the logical request URI. An exchange implementation may use only its path
 * and query when its underlying transport does not address a network host. The optional
 * body is UTF-8 JSON text.
 */
public final class McpHttpRequest {

	private final String method;

	private final URI uri;

	private final McpHttpHeaders headers;

	private final String body;

	private McpHttpRequest(Builder builder) {
		Assert.hasText(builder.method, "HTTP method must not be empty");
		Assert.notNull(builder.uri, "HTTP URI must not be null");
		this.method = builder.method;
		this.uri = builder.uri;
		this.headers = builder.headers == null ? McpHttpHeaders.of(java.util.Map.of()) : builder.headers;
		this.body = builder.body;
	}

	/**
	 * @return the HTTP method
	 */
	public String method() {
		return this.method;
	}

	/**
	 * @return the logical request URI
	 */
	public URI uri() {
		return this.uri;
	}

	/**
	 * @return immutable request headers
	 */
	public McpHttpHeaders headers() {
		return this.headers;
	}

	/**
	 * @return the UTF-8 JSON request body, if present
	 */
	public Optional<String> body() {
		return Optional.ofNullable(this.body);
	}

	/**
	 * @return a new request builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** Builder for {@link McpHttpRequest}. */
	public static final class Builder {

		private String method = "GET";

		private URI uri;

		private McpHttpHeaders headers;

		private String body;

		/**
		 * @param method HTTP method @return this builder
		 */
		public Builder method(String method) {
			this.method = method;
			return this;
		}

		/**
		 * @param uri logical URI @return this builder
		 */
		public Builder uri(URI uri) {
			this.uri = uri;
			return this;
		}

		/**
		 * @param headers request headers @return this builder
		 */
		public Builder headers(McpHttpHeaders headers) {
			this.headers = headers;
			return this;
		}

		/**
		 * @param body UTF-8 JSON text, or {@code null} for no body @return this builder
		 */
		public Builder body(String body) {
			this.body = body;
			return this;
		}

		/**
		 * @return an immutable request
		 */
		public McpHttpRequest build() {
			return new McpHttpRequest(this);
		}

	}

}
