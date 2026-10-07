/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Validates the MCP 2026-07-28 Streamable HTTP request metadata of one JSON-RPC request
 * ("Protocol Version Header", "Standard Request Headers", "Server Validation").
 */
final class StatelessHttpBindingValidator {

	private static final Logger logger = LoggerFactory.getLogger(StatelessHttpBindingValidator.class);

	static final String PROTOCOL_VERSION_META_KEY = "io.modelcontextprotocol/protocolVersion";

	/** Bounds the default resolver against a handler whose cursors never terminate. */
	private static final int MAX_TOOLS_LIST_PAGES = 100;

	private final McpJsonMapper jsonMapper;

	private final McpHttpBinding2026 binding;

	private final McpStatelessStreamingServerHandler handler;

	private final Map<String, List<String>> jsonHeaders;

	StatelessHttpBindingValidator(McpJsonMapper jsonMapper, McpHttpBinding2026 binding,
			McpStatelessStreamingServerHandler handler, Map<String, List<String>> jsonHeaders) {
		this.jsonMapper = jsonMapper;
		this.binding = binding;
		this.handler = handler;
		this.jsonHeaders = jsonHeaders;
	}

	/**
	 * @return an error response, or empty when the request may be dispatched
	 */
	Mono<Optional<StatelessHttpResponse>> validate(McpSchema.JSONRPCRequest request, McpTransportContext context,
			HeaderAccessor headers) {
		Map<String, Object> params = asMap(request.params());
		for (String scalar : List.of(HttpHeaders.PROTOCOL_VERSION, McpHttpBinding2026.MCP_METHOD,
				McpHttpBinding2026.MCP_NAME)) {
			if (count(headers, scalar) > 1) {
				return mismatch(request, scalar + " header must appear exactly once");
			}
		}
		Optional<String> version = first(headers, HttpHeaders.PROTOCOL_VERSION);
		if (version.isEmpty()) {
			return mismatch(request, "MCP-Protocol-Version header is required");
		}
		Object bodyVersion = asMap(params.get("_meta")).get(PROTOCOL_VERSION_META_KEY);
		if (!(bodyVersion instanceof String)) {
			// A required body field is absent, so the request itself is invalid; there is
			// no
			// body value for the header to mismatch (SEP-2575: -32602, HTTP 400).
			return reject(request, McpSchema.ErrorCodes.INVALID_PARAMS,
					"Invalid params: _meta." + PROTOCOL_VERSION_META_KEY + " is required", null);
		}
		if (!version.get().equals(bodyVersion)) {
			return mismatch(request, "MCP-Protocol-Version header value '" + version.get()
					+ "' does not match body value '" + bodyVersion + "'");
		}
		if (!this.binding.supportedVersions().contains(version.get())) {
			return reject(request, McpHttpBinding2026.UNSUPPORTED_PROTOCOL_VERSION,
					"Unsupported protocol version: " + version.get(),
					Map.of("supported", this.binding.supportedVersions(), "requested", version.get()));
		}
		Optional<String> method = first(headers, McpHttpBinding2026.MCP_METHOD);
		if (method.isEmpty()) {
			return mismatch(request, "Mcp-Method header is required");
		}
		if (!request.method().equals(method.get())) {
			return mismatch(request, "Mcp-Method header value '" + method.get() + "' does not match body value '"
					+ request.method() + "'");
		}
		String nameField = nameField(request.method());
		if (nameField == null) {
			return Mono.just(Optional.empty());
		}
		Object bodyName = params.get(nameField);
		Optional<String> nameHeader = first(headers, McpHttpBinding2026.MCP_NAME);
		if (nameHeader.isEmpty()) {
			return mismatch(request, "Mcp-Name header is required for " + request.method());
		}
		Optional<String> decodedName = McpHeaderValueCodec.decode(nameHeader.get());
		if (decodedName.isEmpty()) {
			return mismatch(request, "Mcp-Name header value contains invalid characters");
		}
		if (!decodedName.get().equals(bodyName)) {
			return mismatch(request,
					"Mcp-Name header value '" + decodedName.get() + "' does not match body value '" + bodyName + "'");
		}
		if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
			return Mono.just(Optional.empty());
		}
		return resolveTool(decodedName.get(), context)
			.map(tool -> tool.flatMap(found -> validateParams(request, params, headers, found)));
	}

	private Optional<StatelessHttpResponse> validateParams(McpSchema.JSONRPCRequest request, Map<String, Object> params,
			HeaderAccessor headers, McpSchema.Tool tool) {
		McpToolHeaderBindings.Result parsed = McpToolHeaderBindings.parse(tool);
		if (parsed instanceof McpToolHeaderBindings.Invalid invalid) {
			logger.warn("Not validating Mcp-Param headers for invalid tool definition '{}': {}", tool.name(),
					invalid.reason());
			return Optional.empty();
		}
		Map<String, Object> arguments = asMap(params.get("arguments"));
		for (McpToolHeaderBindings.Binding binding : ((McpToolHeaderBindings.Valid) parsed).bindings()) {
			String headerName = McpHttpBinding2026.MCP_PARAM_PREFIX + binding.headerName();
			if (count(headers, headerName) > 1) {
				return Optional.of(error(request, McpHttpBinding2026.HEADER_MISMATCH,
						"Header mismatch: " + headerName + " header must appear exactly once", null));
			}
			Optional<String> header = first(headers, headerName);
			Optional<String> decoded = header.flatMap(McpHeaderValueCodec::decode);
			if (header.isPresent() && decoded.isEmpty()) {
				return Optional.of(error(request, McpHttpBinding2026.HEADER_MISMATCH,
						"Header mismatch: " + headerName + " header value contains invalid characters", null));
			}
			Optional<Object> value = McpToolHeaderBindings.extract(arguments, binding);
			if (value.isEmpty()) {
				continue;
			}
			if (decoded.isEmpty()) {
				return Optional.of(error(request, McpHttpBinding2026.HEADER_MISMATCH,
						"Header mismatch: " + headerName + " header is required because the body carries a value",
						null));
			}
			if (!matches(binding.primitiveType(), value.get(), decoded.get())) {
				return Optional.of(error(request, McpHttpBinding2026.HEADER_MISMATCH, "Header mismatch: " + headerName
						+ " header value '" + decoded.get() + "' does not match body value '" + value.get() + "'",
						null));
			}
		}
		return Optional.empty();
	}

	/**
	 * Compares a decoded header with the body value it mirrors. Integers compare
	 * numerically; a body value whose JSON type differs from the declared type is
	 * compared by its text, because the header mirrors the body rather than the schema.
	 */
	static boolean matches(McpToolHeaderBindings.PrimitiveType type, Object body, String header) {
		if (type == McpToolHeaderBindings.PrimitiveType.INTEGER && body instanceof Number number) {
			try {
				return new BigDecimal(header).compareTo(new BigDecimal(number.toString())) == 0;
			}
			catch (NumberFormatException exception) {
				return false;
			}
		}
		if (body instanceof String || body instanceof Boolean || body instanceof Number) {
			return body.toString().equals(header);
		}
		return false;
	}

	private Mono<Optional<McpSchema.Tool>> resolveTool(String name, McpTransportContext context) {
		Mono<Optional<McpSchema.Tool>> resolved = this.binding.toolResolver()
			.map(resolver -> Mono.defer(() -> resolver.resolve(name, context)))
			.orElseGet(() -> findInToolsList(name, context, null, 0));
		return resolved.onErrorResume(exception -> {
			logger.warn("Tool resolution for Mcp-Param validation failed for '{}'", name, exception);
			return Mono.just(Optional.empty());
		}).defaultIfEmpty(Optional.empty());
	}

	/**
	 * Resolves through the installed handler's own {@code tools/list}, following cursors.
	 */
	private Mono<Optional<McpSchema.Tool>> findInToolsList(String name, McpTransportContext context, String cursor,
			int page) {
		if (page >= MAX_TOOLS_LIST_PAGES) {
			return Mono.just(Optional.empty());
		}
		McpSchema.JSONRPCRequest listRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_TOOLS_LIST, "mcp-http-binding-tools-list-" + page,
				cursor == null ? null : Map.of("cursor", cursor));
		return Mono
			.defer(() -> this.handler
				.handle(new McpStatelessStreamingServerHandler.Exchange(context, listRequest, Optional.empty())))
			.flatMap(result -> {
				if (!(result instanceof McpStatelessServerResult.Single single) || single.response().error() != null
						|| single.response().result() == null) {
					return Mono.just(Optional.<McpSchema.Tool>empty());
				}
				McpSchema.ListToolsResult tools = this.jsonMapper.convertValue(single.response().result(),
						McpSchema.ListToolsResult.class);
				Optional<McpSchema.Tool> match = tools.tools()
					.stream()
					.filter(tool -> name.equals(tool.name()))
					.findFirst();
				if (match.isPresent() || tools.nextCursor() == null || tools.nextCursor().equals(cursor)) {
					return Mono.just(match);
				}
				return findInToolsList(name, context, tools.nextCursor(), page + 1);
			});
	}

	private static String nameField(String method) {
		return switch (method) {
			case McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_PROMPT_GET -> "name";
			case McpSchema.METHOD_RESOURCES_READ -> "uri";
			default -> null;
		};
	}

	private Mono<Optional<StatelessHttpResponse>> mismatch(McpSchema.JSONRPCRequest request, String detail) {
		return reject(request, McpHttpBinding2026.HEADER_MISMATCH, "Header mismatch: " + detail, null);
	}

	private Mono<Optional<StatelessHttpResponse>> reject(McpSchema.JSONRPCRequest request, int code, String message,
			Object data) {
		return Mono.just(Optional.of(error(request, code, message, data)));
	}

	private StatelessHttpResponse error(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
		McpSchema.JSONRPCResponse response = McpSchema.JSONRPCResponse.error(request.id(),
				new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
		try {
			return new StatelessHttpResponse(400, this.jsonHeaders,
					new StatelessHttpResponse.Json(this.jsonMapper.writeValueAsString(response)));
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to serialize binding error", exception);
		}
	}

	/**
	 * Counts field lines. A mirrored header that appears more than once is malformed: an
	 * intermediary routing on one occurrence and this server validating another would
	 * reintroduce the split source of truth that header validation exists to prevent.
	 */
	private static int count(HeaderAccessor headers, String name) {
		List<String> values = headers.getHeader(name);
		return values == null ? 0 : values.size();
	}

	private static Optional<String> first(HeaderAccessor headers, String name) {
		List<String> values = headers.getHeader(name);
		return values == null ? Optional.empty() : values.stream().findFirst();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

}
