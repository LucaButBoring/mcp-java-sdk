package io.modelcontextprotocol.transport.httpstdio.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.modelcontextprotocol.transport.httpstdio.pipe.DuplexByteChannel;
import io.modelcontextprotocol.transport.httpstdio.pipe.ProcessPipeDuplexByteChannel;

/** Owns a child process used as a raw HTTP-over-stdio peer. */
public final class ChildProcessLauncher {

	private final Process process;

	private final DuplexByteChannel duplex;

	private ChildProcessLauncher(Process process, Consumer<String> stderrLineHandler) {
		this.process = process;
		this.duplex = ProcessPipeDuplexByteChannel.forChild(process);
		Thread stderr = new Thread(() -> drainStderr(process, stderrLineHandler),
				"http-stdio-child-stderr-" + process.pid());
		stderr.setDaemon(true);
		stderr.start();
	}

	public static ChildProcessLauncher launch(List<String> command, Map<String, String> environment,
			Consumer<String> stderrLineHandler) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(Objects.requireNonNull(command, "command"));
		builder.redirectInput(ProcessBuilder.Redirect.PIPE);
		builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
		builder.redirectError(ProcessBuilder.Redirect.PIPE);
		if (environment != null) {
			builder.environment().putAll(environment);
		}
		return new ChildProcessLauncher(builder.start(),
				Objects.requireNonNull(stderrLineHandler, "stderrLineHandler"));
	}

	public DuplexByteChannel duplex() {
		return this.duplex;
	}

	public Process process() {
		return this.process;
	}

	public CompletableFuture<Integer> shutdown(Duration graceful, Duration forceful) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				this.duplex.shutdownOutput();
				if (!this.process.waitFor(graceful.toMillis(), TimeUnit.MILLISECONDS)) {
					this.process.destroy();
					if (!this.process.waitFor(forceful.toMillis(), TimeUnit.MILLISECONDS)) {
						this.process.destroyForcibly();
						this.process.waitFor(forceful.toMillis(), TimeUnit.MILLISECONDS);
					}
				}
				if (this.process.isAlive()) {
					throw new IllegalStateException("Child did not exit within bounded shutdown intervals");
				}
				return this.process.exitValue();
			}
			catch (Exception failure) {
				throw new IllegalStateException("Unable to shut down child process", failure);
			}
		});
	}

	private static void drainStderr(Process process, Consumer<String> handler) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				handler.accept(line);
			}
		}
		catch (IOException failure) {
			if (process.isAlive()) {
				handler.accept("stderr-drain-error=" + failure.getMessage());
			}
		}
	}

}
