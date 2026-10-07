/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Parses {@code x-mcp-header} annotations from a tool input schema, as defined by the MCP
 * 2026-07-28 Streamable HTTP binding ("Custom Headers from Tool Parameters").
 *
 * <p>
 * An annotation is valid only on a {@code string}, {@code integer}, or {@code boolean}
 * property that is statically reachable from the schema root through a chain consisting
 * solely of {@code properties} keys. An annotation anywhere else in the schema, a
 * non-token name, or a case-insensitive duplicate makes the whole tool definition
 * invalid. Values of instance-data keywords ({@code default}, {@code const},
 * {@code enum}, {@code examples}) are data rather than schema and are not inspected.
 */
public final class McpToolHeaderBindings {

	/** The schema extension keyword. */
	public static final String KEYWORD = "x-mcp-header";

	private static final Set<String> INSTANCE_DATA_KEYWORDS = Set.of("default", "const", "enum", "examples");

	private static final String TCHAR_SYMBOLS = "!#$%&'*+-.^_`|~";

	private McpToolHeaderBindings() {
	}

	/** Outcome of parsing a tool's annotations. */
	public sealed interface Result permits Valid, Invalid {

	}

	/**
	 * A valid tool definition and its bindings, in schema order.
	 *
	 * @param bindings the annotated properties
	 */
	public record Valid(List<Binding> bindings) implements Result {

		public Valid {
			bindings = List.copyOf(bindings);
		}

	}

	/**
	 * An invalid tool definition that a Streamable HTTP client must exclude from
	 * {@code tools/list}.
	 *
	 * @param reason human-readable reason
	 */
	public record Invalid(String reason) implements Result {
	}

	/**
	 * One annotated property.
	 *
	 * @param headerName the {@code x-mcp-header} value; the HTTP header is
	 * {@code Mcp-Param-{headerName}}
	 * @param propertyPath chain of {@code properties} keys from the schema root
	 * @param primitiveType declared JSON Schema type of the property
	 */
	public record Binding(String headerName, List<String> propertyPath, PrimitiveType primitiveType) {

		public Binding {
			propertyPath = List.copyOf(propertyPath);
		}

	}

	/** Primitive types permitted for annotated properties. */
	public enum PrimitiveType {

		STRING, INTEGER, BOOLEAN

	}

	/**
	 * Parses the annotations of a tool.
	 * @param tool the tool
	 * @return the bindings, or the reason the definition is invalid
	 */
	public static Result parse(McpSchema.Tool tool) {
		List<Binding> bindings = new ArrayList<>();
		Set<String> names = new HashSet<>();
		String failure = walk(tool.inputSchema(), List.of(), true, bindings, names);
		return failure == null ? new Valid(bindings) : new Invalid(failure);
	}

	/**
	 * Reads the instance value at a binding's property path.
	 * @param arguments tool call arguments
	 * @param binding the binding
	 * @return the value, or empty when absent or {@code null}
	 */
	public static Optional<Object> extract(Map<String, Object> arguments, Binding binding) {
		Object current = arguments;
		for (String property : binding.propertyPath()) {
			if (!(current instanceof Map<?, ?> map)) {
				return Optional.empty();
			}
			current = map.get(property);
			if (current == null) {
				return Optional.empty();
			}
		}
		return Optional.of(current);
	}

	/**
	 * Walks one schema node.
	 * @param reachable whether the node is reached from the root through
	 * {@code properties} keys only
	 * @return a failure reason, or {@code null} when the subtree is valid
	 */
	private static String walk(Object node, List<String> path, boolean reachable, List<Binding> bindings,
			Set<String> names) {
		if (node instanceof List<?> list) {
			for (Object element : list) {
				String failure = walk(element, path, false, bindings, names);
				if (failure != null) {
					return failure;
				}
			}
			return null;
		}
		if (!(node instanceof Map<?, ?> map)) {
			return null;
		}
		if (map.containsKey(KEYWORD)) {
			String failure = bind(map, path, reachable, bindings, names);
			if (failure != null) {
				return failure;
			}
		}
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String key = String.valueOf(entry.getKey());
			if (KEYWORD.equals(key) || INSTANCE_DATA_KEYWORDS.contains(key)) {
				continue;
			}
			String failure;
			if ("properties".equals(key) && entry.getValue() instanceof Map<?, ?> properties) {
				failure = null;
				for (Map.Entry<?, ?> property : properties.entrySet()) {
					List<String> childPath = new ArrayList<>(path);
					childPath.add(String.valueOf(property.getKey()));
					failure = walk(property.getValue(), childPath, reachable, bindings, names);
					if (failure != null) {
						break;
					}
				}
			}
			else {
				failure = walk(entry.getValue(), path, false, bindings, names);
			}
			if (failure != null) {
				return failure;
			}
		}
		return null;
	}

	private static String bind(Map<?, ?> schema, List<String> path, boolean reachable, List<Binding> bindings,
			Set<String> names) {
		if (!reachable || path.isEmpty()) {
			return KEYWORD + " must annotate a property reachable from the root through properties keys only";
		}
		if (!(schema.get(KEYWORD) instanceof String name) || !isToken(name)) {
			return KEYWORD + " value must be a non-empty HTTP field-name token";
		}
		PrimitiveType type = switch (String.valueOf(schema.get("type"))) {
			case "string" -> PrimitiveType.STRING;
			case "integer" -> PrimitiveType.INTEGER;
			case "boolean" -> PrimitiveType.BOOLEAN;
			default -> null;
		};
		if (type == null) {
			return KEYWORD + " '" + name + "' must annotate a string, integer, or boolean property";
		}
		if (!names.add(name.toLowerCase(Locale.ROOT))) {
			return KEYWORD + " '" + name + "' is not case-insensitively unique";
		}
		bindings.add(new Binding(name, path, type));
		return null;
	}

	/** RFC 9110 section 5.6.2 {@code token = 1*tchar}. */
	static boolean isToken(String value) {
		if (value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean alpha = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
			boolean digit = c >= '0' && c <= '9';
			if (!alpha && !digit && TCHAR_SYMBOLS.indexOf(c) < 0) {
				return false;
			}
		}
		return true;
	}

}
