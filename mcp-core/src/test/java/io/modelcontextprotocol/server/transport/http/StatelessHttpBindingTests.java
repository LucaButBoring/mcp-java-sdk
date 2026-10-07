/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 2026-07-28 Streamable HTTP request-metadata binding in
 * {@link StatelessHttpDispatcher}.
 */
class StatelessHttpBindingTests {

	private static final McpJsonMapper JSON_MAPPER = new TestMcpJsonMapper();

	private static final String V = ProtocolVersions.MCP_2026_07_28;

	private static final McpSchema.Tool SQL_TOOL = McpSchema.Tool
		.builder("execute_sql",
				Map.of("type", "object", "properties",
						Map.of("region", Map.of("type", "string", "x-mcp-header", "Region"), "limit",
								Map.of("type", "integer", "x-mcp-header", "Limit"), "dry",
								Map.of("type", "boolean", "x-mcp-header", "Dry"), "query", Map.of("type", "string"))))
		.build();

	private final List<McpSchema.JSONRPCMessage> handled = new CopyOnWriteArrayList<>();

	@Test
	void acceptsMatchingHeadersForEachNamedMethodFamily() {
		assertThat(dispatch(toolsCall(Map.of("region", "us-west1", "query", "q")),
				headers("tools/call", "execute_sql", "Mcp-Param-Region", "us-west1"))
			.status()).isEqualTo(200);
		assertThat(dispatch(request("prompts/get", Map.of("name", "greet")), headers("prompts/get", "greet")).status())
			.isEqualTo(200);
		assertThat(dispatch(request("resources/read", Map.of("uri", "file:///a b.txt")),
				headers("resources/read", "=?base64?ZmlsZTovLy9hIGIudHh0?="))
			.status()).isEqualTo(200);
		assertThat(dispatch(request("tools/list", Map.of()), headers("tools/list", null)).status()).isEqualTo(200);
	}

	@Test
	void protocolVersionHeaderIsRequiredAndMustMatchMeta() {
		Map<String, List<String>> headers = headers("tools/list", null);
		headers.remove("MCP-Protocol-Version");
		assertMismatch(dispatch(request("tools/list", Map.of()), headers), "MCP-Protocol-Version header is required");

		assertMismatch(dispatch(request("tools/list", Map.of(), "2025-11-25"), headers("tools/list", null)),
				"MCP-Protocol-Version header value '2026-07-28' does not match body value '2025-11-25'");

		assertMismatch(dispatch(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/list", "1", Map.of()),
				headers("tools/list", null)), "does not match body value 'null'");
	}

	@Test
	void unsupportedVersionCarriesSupportedAndRequested() {
		Map<String, List<String>> headers = headers("tools/list", null);
		headers.put("MCP-Protocol-Version", List.of("2099-01-01"));
		StatelessHttpResponse response = dispatch(request("tools/list", Map.of(), "2099-01-01"), headers);
		McpSchema.JSONRPCResponse.JSONRPCError error = error(response, 400);
		assertThat(error.code()).isEqualTo(McpSchema.ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION);
		assertThat(error.data()).isEqualTo(Map.of("supported", List.of(V), "requested", "2099-01-01"));
		assertThat(this.handled).isEmpty();
	}

	@Test
	void mcpMethodIsRequiredAndMustMatch() {
		Map<String, List<String>> missing = headers("tools/list", null);
		missing.remove("Mcp-Method");
		assertMismatch(dispatch(request("tools/list", Map.of()), missing), "Mcp-Method header is required");
		assertMismatch(dispatch(request("tools/list", Map.of()), headers("Tools/List", null)),
				"Mcp-Method header value 'Tools/List' does not match body value 'tools/list'");
	}

	@Test
	void mcpNameIsRequiredDecodedAndCompared() {
		assertMismatch(dispatch(request("prompts/get", Map.of("name", "greet")), headers("prompts/get", null)),
				"Mcp-Name header is required for prompts/get");
		assertMismatch(dispatch(request("prompts/get", Map.of("name", "bar")), headers("prompts/get", "foo")),
				"Mcp-Name header value 'foo' does not match body value 'bar'");
		assertMismatch(dispatch(request("prompts/get", Map.of("name", "bar")), headers("prompts/get", "=?base64?%%?=")),
				"Mcp-Name header value contains invalid characters");
		assertThat(dispatch(request("prompts/get", Map.of("name", "Grüße")),
				headers("prompts/get", "=?base64?R3LDvMOfZQ==?="))
			.status()).isEqualTo(200);
	}

	@Test
	void headerNamesAreCaseInsensitive() {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers("tools/call", "execute_sql", "Mcp-Param-Region", "eu")
			.forEach((k, v) -> headers.put(k.toUpperCase(), v));
		assertThat(dispatch(toolsCall(Map.of("region", "eu")), headers).status()).isEqualTo(200);
	}

