/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport.http;

/**
 * Indicates that a stateless exchange violated its message contract.
 *
 * <p>
 * Client-side violations describe invalid inbound messages and map to HTTP 400.
 * Handler-side violations describe invalid handler results and map to HTTP 500 before
 * response headers are committed, or fail an already-started SSE stream.
 */
public class McpStatelessContractException extends RuntimeException {

	/** The side of the exchange that violated the contract. */
	public enum Origin {

		/** The inbound client message violated the contract. */
		CLIENT,

		/** The server handler result violated the contract. */
		HANDLER

	}

	private final int code;

	private final Origin origin;

	/**
	 * Creates a client-side contract exception.
	 * @param code the MCP-compatible error code
	 * @param message the error message
	 */
	public McpStatelessContractException(int code, String message) {
		this(code, message, Origin.CLIENT);
	}

	/**
	 * Creates a contract exception.
	 * @param code the MCP-compatible error code
	 * @param message the error message
	 * @param origin the side that violated the contract
	 */
	public McpStatelessContractException(int code, String message, Origin origin) {
		super(message);
		this.code = code;
		this.origin = origin;
	}

	/** Returns the MCP-compatible error code. */
	public int getCode() {
		return this.code;
	}

	/** Returns the side that violated the contract. */
	public Origin getOrigin() {
		return this.origin;
	}

	/** Returns whether this is a handler-side violation. */
	public boolean isHandlerViolation() {
		return this.origin == Origin.HANDLER;
	}

}
