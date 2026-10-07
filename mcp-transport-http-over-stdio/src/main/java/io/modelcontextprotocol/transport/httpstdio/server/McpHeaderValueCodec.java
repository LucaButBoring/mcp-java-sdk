/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.transport.httpstdio.server;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Value encoding for the {@code Mcp-Name} and {@code Mcp-Param-*} headers of the MCP
 * 2026-07-28 Streamable HTTP binding ("Value Encoding").
 *
 * <p>
 * A value is carried as plain text when it consists only of visible ASCII, space, and
 * horizontal tab, has no leading or trailing whitespace, and does not itself look like
 * the sentinel. Every other value is carried as {@code =?base64?{Base64(UTF-8)}?=}.
 */
public final class McpHeaderValueCodec {

	/** Case-sensitive sentinel prefix. */
	public static final String SENTINEL_PREFIX = "=?base64?";

	/** Case-sensitive sentinel suffix. */
	public static final String SENTINEL_SUFFIX = "?=";

	private static final BigInteger MAX_SAFE_INTEGER = BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE);

	private McpHeaderValueCodec() {
	}

	/**
	 * Encodes a value for a header.
	 * @param value the value
	 * @return the plain value, or its sentinel form
	 */
	public static String encode(String value) {
		if (isPlainSafe(value) && !isSentinel(value)) {
			return value;
		}
		return SENTINEL_PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
				+ SENTINEL_SUFFIX;
	}

	/**
	 * Decodes a header value.
	 * @param header the received header value
	 * @return the decoded value, or empty when the value contains invalid characters or
	 * is a malformed sentinel
	 */
	public static Optional<String> decode(String header) {
		if (isSentinel(header)) {
			String payload = header.substring(SENTINEL_PREFIX.length(), header.length() - SENTINEL_SUFFIX.length());
			// Canonical padded RFC 4648 base64 only: the JDK decoder would otherwise
			// accept
			// unpadded input, which the SEP-2243 test table requires servers to reject.
			if (payload.length() % 4 != 0) {
				return Optional.empty();
			}
			try {
				byte[] bytes = Base64.getDecoder().decode(payload);
				return Optional.of(StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString());
			}
			catch (IllegalArgumentException | CharacterCodingException exception) {
				return Optional.empty();
			}
		}
		return isPlainSafe(header) ? Optional.of(header) : Optional.empty();
	}

	/**
	 * Converts a primitive argument to its header text before encoding.
	 * @param value a string, boolean, or integer within the JavaScript safe range
	 * @return the text form
	 * @throws IllegalArgumentException for any other value
	 */
	public static String format(Object value) {
		if (value instanceof String string) {
			return string;
		}
		if (value instanceof Boolean bool) {
			return bool.toString();
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
				|| value instanceof BigInteger) {
			BigInteger integer = value instanceof BigInteger big ? big
					: BigInteger.valueOf(((Number) value).longValue());
			if (integer.abs().compareTo(MAX_SAFE_INTEGER) <= 0) {
				return integer.toString();
			}
		}
		throw new IllegalArgumentException("Header values must be strings, booleans, or safe integers: " + value);
	}

	private static boolean isSentinel(String value) {
		return value.length() >= SENTINEL_PREFIX.length() + SENTINEL_SUFFIX.length()
				&& value.startsWith(SENTINEL_PREFIX) && value.endsWith(SENTINEL_SUFFIX);
	}

	private static boolean isPlainSafe(String value) {
		if (value.isEmpty() || isWhitespace(value.charAt(0)) || isWhitespace(value.charAt(value.length() - 1))) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!((c >= 0x21 && c <= 0x7e) || isWhitespace(c))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t';
	}

}