	@Test
	void mcpParamHeadersMustMirrorBodyValues() {
		assertMismatch(dispatch(toolsCall(Map.of("region", "us-west1")), headers("tools/call", "execute_sql")),
				"Mcp-Param-Region header is required because the body carries a value");
		assertMismatch(
				dispatch(toolsCall(Map.of("region", "us-west1")),
						headers("tools/call", "execute_sql", "Mcp-Param-Region", "us-east1")),
				"Mcp-Param-Region header value 'us-east1' does not match body value 'us-west1'");
		assertMismatch(
				dispatch(toolsCall(Map.of("region", "us")), headers("tools/call", "execute_sql", "Mcp-Param-Region",
						"us", "Mcp-Param-Dry", "=?base64?*?=")),
				"Mcp-Param-Dry header value contains invalid characters");
		assertMismatch(
				dispatch(toolsCall(Map.of("dry", true)), headers("tools/call", "execute_sql", "Mcp-Param-Dry", "TRUE")),
				"Mcp-Param-Dry header value 'TRUE' does not match body value 'true'");
	}

	@Test
	void duplicatedMirroredHeadersAreRejected() {
		for (String name : List.of("MCP-Protocol-Version", "Mcp-Method", "Mcp-Name")) {
			Map<String, List<String>> headers = headers("tools/call", "execute_sql");
			headers.put(name, List.of(headers.get(name).get(0), "other"));
			assertMismatch(dispatch(toolsCall(Map.of("query", "q")), headers),
					name + " header must appear exactly once");
		}
		Map<String, List<String>> param = headers("tools/call", "execute_sql");
		param.put("Mcp-Param-Region", List.of("us-west1", "us-east1"));
		assertMismatch(dispatch(toolsCall(Map.of("region", "us-west1")), param),
				"Mcp-Param-Region header must appear exactly once");
		Map<String, List<String>> unknown = headers("tools/call", "execute_sql");
		unknown.put("Mcp-Param-Unknown", List.of("a", "b"));
		assertThat(dispatch(toolsCall(Map.of("query", "q")), unknown).status())
			.as("unrecognized Mcp-Param headers are forwarded and otherwise ignored")
			.isEqualTo(200);
	}

	@Test
	void integersCompareNumericallyAndAbsentOrNullValuesAreNotExpected() {
		assertThat(dispatch(toolsCall(Map.of("limit", 42)),
				headers("tools/call", "execute_sql", "Mcp-Param-Limit", "42.0"))
			.status()).isEqualTo(200);
		assertMismatch(
				dispatch(toolsCall(Map.of("limit", 42)), headers("tools/call", "execute_sql", "Mcp-Param-Limit", "43")),
				"Mcp-Param-Limit header value '43' does not match body value");
		Map<String, Object> nullRegion = new HashMap<>();
		nullRegion.put("region", null);
		assertThat(dispatch(toolsCall(nullRegion), headers("tools/call", "execute_sql")).status()).isEqualTo(200);
		assertThat(dispatch(toolsCall(Map.of("query", "q")),
				headers("tools/call", "execute_sql", "Mcp-Param-Unknown", "ignored"))
			.status()).isEqualTo(200);
	}

	@Test
	void unknownOrInvalidToolSkipsParamValidation() {
		assertThat(dispatch(request("tools/call", Map.of("name", "missing", "arguments", Map.of("region", "x"))),
				headers("tools/call", "missing"))
			.status()).isEqualTo(200);
		McpSchema.Tool invalid = McpSchema.Tool
			.builder("bad", Map.of("type", "object", "properties",
					Map.of("region", Map.of("type", "number", "x-mcp-header", "Region"))))
			.build();
		StatelessHttpDispatcher dispatcher = dispatcher(handler(List.of(List.of(invalid))));
		assertThat(dispatcher
			.dispatch(httpRequest(request("tools/call", Map.of("name", "bad", "arguments", Map.of("region", 1))),
					headers("tools/call", "bad")))
			.block()
			.status()).isEqualTo(200);
	}

