package io.modelcontextprotocol.transport.httpstdio.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;

/** Writes one HTTP/2 response stream. Data buffers are transferred to Netty. */
public interface Http2ResponseWriter {

	void headers(String status, Http2Headers headers);

	void data(ByteBuf data, boolean endStream);

	void end();

	void reset(long errorCode);

	void onCancelled(Runnable callback);

}
