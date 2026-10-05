package io.modelcontextprotocol.transport.httpstdio.pipe;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.WriteBufferWaterMark;

/** Configuration for {@link NettyPipeChannel}. */
public final class PipeChannelConfig extends DefaultChannelConfig {

	private volatile int inboundReadChunkBytes = 16_384;

	private volatile int maxInboundQueuedBytes = 262_144;

	private volatile int maxOutboundQueuedBytes = 262_144;

	private volatile boolean closeOnInboundEof = true;

	private volatile long threadJoinTimeoutMillis = 5_000;

	public PipeChannelConfig(Channel channel) {
		super(channel);
		setWriteBufferWaterMark(new WriteBufferWaterMark(this.maxOutboundQueuedBytes / 2, this.maxOutboundQueuedBytes));
	}

	public int getInboundReadChunkBytes() {
		return this.inboundReadChunkBytes;
	}

	public PipeChannelConfig setInboundReadChunkBytes(int value) {
		this.inboundReadChunkBytes = positive(value, "inboundReadChunkBytes");
		return this;
	}

	public int getMaxInboundQueuedBytes() {
		return this.maxInboundQueuedBytes;
	}

	public PipeChannelConfig setMaxInboundQueuedBytes(int value) {
		this.maxInboundQueuedBytes = positive(value, "maxInboundQueuedBytes");
		return this;
	}

	public int getMaxOutboundQueuedBytes() {
		return this.maxOutboundQueuedBytes;
	}

	public PipeChannelConfig setMaxOutboundQueuedBytes(int value) {
		this.maxOutboundQueuedBytes = positive(value, "maxOutboundQueuedBytes");
		setWriteBufferWaterMark(new WriteBufferWaterMark(Math.max(1, value / 2), value));
		return this;
	}

	public boolean isCloseOnInboundEof() {
		return this.closeOnInboundEof;
	}

	public PipeChannelConfig setCloseOnInboundEof(boolean value) {
		this.closeOnInboundEof = value;
		return this;
	}

	public long getThreadJoinTimeoutMillis() {
		return this.threadJoinTimeoutMillis;
	}

	public PipeChannelConfig setThreadJoinTimeoutMillis(long value) {
		if (value <= 0) {
			throw new IllegalArgumentException("threadJoinTimeoutMillis must be positive");
		}
		this.threadJoinTimeoutMillis = value;
		return this;
	}

	private static int positive(int value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}

}
