package io.modelcontextprotocol.transport.httpstdio.server;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.modelcontextprotocol.AbstractStatelessIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Runs the shared stateless integration suite from {@code mcp-test} over the HTTP/2 pipe:
 * the unmodified SDK client and stateless server, connected only by
 * {@link HttpOverStdioClientTransport} and {@link HttpOverStdioServerTransport}.
 *
 * <p>
 * The suite exercises the pre-2026 lifecycle ({@code initialize}, no request-metadata
 * headers), which is what SDK core speaks today, so the 2026-07-28 binding is disabled.
 * The suite therefore proves the pipe carries the existing SDK end to end. The 2026
 * binding is covered by {@link HttpOverStdioServerTransportTests} and the HTTP binding
 * contract tests.
 */
@Timeout(30)
class HttpOverStdioStatelessSharedIntegrationTests extends AbstractStatelessIntegrationTests {

	private static final String ENDPOINT = "/mcp/message";

	private HttpOverStdioServerTransport serverTransport;

	private HttpOverStdioClientTransport clientTransport;

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("pipe"));
	}

	@BeforeEach
	void before() throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(64 * 1024);
		this.serverTransport = HttpOverStdioServerTransport.builder(pair[1])
			.endpoint(ENDPOINT)
			.binding(null)
			.contextExtractor(request -> McpTransportContext.create(Map.of("important", "value")))
			.build();
		this.clientTransport = HttpOverStdioClientTransport.connect(pair[0]).get(10, TimeUnit.SECONDS);
		prepareClients(0, ENDPOINT);
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		this.clientBuilders.put("pipe",
				McpClient
					.sync(HttpClientStreamableHttpTransport.builder("http://pipe")
						.endpoint(mcpEndpoint)
						.httpExchange(this.clientTransport.exchange())
						.build())
					.initializationTimeout(Duration.ofSeconds(20))
					.requestTimeout(Duration.ofSeconds(20)));
	}

	@Override
	protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
		return McpServer.async(this.serverTransport);
	}

	@Override
	protected StatelessSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(this.serverTransport);
	}

	@AfterEach
	void after() {
		if (this.clientTransport != null) {
			this.clientTransport.closeGracefully().block(Duration.ofSeconds(10));
		}
		if (this.serverTransport != null) {
			this.serverTransport.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

}
