package dev.lootprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ModrinthClient {
    private static final String API = "https://api.modrinth.com/v2";
    private final HttpClient client;
    private final ObjectMapper mapper;

    public ModrinthClient() {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.mapper = new ObjectMapper();
    }

    public List<ProjectHit> searchProjects(String query, String projectType) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(API)
                .append("/search?query=")
                .append(url(query))
                .append("&limit=20&index=relevance");
        if (projectType != null && !projectType.isBlank()) {
            String facets = "[[\"project_type:" + projectType + "\"]]";
            url.append("&facets=").append(url(facets));
        }

        JsonNode root = getJson(url.toString());
        List<ProjectHit> hits = new ArrayList<>();
        for (JsonNode node : root.path("hits")) {
            ProjectHit hit = new ProjectHit();
            hit.projectId = node.path("project_id").asText();
            hit.slug = node.path("slug").asText();
            hit.title = node.path("title").asText();
            hit.description = node.path("description").asText("");
            hit.projectType = node.path("project_type").asText("");
            hits.add(hit);
        }
        return hits;
    }

    public Path downloadProjectFile(
            String projectId,
            String mcVersion,
            String requiredExtension,
            Path destinationDir
    ) throws IOException, InterruptedException {
        Files.createDirectories(destinationDir);
        String url = API + "/project/" + url(projectId) + "/version";
        if (mcVersion != null && !mcVersion.isBlank()) {
            String gameVersions = "[\"" + mcVersion + "\"]";
            url += "?game_versions=" + url(gameVersions);
        }
        JsonNode versions = getJson(url);
        for (JsonNode version : versions) {
            for (JsonNode file : version.path("files")) {
                String filename = file.path("filename").asText();
                if (requiredExtension != null && !requiredExtension.isBlank()
                        && !filename.toLowerCase().endsWith(requiredExtension.toLowerCase())) {
                    continue;
                }
                if (requiredExtension == null || requiredExtension.isBlank() || file.path("primary").asBoolean(false)) {
                    return downloadFile(file.path("url").asText(), destinationDir.resolve(filename));
                }
            }
        }
        throw new IOException("No downloadable file matched for project " + projectId + " and MC " + mcVersion);
    }

    public Path downloadBySlug(
            String slugOrId,
            String mcVersion,
            String requiredExtension,
            Path destinationDir
    ) throws IOException, InterruptedException {
        try {
            return downloadProjectFile(slugOrId, mcVersion, requiredExtension, destinationDir);
        } catch (IOException notById) {
            List<ProjectHit> hits = searchProjects(slugOrId, null);
            if (hits.isEmpty()) {
                throw notById;
            }
            return downloadProjectFile(hits.get(0).projectId, mcVersion, requiredExtension, destinationDir);
        }
    }

    private Path downloadFile(String downloadUrl, Path targetFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + downloadUrl);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, targetFile);
        }
        return targetFile;
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .GET()
                .header("User-Agent", "loot-probe-gui/0.1.0")
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static final class ProjectHit {
        public String projectId;
        public String slug;
        public String title;
        public String description;
        public String projectType;

        @Override
        public String toString() {
            return title + " (" + slug + ")";
        }
    }
}
