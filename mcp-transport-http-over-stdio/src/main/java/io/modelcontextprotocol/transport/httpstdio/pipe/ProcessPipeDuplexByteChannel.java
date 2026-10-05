package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A duplex byte channel backed by a child process's raw stdin/stdout pipes. */
public final class ProcessPipeDuplexByteChannel implements DuplexByteChannel {

	private final InputStream inbound;

	private final OutputStream outbound;

	private final String description;

	private final AtomicBoolean outputClosed = new AtomicBoolean();

	private ProcessPipeDuplexByteChannel(InputStream inbound, OutputStream outbound, String description) {
		this.inbound = Objects.requireNonNull(inbound, "inbound");
		this.outbound = Objects.requireNonNull(outbound, "outbound");
		this.description = description;
	}

	public static ProcessPipeDuplexByteChannel forChild(Process process) {
		Objects.requireNonNull(process, "process");
		return new ProcessPipeDuplexByteChannel(process.getInputStream(), process.getOutputStream(),
				"child-process[pid=" + process.pid() + "]");
	}

	public static ProcessPipeDuplexByteChannel forCurrentProcess() {
		return new ProcessPipeDuplexByteChannel(new FileInputStream(FileDescriptor.in),
				new FileOutputStream(FileDescriptor.out), "current-process[stdin/stdout]");
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