	@Test
	void defaultResolverFollowsToolsListPaginationWithTheRequestContext() {
		List<McpTransportContext> listContexts = new CopyOnWriteArrayList<>();
		McpStatelessStreamingServerHandler paged = exchange -> {
			McpSchema.JSONRPCRequest inbound = (McpSchema.JSONRPCRequest) exchange.message();
			if ("tools/list".equals(inbound.method())) {
				listContexts.add(exchange.transportContext());
				boolean second = inbound.params() instanceof Map<?, ?> p && "page-2".equals(p.get("cursor"));
				return Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(inbound.id(),
						second ? new McpSchema.ListToolsResult(List.of(SQL_TOOL), null)
								: new McpSchema.ListToolsResult(
										List.of(McpSchema.Tool.builder("other", Map.of("type", "object")).build()),
										"page-2"))));
			}
			this.handled.add(inbound);
			return Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(inbound.id(), "ok")));
		};
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "a"));
		StatelessHttpDispatcher dispatcher = dispatcher(paged);
		StatelessHttpResponse response = dispatcher
			.dispatch(httpRequest(toolsCall(Map.of("region", "us-west1")),
					headers("tools/call", "execute_sql", "Mcp-Param-Region", "us-east1"), context))
			.block();
		assertMismatch(response, "Mcp-Param-Region header value 'us-east1'");
		assertThat(listContexts).hasSize(2).allMatch(context::equals);
		assertThat(this.handled).isEmpty();
	}

	@Test
	void customResolverReplacesToolsList() {
		AtomicReference<String> resolvedName = new AtomicReference<>();
		McpHttpBinding2026 binding = McpHttpBinding2026.builder().toolResolver((name, context) -> {
			resolvedName.set(name);
			return Mono.just(Optional.of(SQL_TOOL));
		}).build();
		StatelessHttpDispatcher dispatcher = new StatelessHttpDispatcher(JSON_MAPPER, exchange -> {
			McpSchema.JSONRPCRequest inbound = (McpSchema.JSONRPCRequest) exchange.message();
			assertThat(inbound.method()).isNotEqualTo("tools/list");
			return Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(inbound.id(), "ok")));
		}, 1_000_000, () -> false, ServerHttpHeaderValidator.NOOP, binding);
		assertMismatch(dispatcher
			.dispatch(httpRequest(toolsCall(Map.of("region", "a")), headers("tools/call", "execute_sql"),
					McpTransportContext.EMPTY))
			.block(), "Mcp-Param-Region header is required");
		assertThat(resolvedName).hasValue("execute_sql");
	}

	@Test
	void notificationsAreNotHeaderValidated() {
		StatelessHttpResponse response = dispatch(
				new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "notifications/initialized", null),
				new LinkedHashMap<>(Map.of("Accept", List.of("application/json, text/event-stream"))));
		assertThat(response.status()).isEqualTo(202);
	}

	@Test
	void methodNotFoundMapsTo404OnlyWithBinding() {
		McpStatelessStreamingServerHandler notFound = exchange -> Mono.just(new McpStatelessServerResult.Single(
				McpSchema.JSONRPCResponse.error(((McpSchema.JSONRPCRequest) exchange.message()).id(),
						new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
								"Method not found: nope", null))));
		McpSchema.JSONRPCRequest nope = request("nope", Map.of());
		StatelessHttpResponse bound = dispatcher(notFound).dispatch(httpRequest(nope, headers("nope", null))).block();
		assertThat(bound.status()).isEqualTo(404);
		assertThat(error(bound, 404).code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		StatelessHttpResponse legacy = new StatelessHttpDispatcher(JSON_MAPPER, notFound, 1_000_000)
			.dispatch(httpRequest(nope, headers("nope", null)))
			.block();
		assertThat(legacy.status()).isEqualTo(200);
	}

	@Test
	void legacyMessagePhaseOverloadIsRejectedWhenBindingIsConfigured() {
		assertThatThrownBy(() -> dispatcher(handler(List.of(List.of(SQL_TOOL)))).handle("{}", McpTransportContext.EMPTY,
				Optional.of(V)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void longLivedStreamsAreMarked() {
		McpStatelessStreamingServerHandler listen = exchange -> Mono
			.just(McpStatelessServerResult.Stream.longLived(Flux.never()));
		StatelessHttpResponse response = dispatcher(listen)
			.dispatch(httpRequest(request("subscriptions/listen", Map.of()), headers("subscriptions/listen", null)))
			.block();
		assertThat(((StatelessHttpResponse.Sse) response.body()).longLived()).isTrue();
		McpStatelessStreamingServerHandler scoped = exchange -> Mono
			.just(McpStatelessServerResult.Stream.requestScoped(Flux
				.just(McpSchema.JSONRPCResponse.result(((McpSchema.JSONRPCRequest) exchange.message()).id(), "ok"))));
		StatelessHttpResponse finite = dispatcher(scoped)
			.dispatch(httpRequest(request("tools/list", Map.of()), headers("tools/list", null)))
			.block();
		assertThat(((StatelessHttpResponse.Sse) finite.body()).longLived()).isFalse();
	}

	/**
	 * Dispatches one request; {@link #handled} then records only this request's handler
	 * calls.
	 */
	private StatelessHttpResponse dispatch(McpSchema.JSONRPCMessage message, Map<String, List<String>> headers) {
		this.handled.clear();
		return dispatcher(handler(List.of(List.of(SQL_TOOL)))).dispatch(httpRequest(message, headers)).block();
	}

	private static StatelessHttpDispatcher dispatcher(McpStatelessStreamingServerHandler handler) {
		return new StatelessHttpDispatcher(JSON_MAPPER, handler, 1_000_000, () -> false, ServerHttpHeaderValidator.NOOP,
				McpHttpBinding2026.builder().build());
	}

	/**
	 * Serves tools/list from the given pages and answers every other request with "ok".
	 */
	private McpStatelessStreamingServerHandler handler(List<List<McpSchema.Tool>> pages) {
		return exchange -> {
			if (exchange.message() instanceof McpSchema.JSONRPCNotification) {
				return Mono.just(new McpStatelessServerResult.Accepted());
			}
			McpSchema.JSONRPCRequest inbound = (McpSchema.JSONRPCRequest) exchange.message();
			if ("tools/list".equals(inbound.method()) && inbound.id().toString().startsWith("mcp-http-binding")) {
				int page = inbound.params() instanceof Map<?, ?> p && p.get("cursor") != null
						? Integer.parseInt(p.get("cursor").toString()) : 0;
				String next = page + 1 < pages.size() ? Integer.toString(page + 1) : null;
				return Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(inbound.id(),
						new McpSchema.ListToolsResult(pages.get(page), next))));
			}
			this.handled.add(inbound);
			return Mono.just(new McpStatelessServerResult.Single(McpSchema.JSONRPCResponse.result(inbound.id(), "ok")));
		};
	}

	private static McpSchema.JSONRPCRequest toolsCall(Map<String, Object> arguments) {
		return request("tools/call", Map.of("name", "execute_sql", "arguments", arguments));
	}

	private static McpSchema.JSONRPCRequest request(String method, Map<String, Object> params) {
		return request(method, params, V);
	}

	private static McpSchema.JSONRPCRequest request(String method, Map<String, Object> params, String metaVersion) {
		Map<String, Object> withMeta = new LinkedHashMap<>(params);
		withMeta.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", metaVersion));
		return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, "1", withMeta);
	}

	/**
	 * Builds 2026 headers; {@code name} null omits Mcp-Name; extra pairs are appended.
	 */
	private static Map<String, List<String>> headers(String method, String name, String... extra) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Accept", List.of("application/json, text/event-stream"));
		headers.put("MCP-Protocol-Version", List.of(V));
		headers.put("Mcp-Method", List.of(method));
		if (name != null) {
			headers.put("Mcp-Name", List.of(name));
		}
		for (int i = 0; i < extra.length; i += 2) {
			headers.put(extra[i], List.of(extra[i + 1]));
		}
		return headers;
	}

	private static StatelessHttpRequest httpRequest(McpSchema.JSONRPCMessage message,
			Map<String, List<String>> headers) {
		return httpRequest(message, headers, McpTransportContext.EMPTY);
	}

	private static StatelessHttpRequest httpRequest(McpSchema.JSONRPCMessage message, Map<String, List<String>> headers,
			McpTransportContext context) {
		try {
			return new StatelessHttpRequest("POST", "/mcp", new Headers(headers), Optional.empty(),
					JSON_MAPPER.writeValueAsString(message), context);
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static McpSchema.JSONRPCResponse.JSONRPCError error(StatelessHttpResponse response, int status) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.headers()).containsEntry("Content-Type", List.of("application/json;charset=UTF-8"));
		try {
			McpSchema.JSONRPCResponse parsed = JSON_MAPPER
				.readValue(((StatelessHttpResponse.Json) response.body()).text(), McpSchema.JSONRPCResponse.class);
			assertThat(parsed.id()).isEqualTo("1");
			return parsed.error();
		}
		catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private void assertMismatch(StatelessHttpResponse response, String messageFragment) {
		McpSchema.JSONRPCResponse.JSONRPCError error = error(response, 400);
		assertThat(error.code()).isEqualTo(McpSchema.ErrorCodes.HEADER_MISMATCH);
		assertThat(error.message()).startsWith("Header mismatch: ").contains(messageFragment);
		assertThat(this.handled).as("handler must not run for a rejected request").isEmpty();
	}

	private record Headers(Map<String, List<String>> values) implements HeaderAccessor {

		@Override
		public List<String> getHeader(String name) {
			return this.values.entrySet()
				.stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(List.of());
		}

		@Override
		public List<String> getHeaderNames() {
			return new ArrayList<>(this.values.keySet());
		}

	}

}
