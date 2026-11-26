package dev.cloudmount.rclone;

import com.fasterxml.jackson.databind.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class RcClient {
    private final URI base;
    private final String authorization;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public RcClient(int port, String password) {
        base = URI.create("http://127.0.0.1:" + port + "/");
        authorization = "Basic " + Base64.getEncoder().encodeToString(("cloudmount:" + password).getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode call(String endpoint) throws Exception {
        return call(endpoint, "{}");
    }

    public JsonNode call(String endpoint, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(endpoint)).timeout(Duration.ofSeconds(4))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("rclone status request failed (HTTP " + response.statusCode() + ").");
        return json.readTree(response.body());
    }
}
