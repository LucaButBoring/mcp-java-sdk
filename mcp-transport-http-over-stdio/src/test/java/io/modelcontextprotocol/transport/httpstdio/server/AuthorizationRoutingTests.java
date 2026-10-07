package io.modelcontextprotocol.transport.httpstdio.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.http.McpHttpExchange;
import io.modelcontextprotocol.client.transport.http.McpHttpHeaders;
import io.modelcontextprotocol.client.transport.http.McpHttpRequest;
import io.modelcontextprotocol.client.transport.http.McpHttpResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.http.StatelessHttpResponse;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.transport.httpstdio.client.HttpOverStdioClientTransport;
import io.modelcontextprotocol.transport.httpstdio.client.LogicalAuthorityRoutingExchange;
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
 * {@link LogicalAuthorityRoutingExchange}. The child's authority, {@code child.invalid},
 * cannot resolve in DNS (RFC 6761), so any attempt to reach it through the network would
 * fail; the authorization server, {@code idp.example}, exists only as a fake network
 * exchange that records every request it sees.
 */
@Timeout(60)
class AuthorizationRoutingTests {

	private static final String ORIGIN = "https://child.invalid:8443";

	private static final String RESOURCE = ORIGIN + "/mcp";

	/** RFC 9728 section 3.1: the well-known suffix goes between authority and path. */
	private static final String METADATA_URL = ORIGIN + "/.well-known/oauth-protected-resource/mcp";

	private static final String IDP = "https://idp.example";

	private static final String TOKEN = "child-scoped-token";

	private final List<McpHttpRequest> pipeRequests = new CopyOnWriteArrayList<>();

