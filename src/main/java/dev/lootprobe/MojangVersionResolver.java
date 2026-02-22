package dev.lootprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class MojangVersionResolver {
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private MojangVersionResolver() {
    }

    public static Path downloadServerJar(String version, Path cacheRoot) throws IOException, InterruptedException {
        Path versionDir = cacheRoot.resolve("mc-server").resolve(version);
        Path jarPath = versionDir.resolve("server.jar");
        if (Files.exists(jarPath)) {
            return jarPath;
        }

        Files.createDirectories(versionDir);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        ObjectMapper mapper = new ObjectMapper();

        JsonNode manifest = getJson(client, mapper, MANIFEST_URL);
        String versionJsonUrl = null;
        for (JsonNode node : manifest.get("versions")) {
            if (version.equals(node.get("id").asText())) {
                versionJsonUrl = node.get("url").asText();
                break;
            }
        }
        if (versionJsonUrl == null) {
            throw new IllegalArgumentException("Version not found in Mojang manifest: " + version);
        }

        JsonNode versionJson = getJson(client, mapper, versionJsonUrl);
        String serverUrl = versionJson.path("downloads").path("server").path("url").asText();
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("No server download available for version: " + version);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download server jar (" + response.statusCode() + ")");
        }
        try (InputStream in = response.body()) {
            Files.copy(in, jarPath);
        }
        return jarPath;
    }

    private static JsonNode getJson(HttpClient client, ObjectMapper mapper, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }
}
