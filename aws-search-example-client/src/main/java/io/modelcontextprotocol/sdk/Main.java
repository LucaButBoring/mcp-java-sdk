package io.modelcontextprotocol.sdk;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        var transport = HttpClientSseClientTransport.builder("http://localhost:9200")
                .sseEndpoint("/sse")
                .build();
        var mcpClient = McpClient.sync(transport).build();
        mcpClient.initialize();

        search(mcpClient, "Please read the file from the filesystem.");
        search(mcpClient, "Please write the file to the filesystem.");
        search(mcpClient, "Please search for the file I need from the filesystem.");
        search(mcpClient, "Please search for the information I need from the internet.");

        mcpClient.closeGracefully();
    }

    private static void search(McpSyncClient mcpClient, String query) {
        var response = mcpClient.searchTools(McpSchema.SearchToolsRequest.builder()
                .query(query)
                .build());
        var formattedResponse = String.join("\n", response.tools().stream()
                .map(tool -> "\t" + tool.name() + ": " + tool.description())
                .toList());
        logger.info("Search results for [{}]:\n{}", query, formattedResponse);
    }
}