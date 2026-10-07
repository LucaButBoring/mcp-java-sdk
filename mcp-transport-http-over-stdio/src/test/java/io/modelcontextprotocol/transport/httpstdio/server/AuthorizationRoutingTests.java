package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.client.LogicalAuthorityRoutingHttpClient;
import io.modelcontextprotocol.transport.httpstdio.client.PipeRequests;
import io.modelcontextprotocol.transport.httpstdio.client.StubHttpClient;
import io.modelcontextprotocol.transport.httpstdio.http2.Http2Request;
import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.InMemoryDuplexByteChannel;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5: a host OAuth layer authorizes against a child resource server through
 * {@link LogicalAuthorityRoutingHttpClient}. The child's authority,
 * {@code child.invalid}, cannot resolve in DNS (RFC 6761), so any attempt to reach it
 * through the network would fail; the authorization server, {@code idp.example}, exists
 * only as a fake network client that records every request it sees.
 */
@Timeout(60)
class AuthorizationRoutingTests {

	private static final String ORIGIN = "https://child.invalid:8443";

	private static final String RESOURCE = ORIGIN + "/mcp";

	/** RFC 9728 section 3.1: the well-known suffix goes between authority and path. */
	private static final String METADATA_URL = ORIGIN + "/.well-known/oauth-protected-resource/mcp";

	private static final String IDP = "https://idp.example";

	private static final String TOKEN = "child-scoped-token";

	private final List<HttpRequest> pipeRequests = new CopyOnWriteArrayList<>();

	private final List<HttpRequest> networkRequests = new CopyOnWriteArrayList<>();

	private DefaultEventLoopGroup serverGroup;

	private HttpOverStdioServerTransport server;

	private HttpOverStdioClientTransport client;

	@AfterEach
	void tearDown() throws Exception {
		if (this.client != null) {
			this.client.closeGracefully().block(Duration.ofSeconds(10));
		}
		if (this.server != null) {
			this.server.closeGracefully().block(Duration.ofSeconds(10));
		}
		if (this.serverGroup != null) {
			this.serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
		}
	}

