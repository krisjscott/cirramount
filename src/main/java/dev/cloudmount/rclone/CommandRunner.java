package dev.cloudmount.rclone;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class CommandRunner {
    public static void requireSuccess(Result result, String action) throws IOException {
        if (result.exitCode != 0) throw new IOException(action + " failed. " + redact(result.output));
    }

    public static String redact(String value) {
        return value.replaceAll("(?i)(access_token|refresh_token|client_secret|secret_access_key|session_token|access_key_id|key_file_pass|authorization|password|pass)([\\\"\\s:=]+)[^\\s,}\\\"]+", "$1$2[redacted]")
                .replaceAll("http://(?:127\\.0\\.0\\.1|localhost):53682/auth\\?[^\\s]+", "[browser sign-in link]");
    }

    public Process start(List<String> args, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        // Do not inherit another application's rclone remotes, passwords or overrides.
        builder.environment().keySet().removeIf(key -> key.startsWith("RCLONE_"));
        builder.environment().putAll(env);
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }

    public Result run(List<String> args, Map<String, String> env, Duration timeout) throws Exception {
        return collect(start(args, env), timeout, line -> {
        });
    }

    public Result collect(Process process, Duration timeout, Consumer<String> lines) throws Exception {
        var output = new StringBuilder();
        try (var readerPool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> reader = readerPool.submit(() -> {
                try (var in = process.inputReader()) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                            if (output.length() > 131072) output.delete(0, output.length() - 65536);
                        }
                        lines.accept(line);
                    }
                } catch (IOException e) {
                    if (process.isAlive()) throw new UncheckedIOException(e);
                }
            });
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    throw new IOException("rclone timed out. Check your connection and try again.");
                reader.get(5, TimeUnit.SECONDS);
                return new Result(process.exitValue(), output.toString());
            } finally {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                }
            }
        }
    }

    public record Result(int exitCode, String output) {
    }
}
