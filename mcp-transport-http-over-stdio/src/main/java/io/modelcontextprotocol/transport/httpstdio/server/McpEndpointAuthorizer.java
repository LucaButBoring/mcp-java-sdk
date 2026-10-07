package io.modelcontextprotocol.transport.httpstdio.server;

import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.http.StatelessHttpResponse;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2Request;

/**
 * Decides whether a request to the MCP endpoint may be dispatched, letting a child that
 * is an OAuth protected resource answer 401 with a {@code WWW-Authenticate} challenge
 * (RFC 9728 section 5.1) before any MCP processing.
 *
 * <p>
 * It runs only for the MCP endpoint, after Origin and authority validation and before the
 * request body is decoded, so application routes such as protected-resource metadata stay
 * reachable without credentials. Token validation itself is the application's: the
 * transport only carries the decision.
 */
@FunctionalInterface
public interface McpEndpointAuthorizer {

	/**
	 * @param request the HTTP/2 request; its body is borrowed for this call only
	 * @param context the transport context extracted for this request
	 * @return a response to send instead of dispatching, or empty to dispatch
	 */
	Optional<StatelessHttpResponse> authorize(Http2Request request, McpTransportContext context);

}