	@Test
	void hostDiscoversAndAuthorizesThroughThePipeWhileIdentityTrafficStaysOnTheNetwork() throws Exception {
		startChild(true);
		AtomicReference<String> token = new AtomicReference<>();
		AtomicInteger challenges = new AtomicInteger();
		LogicalAuthorityRoutingHttpClient routing = LogicalAuthorityRoutingHttpClient
			.builder(StubHttpClient.recording(fakeIdentityProvider(), this.networkRequests::add))
			.route(ORIGIN, StubHttpClient.recording(this.client.httpClient(), this.pipeRequests::add))
			.build();
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(ORIGIN)
			.endpoint("/mcp")
			.clientBuilder(routing.asClientBuilder())
			.httpRequestCustomizer((builder, method, uri, body, context) -> {
				if (token.get() != null) {
					builder.header("Authorization", "Bearer " + token.get());
				}
			})
			.authorizationErrorHandler((snapshot, info, context) -> {
				challenges.incrementAndGet();
				String challenge = info.headers().firstValue("www-authenticate").orElseThrow();
				return discoverAndObtainToken(routing, challenge).doOnNext(token::set).thenReturn(true);
			})
			.build();

		McpSyncClient mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
		try {
			mcp.initialize();
			assertThat(mcp.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("whoami");
			McpSchema.CallToolResult result = mcp.callTool(McpSchema.CallToolRequest.builder("whoami").build());
			assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("resource " + RESOURCE);
		}
		finally {
			mcp.closeGracefully();
		}

		assertThat(challenges).as("one 401, one discovery").hasValue(1);
		assertThat(this.pipeRequests).as("only the child's origin entered the pipe")
			.allSatisfy(request -> assertThat(request.uri().getRawAuthority()).isEqualTo("child.invalid:8443"));
		assertThat(this.pipeRequests).extracting(request -> request.uri().getPath())
			.contains("/mcp", "/.well-known/oauth-protected-resource/mcp");
		assertThat(this.pipeRequests).filteredOn(request -> request.uri().getPath().startsWith("/.well-known"))
			.as("metadata discovery carries no credentials")
			.allSatisfy(request -> assertThat(request.headers().firstValue("Authorization")).isEmpty());
		assertThat(this.networkRequests).as("identity-provider traffic never entered the pipe")
			.isNotEmpty()
			.allSatisfy(request -> assertThat(request.uri().getHost()).isEqualTo("idp.example"));
		assertThat(this.networkRequests).as("the child-scoped token never reaches the network")
			.allSatisfy(request -> assertThat(request.headers().firstValue("Authorization")).isEmpty());
	}

	@Test
	void metadataIsServedForTheAuthorityTheHostRequestedAndOnlyAtThePathAwareLocation() throws Exception {
		startChild(true);
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> metadata = PipeRequests.sendNow(this.client.httpClient(),
				get(METADATA_URL));
		assertThat(metadata.statusCode()).isEqualTo(200);
		Map<String, Object> document = json(metadata).block(Duration.ofSeconds(5));
		assertThat(document.get("resource")).as("RFC 9728 3.3: identical to the identifier the URL was built from")
			.isEqualTo(RESOURCE);

		HttpResponse<Flow.Publisher<List<ByteBuffer>>> root = PipeRequests.sendNow(this.client.httpClient(),
				get(ORIGIN + "/.well-known/oauth-protected-resource"));
		assertThat(root.statusCode()).as("the resource is /mcp, so the root location is not served").isEqualTo(404);

		HttpResponse<Flow.Publisher<List<ByteBuffer>>> otherAuthority = PipeRequests.sendNow(this.client.httpClient(),
				get("https://elsewhere.invalid:8443/.well-known/oauth-protected-resource/mcp"));
		assertThat(otherAuthority.statusCode()).as("the child serves only its configured authority").isEqualTo(421);
	}

	@Test
	void aProtectedEndpointChallengesBeforeAnyHandlerIsInstalled() throws Exception {
		startChild(false);
		HttpRequest unauthenticated = PipeRequests.request("POST", RESOURCE, Map.of("Content-Type", "application/json"),
				"{}");
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> challenged = PipeRequests.sendNow(this.client.httpClient(),
				unauthenticated);
		assertThat(challenged.statusCode()).isEqualTo(401);
		assertThat(challenged.headers().firstValue("www-authenticate"))
			.hasValue("Bearer resource_metadata=\"" + METADATA_URL + "\"");

		HttpResponse<Flow.Publisher<List<ByteBuffer>>> notReady = PipeRequests.sendNow(this.client.httpClient(),
				PipeRequests.request("POST", RESOURCE,
						Map.of("Content-Type", "application/json", "Authorization", "Bearer " + TOKEN), "{}"));
		assertThat(notReady.statusCode()).as("authorized, but no handler yet").isEqualTo(503);
	}

	/** RFC 9728 section 5: challenge-directed discovery, then exact resource equality. */
	private Mono<String> discoverAndObtainToken(HttpClient routing, String challenge) {
		Matcher matcher = Pattern.compile("resource_metadata=\"([^\"]+)\"").matcher(challenge);
		assertThat(matcher.find()).as("challenge names its metadata: " + challenge).isTrue();
		String metadataUrl = matcher.group(1);
		assertThat(metadataUrl).isEqualTo(METADATA_URL);
		return PipeRequests.send(routing, get(metadataUrl))
			.flatMap(AuthorizationRoutingTests::json)
			.flatMap(metadata -> {
				assertThat(metadata.get("resource")).isEqualTo(RESOURCE);
				@SuppressWarnings("unchecked")
				String issuer = ((List<String>) metadata.get("authorization_servers")).get(0);
				return PipeRequests.send(routing, get(issuer + "/.well-known/oauth-authorization-server"));
			})
			.flatMap(AuthorizationRoutingTests::json)
			.flatMap(serverMetadata -> PipeRequests.send(routing,
					PipeRequests.request("POST", (String) serverMetadata.get("token_endpoint"),
							Map.of("Content-Type", "application/x-www-form-urlencoded"),
							"grant_type=client_credentials&resource=" + RESOURCE)))
			.flatMap(AuthorizationRoutingTests::json)
			.map(response -> (String) response.get("access_token"));
	}

	private void startChild(boolean installHandler) throws Exception {
		DuplexByteChannel[] pair = InMemoryDuplexByteChannel.pair(64 * 1024);
		this.serverGroup = new DefaultEventLoopGroup();
		this.server = HttpOverStdioServerTransport.builder(pair[1])
			.eventLoopGroup(this.serverGroup)
			// The SDK client still speaks the initialize lifecycle; the binding is not
			// what this test is about.
			.binding(null)
			.allowedAuthorities(List.of("child.invalid:8443"))
			.contextExtractor(request -> McpTransportContext.create(Map.of("resource", resourceOf(request))))
			.authorizer(AuthorizationRoutingTests::requireToken)
			.applicationRoute((request, writer) -> {
				String path = request.path();
				if (!"GET".equals(request.method()) || !"/.well-known/oauth-protected-resource/mcp".equals(path)) {
					writer.headers("404", new DefaultHttp2Headers());
					writer.end();
					return;
				}
				String body = "{\"resource\":\"" + resourceOf(request) + "\",\"authorization_servers\":[\"" + IDP
						+ "\"]}";
				writer.headers("200", new DefaultHttp2Headers().add("content-type", "application/json"));
				writer.data(Unpooled.copiedBuffer(body, StandardCharsets.UTF_8), true);
			})
			.build();
		if (!installHandler) {
			this.client = HttpOverStdioClientTransport.connect(pair[0]).get(10, TimeUnit.SECONDS);
			return;
		}
		McpStatelessSyncServer mcp = McpServer.sync(this.server)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(McpStatelessServerFeatures.SyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder("whoami", Map.of("type", "object")).build())
				.callHandler((context, request) -> McpSchema.CallToolResult.builder()
					.content(List.of(McpSchema.TextContent.builder("resource " + context.get("resource")).build()))
					.build())
				.build())
			.build();
		assertThat(mcp).isNotNull();
		this.client = HttpOverStdioClientTransport.connect(pair[0]).get(10, TimeUnit.SECONDS);
	}

