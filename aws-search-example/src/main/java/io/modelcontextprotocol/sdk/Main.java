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

	// Number of search results to return (could be an arbitrary dynamic amount,
	// hardcoding for this)
	private static final int SEARCH_RESULTS = 20;

	// Arbitrary minimum score to consider when returning results
	private static final float MIN_SCORE = 0.4f;

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record McpConfig(Map<String, McpStdioServerConfig> mcpServers) {
		@JsonIgnoreProperties(ignoreUnknown = true)
		private record McpStdioServerConfig(String command, String[] args, Map<String, String> env) {
		}
	}

	public static void main(String[] args) throws IOException, URISyntaxException {
		// Load config
		var region = Region.US_WEST_2;
		var endpoint = System.getenv("OS_ENDPOINT"); // OpenSearch endpoint
		var username = System.getenv("OS_USERNAME"); // OpenSearch master username
		var password = System.getenv("OS_PASSWORD"); // OpenSearch master password
		var configPath = System.getenv("MCP_CONFIG"); // Config file path

		var configRaw = Files.readString(Path.of(configPath));
		var config = objectMapper.readValue(configRaw, McpConfig.class);

		// Create OpenSearch/Bedrock clients
		logger.info("Creating service clients");
		var openSearchClient = createClient(endpoint, username, password);
		var bedrockClient = BedrockRuntimeClient.builder().region(region).build();

		// Load proxied MCP servers
		config.mcpServers().forEach((k, server) -> {
			clientManager.addStdioServer(server.command(), server.args(), server.env());
		});

		// Start MCP server on SSE (Streamable HTTP not yet implemented in Java)
		logger.info("Starting MCP server");
		var transportProvider = HttpServletSseServerTransportProvider.builder()
			.baseUrl("http://localhost:9200")
			.messageEndpoint("/mcp/message")
			.build();
		startTomcat(transportProvider);

		// Create proxies for tools and indexes them into the vector store.
		// In a production implementation, you would want either a deployment step or a
		// control plane
		// action to preload the index so that you don't waste time indexing on every
		// start.
		var tools = createTools();
		var indexName = indexTools(openSearchClient, bedrockClient, tools);

		// Initialize the server itself
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
					// Search (non-paginated for simplicity)
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

	/**
	 * Searches the OpenSearch index for MCP tools.
	 * @param openSearchClient The OpenSearch client to use.
	 * @param bedrockClient The Bedrock client to use for similarity embeddings.
	 * @param indexName The OpenSearch index name.
	 * @param query The search query.
	 * @return The list of tools matching the query.
	 */
	private static List<McpSchema.Tool> searchTools(OpenSearchClient openSearchClient,
			BedrockRuntimeClient bedrockClient, String indexName, String query) throws IOException, URISyntaxException {

		// Generate a similarity embedding
		logger.info("Embedding query: {}", query);
		var embedding = embedText(bedrockClient, query, "search_query");

		// kNN search
		logger.info("Searching index [{}] for query: {}", indexName, query);
		var searchResponse = openSearchClient.search(request -> request.index(indexName)
			.size(SEARCH_RESULTS)
			// Use radial search to return all results above the minimum required score
			.query(q -> q.knn(knn -> knn.field("embedding").vector(embedding).minScore(MIN_SCORE))),
				ToolDocument.class);
		validateSearchResponse(searchResponse);

		// Extract the search results
		var hits = searchResponse.hits().hits();
		logger.info("Found {} documents with a max score of {}", hits.size(),
				hits.stream().map(Hit::score).filter(Objects::nonNull).max(Double::compareTo).orElse(null));

		// Return Tool instances for the search results
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

	/**
	 * Creates an index and indexes the provided MCP tools into the vector store.
	 * @param openSearchClient The OpenSearch client to use.
	 * @param bedrockClient The Bedrock client to use for similarity embeddings.
	 * @param tools The list of tools to index.
	 * @return The created index name.
	 */
	private static String indexTools(OpenSearchClient openSearchClient, BedrockRuntimeClient bedrockClient,
			List<McpServerFeatures.SyncToolSpecification> tools) throws IOException {
		var indexName = "index-0"; // Hardcoded index name

		// Delete the index if it already exists so we start clean each run
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

		// Create the kNN index
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

		// Embed each tool and index it into the vector store
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

	/**
	 * Creates the input text for generating a tool embedding.
	 * @param tool The MCP tool.
	 * @return The text to embed.
	 */
	@SuppressWarnings("unchecked")
	private static String createToolEmbeddingInput(McpSchema.Tool tool) {
		// Create a list of key-value pairs for arguments and arg descriptions
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

		// Concatenate that with the tool name and description
		return tool.name() + ": " + tool.description() + "\nParameters:\n" + String.join("\n", params);
	}

	/**
	 * Calls the embedding model to generate a similarity vector.
	 * @param bedrockClient The Bedrock client to call the model with.
	 * @param text The input text.
	 * @param type The embedding type, for if we're running this with Cohere Embed.
	 * @return The similarity vector.
	 */
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

	/**
	 * Tool data model in OpenSearch.
	 *
	 * @param embedding The similarity vector.
	 * @param name The tool name.
	 * @param description The tool description.
	 * @param inputSchema The tool input schema.
	 */
	private record ToolDocument(@JsonProperty("embedding") List<Float> embedding, @JsonProperty("name") String name,
			@JsonProperty("description") String description, @JsonProperty("inputSchema") String inputSchema) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingRequest(String inputText, int dimensions) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingResult(@JsonProperty("embedding") List<Float> embedding) {
	}

	/**
	 * Creates a list of tool specs based on the loaded remote servers.
	 * @return A list of tool specifications.
	 */
	private static List<McpServerFeatures.SyncToolSpecification> createTools() {
		var tools = clientManager.listAllTools();
		return tools.stream().map(Main::createProxyToolSpec).toList();
	}

	/**
	 * Creates a tool spec from a tool, intended for proxying calls to remote MCP servers.
	 * @param tool The tool received from the remote server from tools/list.
	 * @return A tool spec for calling the tool.
	 */
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

	/**
	 * Creates an OpenSearch client.
	 * @param endpoint Server endpoint.
	 * @param username Master user.
	 * @param password Master password.
	 * @return Client instance.
	 */
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