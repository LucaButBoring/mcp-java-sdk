package io.modelcontextprotocol.transport.httpstdio.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;

/** An aggregated HTTP/2 response. The caller owns and must release {@code body}. */
public record Http2Response(String status, Http2Headers headers, ByteBuf body, boolean endStreamSeen) {
}
