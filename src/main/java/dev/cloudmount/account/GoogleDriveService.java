package dev.cloudmount.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudmount.config.*;
import dev.cloudmount.rclone.CommandRunner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class GoogleDriveService {
    private static final Pattern AUTH_LINK = Pattern.compile("http://(?:127\\.0\\.0\\.1|localhost):53682/auth\\?[^\\s<>]+");
    private final AppPaths paths;
    private final CommandRunner runner;
    private final AtomicReference<Process> login = new AtomicReference<>();
    private final ObjectMapper json = new ObjectMapper();

    public GoogleDriveService(AppPaths paths, CommandRunner runner) {
        this.paths = paths;
        this.runner = runner;
    }

    public static String extractToken(String output) throws IOException {
        ObjectMapper parser = new ObjectMapper();
        for (String line : output.split("\\R")) {
            if (!line.strip().startsWith("{")) continue;
            try {
                var node = parser.readTree(line);
                if (node.hasNonNull("access_token") && node.hasNonNull("refresh_token"))
                    return parser.writeValueAsString(node);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        throw new IOException("rclone completed without a usable Google token. Please sign in again.");
    }

    private static String singleLine(String value, String label) {
        if (value == null || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException(label + " must be a single line.");
        return value.strip();
    }

    public boolean hasAccount() {
        try {
            return Files.readString(paths.credentials()).contains("token = ");
        } catch (IOException e) {
            return false;
        }
    }

    public void login(String binary, String clientId, String secret, Consumer<URI> browser) throws Exception {
        clientId = singleLine(clientId, "Google client ID");
        secret = singleLine(secret, "Google client secret");
        if (!clientId.endsWith(".apps.googleusercontent.com") || secret.isBlank())
            throw new IllegalArgumentException("Enter a Google Desktop app client ID and client secret.");
        if (login.get() != null) throw new IOException("Sign-in is already running.");
        PrivateFiles.directory(paths.configDir());
        Path responseTemplate = paths.configDir().resolve("oauth-response.html");
        try (var resource = GoogleDriveService.class.getResourceAsStream("/dev/cloudmount/oauth-response.html")) {
            if (resource == null)
                throw new IOException("The Google sign-in page is missing. Rebuild CloudMount and retry.");
            PrivateFiles.write(responseTemplate, new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        // Backend environment options are consumed by rclone's ConfigMap; secrets never enter argv.
        Process process = runner.start(List.of(binary, "authorize", "drive", "--auth-no-open-browser", "--template", responseTemplate.toString(), "--config", paths.credentials().toString()),
                Map.of("RCLONE_DRIVE_CLIENT_ID", clientId, "RCLONE_DRIVE_CLIENT_SECRET", secret, "RCLONE_DRIVE_SCOPE", "drive"));
        if (!login.compareAndSet(null, process)) {
            process.destroy();
            throw new IOException("Sign-in is already running.");
        }
        Path candidate = null;
        try {
            var result = runner.collect(process, Duration.ofMinutes(5), line -> {
                var matcher = AUTH_LINK.matcher(line);
                if (matcher.find()) browser.accept(URI.create(matcher.group()));
            });
            if (result.exitCode() != 0)
                throw new IOException("Google sign-in did not complete. Retry, and check the OAuth client and consent screen settings.");
            String token = extractToken(result.output());
            candidate = Files.createTempFile(paths.configDir(), ".account-", ".conf");
            PrivateFiles.write(candidate, "[cloudmount]\ntype = drive\nscope = drive\ncloudmount_cache_id = " + UUID.randomUUID() + "\nclient_id = " + clientId
                    + "\nclient_secret = " + secret + "\ntoken = " + token + "\n");
            verify(binary, candidate);
            Files.move(candidate, paths.credentials(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            login.compareAndSet(process, null);
            if (candidate != null) Files.deleteIfExists(candidate);
        }
    }

    public String verify(String binary) throws Exception {
        return verify(binary, paths.credentials());
    }

    private String verify(String binary, Path file) throws Exception {
        var result = runner.run(List.of(binary, "about", "cloudmount:", "--json", "--config", file.toString(),
                "--contimeout", "10s", "--timeout", "20s", "--retries", "1"), Map.of(), Duration.ofSeconds(45));
        CommandRunner.requireSuccess(result, "Google Drive connection check");
        var data = json.readTree(result.output());
        return data.has("used") ? "Connected · " + dev.cloudmount.transfer.TransferSnapshot.bytes(data.path("used").asLong()) + " used" : "Google Drive connected";
    }

    public void cancel() {
        Process process = login.get();
        if (process != null) process.destroy();
    }

    public void disconnect() throws IOException {
        Files.deleteIfExists(paths.credentials());
    }
}
