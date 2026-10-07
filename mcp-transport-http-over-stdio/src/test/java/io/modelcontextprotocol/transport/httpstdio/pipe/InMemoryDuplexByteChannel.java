package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, connected in-memory duplex channels intended for tests and embedding. */
public final class InMemoryDuplexByteChannel implements DuplexByteChannel {

	private final InputStream inbound;

	private final OutputStream outbound;

	private final String description;

	private final AtomicBoolean outputClosed = new AtomicBoolean();

	private InMemoryDuplexByteChannel(InputStream inbound, OutputStream outbound, String description) {
		this.inbound = inbound;
		this.outbound = outbound;
		this.description = description;
	}

	public static DuplexByteChannel[] pair(int bufferBytes) throws IOException {
		if (bufferBytes <= 0) {
			throw new IllegalArgumentException("bufferBytes must be positive");
		}
		PipedInputStream aInbound = new PipedInputStream(bufferBytes);
		PipedInputStream bInbound = new PipedInputStream(bufferBytes);
		PipedOutputStream aOutbound = new PipedOutputStream(bInbound);
		PipedOutputStream bOutbound = new PipedOutputStream(aInbound);
		return new DuplexByteChannel[] {
				new InMemoryDuplexByteChannel(aInbound, aOutbound, "in-memory-end-a[buffer=" + bufferBytes + "]"),
				new InMemoryDuplexByteChannel(bInbound, bOutbound, "in-memory-end-b[buffer=" + bufferBytes + "]") };
	}

	@Override
	public InputStream inbound() {
		return this.inbound;
	}

	@Override
	public OutputStream outbound() {
		return this.outbound;
	}

	@Override
	public void shutdownOutput() throws IOException {
		if (this.outputClosed.compareAndSet(false, true)) {
			this.outbound.close();
		}
	}

	@Override
	public void close() throws IOException {
		IOException failure = null;
		try {
			shutdownOutput();
		}
		catch (IOException ex) {
			failure = ex;
		}
		try {
			this.inbound.close();
		}
		catch (IOException ex) {
			if (failure == null) {
				failure = ex;
			}
			else {
				failure.addSuppressed(ex);
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	@Override
	public String describe() {
		return this.description;
	}

}