	/**
	 * The child derives its canonical resource from :scheme and :authority (memo section
	 * 5).
	 */
	private static String resourceOf(Http2Request request) {
		return request.headers().scheme() + "://" + request.headers().authority() + "/mcp";
	}

	private static Optional<StatelessHttpResponse> requireToken(Http2Request request, McpTransportContext context) {
		CharSequence authorization = request.headers().get("authorization");
		if (authorization != null && ("Bearer " + TOKEN).contentEquals(authorization)) {
			return Optional.empty();
		}
		String metadata = request.headers().scheme() + "://" + request.headers().authority()
				+ "/.well-known/oauth-protected-resource/mcp";
		return Optional.of(new StatelessHttpResponse(401,
				Map.of("WWW-Authenticate", List.of("Bearer resource_metadata=\"" + metadata + "\"")),
				new StatelessHttpResponse.Empty()));
	}

	private static HttpClient fakeIdentityProvider() {
		return new StubHttpClient(request -> {
			String path = request.uri().getPath();
			if (!"idp.example".equals(request.uri().getHost())) {
				throw new IllegalStateException("network saw an unexpected host: " + request.uri());
			}
			if ("/.well-known/oauth-authorization-server".equals(path)) {
				return StubHttpClient.Reply.of(200,
						"{\"issuer\":\"" + IDP + "\",\"token_endpoint\":\"" + IDP + "/token\"}", "Content-Type",
						"application/json");
			}
			if ("/token".equals(path)) {
				return StubHttpClient.Reply.of(200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\"}",
						"Content-Type", "application/json");
			}
			throw new IllegalStateException("unexpected identity-provider request: " + request.uri());
		});
	}

	private static HttpRequest get(String url) {
		return PipeRequests.request("GET", url, Map.of(), null);
	}

	/** Non-blocking: the body may arrive on the event loop the caller is running on. */
	private static Mono<Map<String, Object>> json(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
		return PipeRequests.body(response).map(text -> {
			try {
				return McpJsonDefaults.getMapper().readValue(text, new TypeRef<Map<String, Object>>() {
				});
			}
			catch (Exception exception) {
				throw new IllegalStateException("not JSON: " + text, exception);
			}
		});
	}

}
