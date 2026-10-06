/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.util.Assert;

/**
 * Immutable, case-insensitive, multi-valued HTTP headers.
 *
 * <p>
 * Header names retain the casing of their first occurrence, while lookups are
 * case-insensitive. Header values retain insertion order.
 */
public final class McpHttpHeaders {

	private static final McpHttpHeaders EMPTY = new McpHttpHeaders(Map.of());

	private final Map<String, List<String>> headers;

	private final Map<String, String> namesByLowerCase;

	private McpHttpHeaders(Map<String, List<String>> source) {
		Map<String, List<String>> values = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		source.forEach((name, headerValues) -> {
			Assert.hasText(name, "Header name must not be empty");
			Assert.notNull(headerValues, "Header values must not be null");
			String lowerCaseName = name.toLowerCase(Locale.ROOT);
			String retainedName = names.computeIfAbsent(lowerCaseName, ignored -> name);
			List<String> retainedValues = values.computeIfAbsent(retainedName, ignored -> new ArrayList<>());
			headerValues.forEach(value -> {
				Assert.notNull(value, "Header value must not be null");
				retainedValues.add(value);
			});
		});
		values.replaceAll((name, headerValues) -> List.copyOf(headerValues));
		this.headers = Collections.unmodifiableMap(values);
		this.namesByLowerCase = Collections.unmodifiableMap(names);
	}

	/**
	 * Return all values for a header name in insertion order.
	 * @param name header name
	 * @return immutable header values, or an empty list
	 */
	public List<String> allValues(String name) {
		Assert.notNull(name, "Header name must not be null");
		String retainedName = this.namesByLowerCase.get(name.toLowerCase(Locale.ROOT));
		return retainedName == null ? List.of() : this.headers.get(retainedName);
	}

	/**
	 * Return the first value for a header name.
	 * @param name header name
	 * @return the first value, if present
	 */
	public Optional<String> firstValue(String name) {
		List<String> values = allValues(name);
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	/**
	 * Return an immutable map of header names to immutable value lists.
	 * @return the headers
	 */
	public Map<String, List<String>> map() {
		return this.headers;
	}

	/**
	 * Create headers from a map of names to value lists.
	 * @param headers source headers
	 * @return immutable headers
	 */
	public static McpHttpHeaders of(Map<String, List<String>> headers) {
		Assert.notNull(headers, "Headers must not be null");
		return headers.isEmpty() ? EMPTY : new McpHttpHeaders(headers);
	}

	/**
	 * Create a new headers builder.
	 * @return a builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** Builder for {@link McpHttpHeaders}. */
	public static final class Builder {

		private final Map<String, List<String>> headers = new LinkedHashMap<>();

		/**
		 * Append a header value.
		 * @param name header name
		 * @param value header value
		 * @return this builder
		 */
		public Builder add(String name, String value) {
			Assert.hasText(name, "Header name must not be empty");
			Assert.notNull(value, "Header value must not be null");
			String retainedName = this.headers.keySet()
				.stream()
				.filter(candidate -> candidate.equalsIgnoreCase(name))
				.findFirst()
				.orElse(name);
			this.headers.computeIfAbsent(retainedName, ignored -> new ArrayList<>()).add(value);
			return this;
		}

		/**
		 * Append all values from a header map.
		 * @param headers source headers
		 * @return this builder
		 */
		public Builder addAll(Map<String, List<String>> headers) {
			Assert.notNull(headers, "Headers must not be null");
			headers.forEach((name, values) -> values.forEach(value -> add(name, value)));
			return this;
		}

		/**
		 * Build immutable headers.
		 * @return immutable headers
		 */
		public McpHttpHeaders build() {
			return McpHttpHeaders.of(this.headers);
		}

	}

}
