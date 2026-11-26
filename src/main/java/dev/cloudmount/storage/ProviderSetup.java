package dev.cloudmount.storage;

import com.fasterxml.jackson.databind.*;
import dev.cloudmount.config.*;
import dev.cloudmount.rclone.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Drives rclone's own noninteractive configuration state machine over private authenticated RC.
 */
public final class ProviderSetup {
    private final ObjectMapper json = new ObjectMapper();
    private final CommandRunner runner = new CommandRunner();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
        var p = process.get();
        if (p != null) p.destroy();
    }

    public void configure(CloudAccount account, AppPaths paths, String binary, Map<String, String> supplied,
                          Function<Question, String> ask, Consumer<URI> openBrowser) throws Exception {
        configureBackend(account.provider().backend(), account.remote(), paths, binary, supplied, ask, openBrowser);
    }

    public void configureBackend(String backend, String remote, AppPaths paths, String binary, Map<String, String> supplied,
                                 Function<Question, String> ask, Consumer<URI> openBrowser) throws Exception {
        cancelled.set(false);
        PrivateFiles.directory(paths.configDir());
        Path temporary = Files.createTempDirectory(paths.configDir(), ".setup-");
        Process server = null;
        try {
            Path candidate = temporary.resolve("rclone.conf"), template = temporary.resolve("oauth-response.html");
            PrivateFiles.write(candidate, "");
            try (var stream = getClass().getResourceAsStream("/dev/cloudmount/oauth-response.html")) {
                PrivateFiles.write(template, new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            int port;
            try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            String password = UUID.randomUUID().toString() + UUID.randomUUID();
            server = runner.start(List.of(binary, "rcd", "--rc-addr", "127.0.0.1:" + port, "--config", candidate.toString(), "--log-level", "ERROR"), Map.of("RCLONE_RC_USER", "cloudmount", "RCLONE_RC_PASS", password));
            if (!process.compareAndSet(null, server)) {
                server.destroy();
                throw new IOException("A connection setup is already running.");
            }
            Process owned = server;
            Thread.ofVirtual().start(() -> {
                try {
                    owned.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            });
            RcClient rc = new RcClient(port, password);
            long ready = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean online = false;
            while (System.nanoTime() < ready) {
                check();
                try {
                    rc.call("core/pid");
                    online = true;
                    break;
                } catch (IOException e) {
                    Thread.sleep(100);
                }
            }
            if (!online) throw new IOException("The connection setup engine could not start.");
            var parameters = new LinkedHashMap<>(supplied);
            parameters.put("config_template_file", template.toString());
            parameters.put("config_auth_no_browser", "true");
            parameters.put("cloudmount_cache_id", UUID.randomUUID().toString());
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("nonInteractive", true);
            options.put("obscure", true);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("name", "cloudmount");
            input.put("type", backend);
            input.put("parameters", parameters);
            input.put("opt", options);
            String endpoint = "config/create";
            for (int step = 0; step < 100; step++) {
                JsonNode result = job(rc, endpoint, input, openBrowser);
                String error = result.path("Error").asText("");
                if (!error.isBlank())
                    throw new IOException("The provider rejected these settings. Review the account details and try again.");
                String state = result.path("State").asText("");
                if (state.isBlank()) break;
                JsonNode opt = result.path("Option");
                String name = opt.path("Name").asText();
                String answer;
                if (name.equals("config_is_local")) answer = "true";
                else {
                    var choices = new ArrayList<Choice>();
                    for (JsonNode choice : opt.path("Examples"))
                        choices.add(new Choice(choice.path("Value").asText(), choice.path("Help").asText(choice.path("Value").asText())));
                    answer = ask.apply(new Question(name, opt.path("Help").asText(), opt.path("Default").asText(""), opt.path("IsPassword").asBoolean(), opt.path("Required").asBoolean(), opt.path("Exclusive").asBoolean(), List.copyOf(choices)));
                    if (answer == null) throw new CancellationException("Connection setup cancelled.");
                }
                options = new LinkedHashMap<>();
                options.put("nonInteractive", true);
                options.put("continue", true);
                options.put("state", state);
                options.put("result", answer);
                input = new LinkedHashMap<>();
                input.put("name", "cloudmount");
                input.put("parameters", parameters);
                input.put("opt", options);
                endpoint = "config/update";
                if (step == 99)
                    throw new IOException("The provider setup did not finish. Review its settings and retry.");
            }
            // A real directory read verifies access, including providers that have no quota endpoint.
            job(rc, "operations/list", Map.of("fs", remote, "remote", "", "opt", Map.of("dirsOnly", true, "noModTime", true)), openBrowser);
            check();
            // The setup server must stop before committing, so later token writes cannot target a removed staging file.
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS))
                throw new IOException("Setup engine is still stopping. Retry shortly.");
            String config = Files.readString(candidate);
            if (!config.contains("cloudmount_cache_id")) {
                config = config + "\ncloudmount_cache_id = " + parameters.get("cloudmount_cache_id") + "\n";
                PrivateFiles.write(candidate, config);
            }
            check();
            Files.move(candidate, paths.credentials(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (server != null) {
                server.destroy();
                if (!server.waitFor(3, TimeUnit.SECONDS)) server.destroyForcibly();
                process.compareAndSet(server, null);
            }
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private JsonNode job(RcClient rc, String endpoint, Map<String, ?> input, Consumer<URI> open) throws Exception {
        check();
        var request = new LinkedHashMap<String, Object>(input);
        request.put("_async", true);
        long id = rc.call(endpoint, json.writeValueAsString(request)).path("jobid").asLong(-1);
        if (id < 0) throw new IOException("The setup engine did not return a job ID.");
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        String lastUrl = "";
        while (System.nanoTime() < deadline) {
            check();
            var status = rc.call("job/status", json.writeValueAsString(Map.of("jobid", id)));
            if (status.path("finished").asBoolean()) {
                if (!status.path("success").asBoolean())
                    throw new IOException("Provider connection failed. Check credentials, endpoint, access permissions and browser consent, then retry.");
                return status.path("output");
            }
            var oauth = rc.call("config/oauthstatus");
            String url = oauth.path("authUrl").asText("");
            if (!url.isBlank() && !url.equals(lastUrl)) {
                URI uri = URI.create(url);
                if (!"http".equals(uri.getScheme()) || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())) || uri.getPort() != 53682 || !"/auth".equals(uri.getPath()))
                    throw new IOException("The provider returned an unexpected local authorization address.");
                lastUrl = url;
                open.accept(uri);
            }
            Thread.sleep(300);
        }
        throw new IOException("Provider sign-in timed out. Retry and complete the browser steps within five minutes.");
    }

    private void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("Connection setup cancelled.");
    }

    public record Question(String name, String help, String value, boolean password, boolean required,
                           boolean exclusive, List<Choice> choices) {
    }

    public record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
