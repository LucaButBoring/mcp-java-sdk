package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A bidirectional byte-transport SPI used by the HTTP-over-stdio channel. Implementations
 * may represent process pipes, domain sockets, or connected in-memory pairs.
 */
public interface DuplexByteChannel extends Closeable {

	InputStream inbound();

	OutputStream outbound();

	/** Closes only the outbound stream, leaving inbound reads available. */
	void shutdownOutput() throws IOException;

	@Override
	void close() throws IOException;

	String describe();

}
