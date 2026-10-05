package io.modelcontextprotocol.transport.httpstdio.http2;

/**
 * Handles one fully aggregated HTTP/2 request synchronously. The request body is borrowed
 * for the duration of {@link #handle(Http2Request, Http2ResponseWriter)}; see
 * {@link Http2Request} for retention requirements.
 */
@FunctionalInterface
public interface Http2RequestHandler {

	void handle(Http2Request request, Http2ResponseWriter response) throws Exception;

}
