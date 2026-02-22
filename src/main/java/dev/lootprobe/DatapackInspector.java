package dev.lootprobe;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class DatapackInspector {

    private DatapackInspector() {
    }

    public static DatapackInfluence inspect(List<Path> datapacks) throws IOException {
        DatapackInfluence out = new DatapackInfluence();
        for (Path pack : datapacks) {
            Path source = pack.toAbsolutePath();
            if (!Files.exists(source)) {
                continue;
            }
            if (Files.isDirectory(source)) {
                inspectRoot(source, out);
            } else if (source.getFileName().toString().toLowerCase().endsWith(".zip")) {
                inspectZip(source, out);
            }
        }
        out.addedStructures.sort(Comparator.naturalOrder());
        out.overriddenStructures.sort(Comparator.naturalOrder());
        out.addedLootTables.sort(Comparator.naturalOrder());
        out.overriddenLootTables.sort(Comparator.naturalOrder());
        return out;
    }

    private static void inspectZip(Path zipPath, DatapackInfluence out) throws IOException {
        try (var fs = FileSystems.newFileSystem(zipPath)) {
            for (Path root : fs.getRootDirectories()) {
                inspectRoot(root, out);
            }
        }
    }

    private static void inspectRoot(Path root, DatapackInfluence out) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String normalized = file.toString().replace('\\', '/');
                if (!normalized.endsWith(".json")) {
                    return;
                }

                String markerStructure = "/data/";
                int i = normalized.indexOf(markerStructure);
                if (i >= 0) {
                    String relative = normalized.substring(i + markerStructure.length());
                    classify(relative, out, "worldgen/structure/", true);
                    classify(relative, out, "loot_table/", false);
                    classify(relative, out, "loot_tables/", false);
                }
            });
        }
    }

    private static void classify(String dataRelative, DatapackInfluence out, String folder, boolean structure) {
        int slash = dataRelative.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String namespace = dataRelative.substring(0, slash);
        String rest = dataRelative.substring(slash + 1);
        if (!rest.startsWith(folder) || rest.length() <= folder.length()) {
            return;
        }
        String path = rest.substring(folder.length(), rest.length() - ".json".length());
        String id = namespace + ":" + path.replace('\\', '/');

        if (structure) {
            if ("minecraft".equals(namespace)) {
                addUnique(out.overriddenStructures, id);
            } else {
                addUnique(out.addedStructures, id);
            }
        } else {
            if ("minecraft".equals(namespace)) {
                addUnique(out.overriddenLootTables, id);
            } else {
                addUnique(out.addedLootTables, id);
            }
        }
    }

    private static void addUnique(List<String> list, String id) {
        Set<String> s = new LinkedHashSet<>(list);
        if (s.add(id)) {
            list.clear();
            list.addAll(s);
        }
    }

    public static final class DatapackInfluence {
        public List<String> addedStructures = new ArrayList<>();
        public List<String> overriddenStructures = new ArrayList<>();
        public List<String> addedLootTables = new ArrayList<>();
        public List<String> overriddenLootTables = new ArrayList<>();
    }
}
