package io.modelcontextprotocol.transport.httpstdio.pipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Unix-domain-socket fallback wire (design memo section 2): a
 * {@link DuplexByteChannel} over a connected {@code AF_UNIX} stream socket, for hosts
 * where the parent and child should not share stdio. The HTTP/2 layer above is unchanged.
 *
 * <p>
 * Reads and writes go straight to the {@link SocketChannel}, which allows one blocking
 * reader and one blocking writer concurrently. {@code Channels.newInputStream} and
 * {@code newOutputStream} are not used, because for selectable channels they serialize
 * reads and writes on the channel's blocking lock.
 *
 * <p>
 * Socket path permissions, readiness coordination, and cleanup of the socket file belong
 * to the caller; the channel only carries bytes.
 */
public final class UnixDomainSocketDuplexByteChannel implements DuplexByteChannel {

	private final SocketChannel socket;

	private final String description;

	private final InputStream inbound;

	private final OutputStream outbound;

	private final AtomicBoolean outputShutdown = new AtomicBoolean();

	private UnixDomainSocketDuplexByteChannel(SocketChannel socket, String description) {
		this.socket = Objects.requireNonNull(socket, "socket");
		this.description = description;
		this.inbound = new SocketInput(socket);
		this.outbound = new SocketOutput(socket);
	}

	/**
	 * Connects to a listening socket.
	 * @param path socket file path
	 * @return the connected channel
	 * @throws IOException if the connection fails
	 */
	public static UnixDomainSocketDuplexByteChannel connect(Path path) throws IOException {
		SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			socket.connect(UnixDomainSocketAddress.of(path));
		}
		catch (IOException | RuntimeException failure) {
			socket.close();
			throw failure;
		}
		return new UnixDomainSocketDuplexByteChannel(socket, "unix-domain-client[" + path + "]");
	}

	/**
	 * Accepts one connection on a listening socket.
	 * @param server a blocking {@code AF_UNIX} server channel
	 * @return the accepted channel
	 * @throws IOException if accepting fails
	 */
	public static UnixDomainSocketDuplexByteChannel accept(ServerSocketChannel server) throws IOException {
		SocketChannel socket = server.accept();
		return new UnixDomainSocketDuplexByteChannel(socket, "unix-domain-server[" + server.getLocalAddress() + "]");
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
		if (this.outputShutdown.compareAndSet(false, true) && this.socket.isOpen()) {
			this.socket.shutdownOutput();
		}
	}

	@Override
	public void close() throws IOException {
		this.outputShutdown.set(true);
		this.socket.close();
	}

	@Override
	public String describe() {
		return this.description;
	}

	private static final class SocketInput extends InputStream {

		private final SocketChannel socket;

		private SocketInput(SocketChannel socket) {
			this.socket = socket;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int count = read(one, 0, 1);
			return count < 0 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			Objects.checkFromIndexSize(offset, length, bytes.length);
			if (length == 0) {
				return 0;
			}
			return this.socket.read(ByteBuffer.wrap(bytes, offset, length));
		}

	}

	private static final class SocketOutput extends OutputStream {

		private final SocketChannel socket;

		private SocketOutput(SocketChannel socket) {
			this.socket = socket;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			Objects.checkFromIndexSize(offset, length, bytes.length);
			ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
			while (buffer.hasRemaining()) {
				this.socket.write(buffer);
			}
		}

	}

}
