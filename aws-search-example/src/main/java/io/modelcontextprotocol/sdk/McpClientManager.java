package io.modelcontextprotocol.sdk;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McpClientManager {

	private final List<McpSyncClient> clients;

	private final Map<String, McpSyncClient> toolReverseMap;

	public McpClientManager() {
		clients = new ArrayList<>();
		toolReverseMap = new HashMap<>();
	}

	public void addStdioServer(String command, String[] args, Map<String, String> env) {
		var transport = new StdioClientTransport(ServerParameters.builder(command).args(args).env(env).build());
		var client = McpClient.sync(transport).build();
		client.initialize();

		clients.add(client);
		loadTools(client);
	}

	public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
		// Get the appropriate client by tool name
		var client = Optional.ofNullable(toolReverseMap.get(request.name())).orElseThrow();
		return client.callTool(request);
	}

	private void loadTools(McpSyncClient client) {
		var tools = listTools(client);
		for (var tool : tools) {
			assert !toolReverseMap.containsKey(tool.name());
			toolReverseMap.put(tool.name(), client);
		}
	}

	/**
	 * List tools from all servers, intended to be called once when building our vector
	 * index.
	 * @return The consolidated list of tools from all servers.
	 */
	public List<McpSchema.Tool> listAllTools() {
		var tools = new ArrayList<McpSchema.Tool>();
		for (var client : clients) {
			tools.addAll(listTools(client));
		}

		return tools;
	}

	private List<McpSchema.Tool> listTools(McpSyncClient client) {
		var tools = new ArrayList<McpSchema.Tool>();
		String cursor = null;
		do {
			var toolPage = client.listTools(cursor);
			cursor = toolPage.nextCursor();

			// Assuming for this example that no servers expose tools with the same
			// names; otherwise we'd have to namespace them since we plan to index
			// this list in our vector store and will need to reverse-map when calling
			// the tool.
			tools.addAll(toolPage.tools());
		}
		while (cursor != null);

		return tools;
	}

}
