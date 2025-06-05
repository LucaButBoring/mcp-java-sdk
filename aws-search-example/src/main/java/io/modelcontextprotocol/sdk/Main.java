package io.modelcontextprotocol.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Main {

	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private static final McpClientManager clientManager = new McpClientManager();

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record McpConfig(Map<String, McpStdioServerConfig> mcpServers) {
		@JsonIgnoreProperties(ignoreUnknown = true)
		private record McpStdioServerConfig(String command, String[] args, Map<String, String> env) {
		}
	}

	public static void main(String[] args) throws IOException, URISyntaxException {
		var region = Region.US_WEST_2;
		var endpoint = System.getenv("OS_ENDPOINT");
		var username = System.getenv("OS_USERNAME");
		var password = System.getenv("OS_PASSWORD");
		var configPath = System.getenv("MCP_CONFIG");

		var configRaw = Files.readString(Path.of(configPath));
		var config = objectMapper.readValue(configRaw, McpConfig.class);

		logger.info("Creating service clients");
		var openSearchClient = createClient(endpoint, username, password);
		var bedrockClient = BedrockRuntimeClient.builder().region(region).build();

		config.mcpServers().forEach((k, server) -> {
			clientManager.addStdioServer(server.command(), server.args(), server.env());
		});

		logger.info("Starting MCP server");
		var transportProvider = HttpServletSseServerTransportProvider.builder()
			.baseUrl("http://localhost:9200")
			.messageEndpoint("/mcp/message")
			.build();
		startTomcat(transportProvider);

		var tools = createTools();
		var indexName = indexTools(openSearchClient, bedrockClient, tools);

		McpServer.sync(transportProvider)
			.serverInfo("aws-search-example", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder()
				// Enable the search capability
				.tools(McpSchema.ServerCapabilities.ToolCapabilities.builder().search(true).build())
				.build())
			.tools(tools)
			// Create the search handler function
			.toolSearchHandler((e, request) -> {
				try {
					return McpSchema.SearchToolsResult.builder()
						.tools(searchTools(openSearchClient, bedrockClient, indexName, request.query()))
						.build();
				}
				catch (IOException | URISyntaxException ex) {
					throw new RuntimeException(ex);
				}
			})
			.build();
		logger.info("MCP server now ready");
	}

	private static final int SEARCH_RESULTS = 20;

	private static final float MIN_SCORE = 0.4f;

	private static List<McpSchema.Tool> searchTools(OpenSearchClient openSearchClient,
			BedrockRuntimeClient bedrockClient, String indexName, String query) throws IOException, URISyntaxException {

		// Generate an embedding
		logger.info("Embedding query: {}", query);
		var embedding = embedText(bedrockClient, query, "search_query");

		// kNN search
		logger.info("Searching index [{}] for query: {}", indexName, query);
		var searchResponse = openSearchClient.search(
				request -> request.index(indexName)
					.size(SEARCH_RESULTS)
					.query(q -> q.knn(knn -> knn.field("embedding").vector(embedding).minScore(MIN_SCORE))),
				ToolDocument.class);
		validateSearchResponse(searchResponse);

		var hits = searchResponse.hits().hits();
		logger.info("Found {} documents with a max score of {}", hits.size(),
				hits.stream().map(Hit::score).filter(Objects::nonNull).max(Double::compareTo).orElse(null));

		return hits.stream()
			.map(Hit::source)
			.filter(Objects::nonNull)
			.map(toolDocument -> new McpSchema.Tool(toolDocument.name(), toolDocument.description(),
					toolDocument.inputSchema()))
			.toList();
	}

	private static void validateSearchResponse(final SearchResponse<?> response) {
		if (response == null) {
			throw new RuntimeException("Search response was null");
		}

		if (response.timedOut()) {
			throw new RuntimeException("Search timed out");
		}

		if (Boolean.TRUE.equals(response.terminatedEarly())) {
			throw new RuntimeException("Search terminated early");
		}
	}

	private static String indexTools(OpenSearchClient openSearchClient, BedrockRuntimeClient bedrockClient,
			List<McpServerFeatures.SyncToolSpecification> tools) throws IOException {
		var indexName = "index-0";

		logger.info("Deleting index [{}]", indexName);
		try {
			openSearchClient.indices().delete(request -> request.index(indexName));
			logger.info("Deleted index [{}]", indexName);
		}
		catch (final OpenSearchException e) {
			if (!e.getMessage().contains("[index_not_found_exception]")) {
				throw e;
			}
		}

		logger.info("Creating index [{}]", indexName);
		try {
			openSearchClient.indices()
				.create(request -> request.index(indexName)
					.settings(settings -> settings.knn(true))
					.mappings(mappings -> mappings.properties(Map
						.of("embedding", Property.of(prop -> prop.knnVector(knnVector -> knnVector.dimension(256)
							.method(method -> method.name("hnsw")
								.engine("faiss")
								.spaceType("cosinesimil")
								.parameters(Map.of("ef_construction", JsonData.of(128), "m", JsonData.of(24))))))))));
			logger.info("Created index [{}]", indexName);
		}
		catch (final OpenSearchException e) {
			if (!e.getMessage().contains("[resource_already_exists_exception]")) {
				throw e;
			}
		}

		logger.info("Indexing tools");
		for (var toolSpec : tools) {
			var tool = toolSpec.tool();

			// Generate an embedding
			var embeddingInputText = createToolEmbeddingInput(tool);
			logger.info("Embedding tool: [{}]", embeddingInputText);
			var embeddingResponse = embedText(bedrockClient, embeddingInputText, "search_document");

			// Upsert the tool document
			logger.info("Indexing tool into index [{}]: {}", indexName, tool.name());
			var inputSchema = objectMapper.writeValueAsString(tool.inputSchema());
			openSearchClient.index(request -> request.index(indexName)
				.document(new ToolDocument(embeddingResponse, tool.name(), tool.description(), inputSchema))
				.refresh(Refresh.True));
		}
		logger.info("Indexed tools");

		return indexName;
	}

	@SuppressWarnings("unchecked")
	private static String createToolEmbeddingInput(McpSchema.Tool tool) {
		var params = tool.inputSchema().properties().entrySet().stream().map(e -> {
			var description = (String) ((Map<String, Object>) e.getValue()).get("description");
			if (description == null) {
				return null;
			}

			return e.getKey() + ": " + description;
		}).filter(Objects::nonNull).toList();
		if (params.isEmpty()) {
			return tool.name() + ": " + tool.description();
		}

		return tool.name() + ": " + tool.description() + "\nParameters:\n" + String.join("\n", params);
	}

	private static List<Float> embedText(BedrockRuntimeClient bedrockClient, String text, String type)
			throws JsonProcessingException {
		var embeddingRequest = new EmbeddingRequest(text, 256);
		var embeddingRequestRaw = objectMapper.writeValueAsString(embeddingRequest);
		var embeddingResponse = bedrockClient.invokeModel(request -> request.modelId("amazon.titan-embed-text-v2:0")
			.body(SdkBytes.fromUtf8String(embeddingRequestRaw))
			.accept("*/*")
			.contentType("application/json")
			.build());
		return objectMapper.readValue(embeddingResponse.body().asUtf8String(), EmbeddingResult.class).embedding();
	}

	private record ToolDocument(@JsonProperty("embedding") List<Float> embedding, @JsonProperty("name") String name,
			@JsonProperty("description") String description, @JsonProperty("inputSchema") String inputSchema) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingRequest(String inputText, int dimensions) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingResult(@JsonProperty("embedding") List<Float> embedding) {
	}

	private static List<McpServerFeatures.SyncToolSpecification> createTools() {
		var tools = clientManager.listAllTools();
		return tools.stream().map(Main::createProxyToolSpec).toList();
	}

	private static McpServerFeatures.SyncToolSpecification createProxyToolSpec(McpSchema.Tool tool) {
		return new McpServerFeatures.SyncToolSpecification(tool, (ex, params) -> {
			try {
				return clientManager.callTool(new McpSchema.CallToolRequest(tool.name(), params));
			}
			catch (final Exception e) {
				logger.error(e.getMessage(), e);
				throw e;
			}
		});
	}

	private static OpenSearchClient createClient(String endpoint, String username, String password)
			throws URISyntaxException {
		final HttpHost host = HttpHost.create(endpoint);

		final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(new AuthScope(host),
				new UsernamePasswordCredentials(username, password.toCharArray()));

		final OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host)
			.setHttpClientConfigCallback(
					httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
			.build();
		return new OpenSearchClient(transport);
	}

	private static void startTomcat(Servlet transportProvider) {
		var tomcat = new Tomcat();
		tomcat.setPort(9200);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(transportProvider);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(3000);

		try {
			tomcat.start();
			assert tomcat.getServer().getState().equals(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

}