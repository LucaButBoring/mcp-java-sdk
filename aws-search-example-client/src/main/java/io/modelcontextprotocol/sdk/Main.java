package io.modelcontextprotocol.sdk;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

public class Main {

	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) throws InterruptedException {
		logger.info("Creating service clients");

		var transport = HttpClientSseClientTransport.builder("http://localhost:9200").sseEndpoint("/sse").build();
		var mcpClient = McpClient.sync(transport).build();
		mcpClient.initialize();

		var region = Region.US_WEST_2;
		var bedrockClient = BedrockRuntimeClient.builder().region(region).build();

		// Set up the application and input loop
		var application = new BedrockMCPApplication(
				bedrockClient, mcpClient, List.of("us.anthropic.claude-sonnet-4-20250514-v1:0",
						"us.anthropic.claude-3-7-sonnet-20250219-v1:0", "us.anthropic.claude-3-5-sonnet-20241022-v2:0"),
				"You are a helpful assistant.");
		var scanner = new Scanner(System.in, StandardCharsets.UTF_8);

		while (scanner.hasNextLine()) {
			var line = scanner.nextLine();
			if (line.startsWith("!") && !handleCommand(line)) {
				break;
			}

			application.send(line);
		}

		mcpClient.closeGracefully();
	}

	private static boolean handleCommand(String command) {
		if (command.startsWith("!q")) {
			return false;
		}

		return true;
	}

}