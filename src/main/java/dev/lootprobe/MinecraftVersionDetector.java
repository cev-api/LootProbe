package dev.lootprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MinecraftVersionDetector {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?<!\\d)(\\d+\\.\\d+(?:\\.\\d+)?)(?!\\d)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MinecraftVersionDetector() {
    }

    public static Optional<String> detect(Path serverJar) throws IOException {
        if (serverJar == null) {
            return Optional.empty();
        }
        Path absolute = serverJar.toAbsolutePath();
        if (!absolute.toFile().isFile()) {
            return Optional.empty();
        }

        try (ZipFile zip = new ZipFile(absolute.toFile())) {
            Optional<String> fromVersionJson = readFromVersionJson(zip);
            if (fromVersionJson.isPresent()) {
                return fromVersionJson;
            }

            Optional<String> fromManifest = readFromManifest(zip);
            if (fromManifest.isPresent()) {
                return fromManifest;
            }
        }

        Matcher byName = VERSION_PATTERN.matcher(absolute.getFileName().toString());
        if (byName.find()) {
            return Optional.of(byName.group(1));
        }
        return Optional.empty();
    }

    private static Optional<String> readFromVersionJson(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("version.json");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            JsonNode json = MAPPER.readTree(in);
            String id = readNonBlank(json, "id");
            if (id != null) {
                return Optional.of(id);
            }
            String name = readNonBlank(json, "name");
            return name != null ? Optional.of(name) : Optional.empty();
        }
    }

    private static Optional<String> readFromManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            Manifest manifest = new Manifest(in);
            String[] keys = {
                    "Implementation-Version",
                    "Bundle-Version",
                    "Specification-Version"
            };
            for (String key : keys) {
                String raw = manifest.getMainAttributes().getValue(key);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Matcher m = VERSION_PATTERN.matcher(raw);
                if (m.find()) {
                    return Optional.of(m.group(1));
                }
            }
            return Optional.empty();
        }
    }

    private static String readNonBlank(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }
}
