/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.io.IOException;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;

public final class TestMcpJsonMapper implements McpJsonMapper {

	private final McpJsonMapper delegate = new GsonMcpJsonMapper();

	@Override
	public <T> T readValue(String content, Class<T> type) throws IOException {
		return this.delegate.readValue(content, type);
	}

	@Override
	public <T> T readValue(byte[] content, Class<T> type) throws IOException {
		return this.delegate.readValue(content, type);
	}

	@Override
	public <T> T readValue(String content, TypeRef<T> type) throws IOException {
		return this.delegate.readValue(content, type);
	}

	@Override
	public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
		return this.delegate.readValue(content, type);
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> type) {
		return this.delegate.convertValue(fromValue, type);
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> type) {
		return this.delegate.convertValue(fromValue, type);
	}

	@Override
	public String writeValueAsString(Object value) throws IOException {
		return this.delegate.writeValueAsString(value instanceof McpError error ? error.getJsonRpcError() : value);
	}

	@Override
	public byte[] writeValueAsBytes(Object value) throws IOException {
		return this.delegate.writeValueAsBytes(value instanceof McpError error ? error.getJsonRpcError() : value);
	}

}