	private final List<McpHttpRequest> networkRequests = new CopyOnWriteArrayList<>();

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
		LogicalAuthorityRoutingExchange routing = LogicalAuthorityRoutingExchange.builder(fakeIdentityProvider())
			.route(ORIGIN, recording(this.client.exchange(), this.pipeRequests))
			.build();
		McpHttpExchange authorizing = (request, context) -> routing.exchange(
				token.get() == null ? request : withHeader(request, "Authorization", "Bearer " + token.get()), context);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(ORIGIN)
			.endpoint("/mcp")
			.httpExchange(authorizing)
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
		McpHttpResponse metadata = this.client.exchange()
			.exchange(get(METADATA_URL), McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		assertThat(metadata.statusCode()).isEqualTo(200);
		Map<String, Object> document = json(metadata).block(Duration.ofSeconds(5));
		assertThat(document.get("resource")).as("RFC 9728 3.3: identical to the identifier the URL was built from")
			.isEqualTo(RESOURCE);

		McpHttpResponse root = this.client.exchange()
			.exchange(get(ORIGIN + "/.well-known/oauth-protected-resource"), McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		assertThat(root.statusCode()).as("the resource is /mcp, so the root location is not served").isEqualTo(404);

		McpHttpResponse otherAuthority = this.client.exchange()
			.exchange(get("https://elsewhere.invalid:8443/.well-known/oauth-protected-resource/mcp"),
					McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		assertThat(otherAuthority.statusCode()).as("the child serves only its configured authority").isEqualTo(421);
	}

	@Test
	void aProtectedEndpointChallengesBeforeAnyHandlerIsInstalled() throws Exception {
		startChild(false);
		McpHttpRequest unauthenticated = McpHttpRequest.builder()
			.method("POST")
			.uri(URI.create(RESOURCE))
			.headers(McpHttpHeaders.builder().add("Content-Type", "application/json").build())
			.body("{}")
			.build();
		McpHttpResponse challenged = this.client.exchange()
			.exchange(unauthenticated, McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		assertThat(challenged.statusCode()).isEqualTo(401);
		assertThat(challenged.headers().firstValue("www-authenticate"))
			.hasValue("Bearer resource_metadata=\"" + METADATA_URL + "\"");

		McpHttpResponse notReady = this.client.exchange()
			.exchange(withHeader(unauthenticated, "Authorization", "Bearer " + TOKEN), McpTransportContext.EMPTY)
			.block(Duration.ofSeconds(5));
		assertThat(notReady.statusCode()).as("authorized, but no handler yet").isEqualTo(503);
	}

	/** RFC 9728 section 5: challenge-directed discovery, then exact resource equality. */
	private Mono<String> discoverAndObtainToken(LogicalAuthorityRoutingExchange routing, String challenge) {
		Matcher matcher = Pattern.compile("resource_metadata=\"([^\"]+)\"").matcher(challenge);
		assertThat(matcher.find()).as("challenge names its metadata: " + challenge).isTrue();
		String metadataUrl = matcher.group(1);
		assertThat(metadataUrl).isEqualTo(METADATA_URL);
		return routing.exchange(get(metadataUrl), McpTransportContext.EMPTY)
			.flatMap(AuthorizationRoutingTests::json)
			.flatMap(metadata -> {
				assertThat(metadata.get("resource")).isEqualTo(RESOURCE);
				@SuppressWarnings("unchecked")
				String issuer = ((List<String>) metadata.get("authorization_servers")).get(0);
				return routing.exchange(get(issuer + "/.well-known/oauth-authorization-server"),
						McpTransportContext.EMPTY);
			})
			.flatMap(AuthorizationRoutingTests::json)
			.flatMap(serverMetadata -> routing.exchange(McpHttpRequest.builder()
				.method("POST")
				.uri(URI.create((String) serverMetadata.get("token_endpoint")))
				.headers(McpHttpHeaders.builder().add("Content-Type", "application/x-www-form-urlencoded").build())
				.body("grant_type=client_credentials&resource=" + RESOURCE)
				.build(), McpTransportContext.EMPTY))
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

	private McpHttpExchange fakeIdentityProvider() {
		return (request, context) -> {
			this.networkRequests.add(request);
			String path = request.uri().getPath();
			if (!"idp.example".equals(request.uri().getHost())) {
				return Mono.error(new IllegalStateException("network saw an unexpected host: " + request.uri()));
			}
			if ("/.well-known/oauth-authorization-server".equals(path)) {
				return Mono.just(
						jsonResponse(request, "{\"issuer\":\"" + IDP + "\",\"token_endpoint\":\"" + IDP + "/token\"}"));
			}
			if ("/token".equals(path)) {
				return Mono
					.just(jsonResponse(request, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\"}"));
			}
			return Mono.error(new IllegalStateException("unexpected identity-provider request: " + request.uri()));
		};
	}

	private static McpHttpExchange recording(McpHttpExchange delegate, List<McpHttpRequest> seen) {
		return (request, context) -> {
			seen.add(request);
			return delegate.exchange(request, context);
		};
	}

	private static McpHttpRequest withHeader(McpHttpRequest request, String name, String value) {
		McpHttpRequest.Builder builder = McpHttpRequest.builder()
			.method(request.method())
			.uri(request.uri())
			.headers(McpHttpHeaders.builder().addAll(request.headers().map()).add(name, value).build());
		request.body().ifPresent(builder::body);
		return builder.build();
	}

	private static McpHttpRequest get(String url) {
		return McpHttpRequest.builder()
			.method("GET")
			.uri(URI.create(url))
			.headers(McpHttpHeaders.builder().build())
			.build();
	}

	private static McpHttpResponse jsonResponse(McpHttpRequest request, String body) {
		return new McpHttpResponse(200, McpHttpHeaders.builder().add("Content-Type", "application/json").build(),
				JdkFlowAdapter.publisherToFlowPublisher(
						Flux.just(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))))),
				request, "HTTP_1_1");
	}

	/** Non-blocking: the body may arrive on the event loop the caller is running on. */
	private static Mono<Map<String, Object>> json(McpHttpResponse response) {
		return JdkFlowAdapter.flowPublisherToFlux(response.body())
			.flatMapIterable(values -> values)
			.map(buffer -> StandardCharsets.UTF_8.decode(buffer).toString())
			.collect(Collectors.joining())
			.map(text -> {
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
