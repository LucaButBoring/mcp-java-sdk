/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpHttpHeaderEncodingTests {

	// Value Encoding table, MCP 2026-07-28 Streamable HTTP.
	@Test
	void encodesSpecificationExamples() {
		assertThat(McpHeaderValueCodec.encode("us-west1")).isEqualTo("us-west1");
		assertThat(McpHeaderValueCodec.encode("Hello, 世界")).isEqualTo("=?base64?SGVsbG8sIOS4lueVjA==?=");
		assertThat(McpHeaderValueCodec.encode(" padded ")).isEqualTo("=?base64?IHBhZGRlZCA=?=");
		assertThat(McpHeaderValueCodec.encode("line1\nline2")).isEqualTo("=?base64?bGluZTEKbGluZTI=?=");
		assertThat(McpHeaderValueCodec.encode("=?base64?literal?=")).isEqualTo("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=");
	}

	@Test
	void decodingInvertsEncoding() {
		for (String value : List.of("us-west1", "Hello, 世界", " padded ", "line1\nline2", "=?base64?literal?=", "",
				"a\tb", "file:///projects/myapp/config.json")) {
			assertThat(McpHeaderValueCodec.decode(McpHeaderValueCodec.encode(value))).contains(value);
		}
	}

	@Test
	void decodingRejectsInvalidValues() {
		assertThat(McpHeaderValueCodec.decode("=?base64?not base64!?=")).isEmpty();
		assertThat(McpHeaderValueCodec.decode("=?base64?SGVsbG8?=")).as("unpadded base64").isEmpty();
		assertThat(McpHeaderValueCodec.decode("=?base64?SGVsbG8=?=")).contains("Hello");
		assertThat(McpHeaderValueCodec.decode("=?base64?/w==?=")).as("0xFF is not UTF-8").isEmpty();
		assertThat(McpHeaderValueCodec.decode("caf\u00e9")).as("non-ASCII plain value").isEmpty();
		assertThat(McpHeaderValueCodec.decode("a\u0001b")).as("control character").isEmpty();
		assertThat(McpHeaderValueCodec.decode("=?BASE64?dXM=?=")).as("markers are case-sensitive")
			.contains("=?BASE64?dXM=?=");
	}

	@Test
	void formatsPrimitiveValues() {
		assertThat(McpHeaderValueCodec.format("x")).isEqualTo("x");
		assertThat(McpHeaderValueCodec.format(true)).isEqualTo("true");
		assertThat(McpHeaderValueCodec.format(false)).isEqualTo("false");
		assertThat(McpHeaderValueCodec.format(-7)).isEqualTo("-7");
		assertThat(McpHeaderValueCodec.format(9007199254740991L)).isEqualTo("9007199254740991");
		assertThat(McpHeaderValueCodec.format(BigInteger.valueOf(-9007199254740991L))).isEqualTo("-9007199254740991");
		assertThatThrownBy(() -> McpHeaderValueCodec.format(9007199254740992L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> McpHeaderValueCodec.format(1.5)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parsesBindingsOnStaticallyReachableProperties() {
		McpToolHeaderBindings.Result result = McpToolHeaderBindings.parse(tool(Map.of("type", "object", "properties",
				Map.of("region", Map.of("type", "string", "x-mcp-header", "Region"), "options",
						Map.of("type", "object", "properties",
								Map.of("dryRun", Map.of("type", "boolean", "x-mcp-header", "Dry-Run"), "limit",
										Map.of("type", "integer", "x-mcp-header", "Limit")))))));
		assertThat(result).isInstanceOf(McpToolHeaderBindings.Valid.class);
		assertThat(((McpToolHeaderBindings.Valid) result).bindings()).containsExactlyInAnyOrder(
				new McpToolHeaderBindings.Binding("Region", List.of("region"),
						McpToolHeaderBindings.PrimitiveType.STRING),
				new McpToolHeaderBindings.Binding("Dry-Run", List.of("options", "dryRun"),
						McpToolHeaderBindings.PrimitiveType.BOOLEAN),
				new McpToolHeaderBindings.Binding("Limit", List.of("options", "limit"),
						McpToolHeaderBindings.PrimitiveType.INTEGER));
	}

	@Test
	void rejectsAnnotationsOutsideAPropertiesChain() {
		Map<String, Object> annotated = Map.of("type", "string", "x-mcp-header", "X");
		for (Map<String, Object> schema : List.<Map<String, Object>>of(Map.of("type", "array", "items", annotated),
				Map.of("type", "object", "properties",
						Map.of("list",
								Map.of("type", "array", "items",
										Map.of("type", "object", "properties", Map.of("x", annotated))))),
				Map.of("oneOf", List.of(Map.of("type", "object", "properties", Map.of("x", annotated)))),
				Map.of("anyOf", List.of(annotated)), Map.of("allOf", List.of(annotated)), Map.of("not", annotated),
				Map.of("if", annotated), Map.of("then", annotated), Map.of("else", annotated),
				Map.of("$defs", Map.of("d", annotated)), Map.of("definitions", Map.of("d", annotated)),
				Map.of("additionalProperties", annotated), Map.of("type", "object", "properties",
						Map.of("x", Map.of("$ref", "#/$defs/d")), "$defs", Map.of("d", annotated)),
				annotated)) {
			assertThat(McpToolHeaderBindings.parse(tool(schema))).as(schema.toString())
				.isInstanceOf(McpToolHeaderBindings.Invalid.class);
		}
	}

	@Test
	void rejectsInvalidAnnotationValuesAndTypes() {
		for (Map<String, Object> property : List.<Map<String, Object>>of(Map.of("type", "string", "x-mcp-header", ""),
				Map.of("type", "string", "x-mcp-header", "Has Space"),
				Map.of("type", "string", "x-mcp-header", "Bad\r\nName"),
				Map.of("type", "string", "x-mcp-header", "R\u00e9gion"),
				Map.of("type", "number", "x-mcp-header", "Ratio"), Map.of("type", "object", "x-mcp-header", "Obj"),
				Map.of("x-mcp-header", "Untyped"), Map.of("type", "string", "x-mcp-header", 42))) {
			assertThat(McpToolHeaderBindings.parse(tool(Map.of("type", "object", "properties", Map.of("p", property)))))
				.as(property.toString())
				.isInstanceOf(McpToolHeaderBindings.Invalid.class);
		}
	}

	@Test
	void rejectsCaseInsensitiveDuplicates() {
		assertThat(McpToolHeaderBindings.parse(tool(Map.of("type", "object", "properties",
				Map.of("a", Map.of("type", "string", "x-mcp-header", "Region"), "b",
						Map.of("type", "string", "x-mcp-header", "REGION"))))))
			.isInstanceOf(McpToolHeaderBindings.Invalid.class);
	}

	@Test
	void ignoresInstanceDataKeywords() {
		assertThat(McpToolHeaderBindings.parse(tool(Map.of("type", "object", "properties",
				Map.of("p",
						Map.of("type", "object", "default", Map.of("x-mcp-header", "NotSchema"), "examples",
								List.of(Map.of("x-mcp-header", "NotSchema"))))))))
			.isEqualTo(new McpToolHeaderBindings.Valid(List.of()));
	}

	@Test
	void extractsValuesAtTheExactPath() {
		McpToolHeaderBindings.Binding binding = new McpToolHeaderBindings.Binding("Limit", List.of("options", "limit"),
				McpToolHeaderBindings.PrimitiveType.INTEGER);
		assertThat(McpToolHeaderBindings.extract(Map.of("options", Map.of("limit", 5)), binding)).contains(5);
		assertThat(McpToolHeaderBindings.extract(Map.of("options", Map.of()), binding)).isEmpty();
		assertThat(McpToolHeaderBindings.extract(Map.of("limit", 5), binding)).isEmpty();
		java.util.HashMap<String, Object> nullValue = new java.util.HashMap<>();
		nullValue.put("limit", null);
		assertThat(McpToolHeaderBindings.extract(Map.of("options", nullValue), binding)).isEmpty();
	}

	private static McpSchema.Tool tool(Map<String, Object> inputSchema) {
		return McpSchema.Tool.builder("t", inputSchema).build();
	}

}
