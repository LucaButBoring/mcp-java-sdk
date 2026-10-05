package io.modelcontextprotocol.transport.httpstdio.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;

/**
 * An aggregated HTTP/2 request.
 * <p>
 * {@code body} is borrowed and remains valid only for the synchronous duration of
 * {@link Http2RequestHandler#handle(Http2Request, Http2ResponseWriter)}. The server
 * releases it after the handler returns. A handler that needs the bytes afterward must
 * retain the body, or a derived buffer, and release that retained reference itself.
 */
public record Http2Request(String method, String path, Http2Headers headers, ByteBuf body) {
}
