package dev.cloudmount.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cloudmount.config.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * Desktop client setup is separate from the user's Google authorization tokens.
 */
public final class OAuthClientStore {
    private final AppPaths paths;
    private final ObjectMapper json = new ObjectMapper();
    public OAuthClientStore(AppPaths paths) {
        this.paths = paths;
    }

    public Client load() throws IOException {
        String id = System.getenv("CLOUDMOUNT_GOOGLE_CLIENT_ID"), secret = System.getenv("CLOUDMOUNT_GOOGLE_CLIENT_SECRET");
        if (id != null && secret != null) return new Client(id, secret);
        Path saved = paths.configDir().resolve("google-oauth.json");
        if (Files.exists(saved)) return read(saved);
        // Reuse this app's already-authorized desktop client without exposing it in text fields.
        if (Files.exists(paths.credentials())) {
            String savedId = "", savedSecret = "";
            for (String line : Files.readAllLines(paths.credentials())) {
                String[] entry = line.split("=", 2);
                if (entry.length != 2) continue;
                if (entry[0].strip().equals("client_id")) savedId = entry[1].strip();
                if (entry[0].strip().equals("client_secret")) savedSecret = entry[1].strip();
            }
            if (!savedId.isBlank() && !savedSecret.isBlank()) return new Client(savedId, savedSecret);
        }
        throw new IOException("Google sign-in needs one-time app setup. Open Google app setup and import your Desktop app credentials JSON.");
    }

    public void importFile(Path source) throws IOException {
        Client client = read(source);
        PrivateFiles.write(paths.configDir().resolve("google-oauth.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("installed", Map.of("client_id", client.id(), "client_secret", client.secret()))));
    }

    private Client read(Path source) throws IOException {
        try {
            if (Files.size(source) > 65536) throw new IOException();
            var installed = json.readTree(Files.readString(source)).path("installed");
            return new Client(installed.path("client_id").asText(), installed.path("client_secret").asText());
        } catch (Exception e) {
            throw new IOException("Could not read Desktop app credentials. Download the OAuth client JSON from Google Cloud and try again.");
        }
    }

    public record Client(String id, String secret) {
        public Client {
            id = id == null ? "" : id.strip();
            secret = secret == null ? "" : secret.strip();
            if (!id.endsWith(".apps.googleusercontent.com") || secret.isBlank()
                    || id.matches("(?s).*\\R.*") || secret.matches("(?s).*\\R.*"))
                throw new IllegalArgumentException("Choose credentials for a Google Desktop app OAuth client.");
        }

        @Override
        public String toString() {
            return "Google Desktop OAuth client [credentials hidden]";
        }
    }
}
