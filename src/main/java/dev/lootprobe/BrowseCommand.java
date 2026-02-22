package dev.lootprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(name = "browse", description = "Browse a probe result JSON in a structured terminal view.")
public final class BrowseCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Path to probe result JSON")
    private Path input;

    @Override
    public Integer call() throws Exception {
        Path path = input.toAbsolutePath();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Input file not found: " + path);
        }

        ObjectMapper mapper = new ObjectMapper();
        ProbeResult result = mapper.readValue(path.toFile(), ProbeResult.class);
        List<WorldChestScanner.ScannedStructure> structures = new ArrayList<>();
        if (result.regionScan != null && result.regionScan.structures != null) {
            structures.addAll(result.regionScan.structures);
        }
        structures.sort(Comparator.comparing((WorldChestScanner.ScannedStructure s) -> s.id).thenComparingInt(s -> s.x).thenComparingInt(s -> s.z));

        printHeader(path, result, structures);
        printStructureSummary(structures);
        printHelp();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nloot-browse> ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase(Locale.ROOT);

                switch (cmd) {
                    case "q", "quit", "exit" -> {
                        return 0;
                    }
                    case "h", "help" -> printHelp();
                    case "summary" -> {
                        printHeader(path, result, structures);
                        printStructureSummary(structures);
                    }
                    case "list" -> printStructureSummary(structures);
                    case "show" -> showStructure(parts, structures, false);
                    case "items" -> showStructure(parts, structures, true);
                    case "find" -> findItems(parts, structures);
                    default -> System.out.println("Unknown command. Type 'help'.");
                }
            }
        }
        return 0;
    }

    private static void printHeader(Path path, ProbeResult result, List<WorldChestScanner.ScannedStructure> structures) {
        int chestCount = 0;
        int itemCount = 0;
        for (WorldChestScanner.ScannedStructure s : structures) {
            chestCount += s.chests != null ? s.chests.size() : 0;
            if (s.chests != null) {
                for (WorldChestScanner.ChestData chest : s.chests) {
                    itemCount += chest.items != null ? chest.items.size() : 0;
                }
            }
        }

        System.out.println("\n============================================================");
        System.out.println(" Loot Probe Result Browser");
        System.out.println("============================================================");
        System.out.println("File      : " + path);
        System.out.println("Version   : " + safe(result.mcVersion));
        System.out.println("Seed      : " + result.seed);
        System.out.println("Duration  : " + result.durationSeconds + "s");
        System.out.println("Structures: " + structures.size());
        System.out.println("Chests    : " + chestCount);
        System.out.println("Items     : " + itemCount);
        if (result.regionScan != null) {
            System.out.println("Scan      : dim=" + safe(result.regionScan.dimension)
                    + " center=(" + result.regionScan.centerX + "," + result.regionScan.centerZ + ")"
                    + " radius=" + result.regionScan.radius);
        }
    }

    private static void printStructureSummary(List<WorldChestScanner.ScannedStructure> structures) {
        System.out.println("\n--- Structures ---");
        if (structures.isEmpty()) {
            System.out.println("No scanned structures in regionScan.");
            return;
        }
        System.out.printf("%-5s %-30s %-18s %-8s %-8s%n", "Idx", "Structure", "Coords", "Chests", "Items");
        for (int i = 0; i < structures.size(); i++) {
            WorldChestScanner.ScannedStructure s = structures.get(i);
            int itemCount = 0;
            if (s.chests != null) {
                for (WorldChestScanner.ChestData chest : s.chests) {
                    itemCount += chest.items != null ? chest.items.size() : 0;
                }
            }
            String coords = s.x + "," + s.y + "," + s.z;
            int chests = s.chests != null ? s.chests.size() : 0;
            System.out.printf("%-5d %-30s %-18s %-8d %-8d%n", i, trim(s.id, 29), coords, chests, itemCount);
        }
    }

    private static void showStructure(String[] parts, List<WorldChestScanner.ScannedStructure> structures, boolean showItems) {
        if (parts.length < 2) {
            System.out.println("Usage: " + (showItems ? "items <structureIndex>" : "show <structureIndex>"));
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid index: " + parts[1]);
            return;
        }
        if (idx < 0 || idx >= structures.size()) {
            System.out.println("Index out of range. Valid 0.." + Math.max(0, structures.size() - 1));
            return;
        }

        WorldChestScanner.ScannedStructure s = structures.get(idx);
        System.out.println("\n--- Structure [" + idx + "] " + s.id + " @ " + s.x + "," + s.y + "," + s.z + " ---");
        if (s.error != null && !s.error.isBlank()) {
            System.out.println("Error: " + s.error);
            return;
        }
        if (s.chests == null || s.chests.isEmpty()) {
            System.out.println("No chests captured.");
            return;
        }

        for (int i = 0; i < s.chests.size(); i++) {
            WorldChestScanner.ChestData chest = s.chests.get(i);
            int itemCount = chest.items != null ? chest.items.size() : 0;
            String lootTable = chest.lootTable != null ? chest.lootTable : "-";
            System.out.printf("  Chest %-3d @ (%d,%d,%d) %-16s table=%s items=%d%n",
                    i, chest.x, chest.y, chest.z, safe(chest.blockId), lootTable, itemCount);
            if (showItems && chest.items != null) {
                for (LootSampler.ItemStackData item : chest.items) {
                    System.out.printf("      - %s x%d%n", safe(item.itemId), item.count);
                }
            }
        }

        if (!showItems) {
            System.out.println("Tip: use 'items " + idx + "' to print all item stacks.");
        }
    }

    private static void findItems(String[] parts, List<WorldChestScanner.ScannedStructure> structures) {
        if (parts.length < 2) {
            System.out.println("Usage: find <text>");
            return;
        }
        String needle = String.join(" ", List.of(parts).subList(1, parts.length)).toLowerCase(Locale.ROOT);
        int hits = 0;
        System.out.println("\n--- Matches for '" + needle + "' ---");
        for (int i = 0; i < structures.size(); i++) {
            WorldChestScanner.ScannedStructure s = structures.get(i);
            if (s.chests == null) {
                continue;
            }
            for (int c = 0; c < s.chests.size(); c++) {
                WorldChestScanner.ChestData chest = s.chests.get(c);
                if (chest.items == null) {
                    continue;
                }
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (LootSampler.ItemStackData item : chest.items) {
                    String id = safe(item.itemId);
                    if (id.toLowerCase(Locale.ROOT).contains(needle)) {
                        counts.merge(id, item.count, Integer::sum);
                    }
                }
                if (!counts.isEmpty()) {
                    hits++;
                    System.out.printf("structure[%d] chest[%d] %s @ (%d,%d,%d)%n", i, c, trim(s.id, 40), chest.x, chest.y, chest.z);
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        System.out.printf("  - %s x%d%n", e.getKey(), e.getValue());
                    }
                }
            }
        }
        if (hits == 0) {
            System.out.println("No matching items found.");
        }
    }

    private static void printHelp() {
        System.out.println("\nCommands:");
        System.out.println("  summary              show top summary and structure table");
        System.out.println("  list                 list structures");
        System.out.println("  show <idx>           show chest list for a structure");
        System.out.println("  items <idx>          show full item stacks for a structure");
        System.out.println("  find <text>          search item ids across all chests");
        System.out.println("  help                 show this help");
        System.out.println("  quit                 exit browser");
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String trim(String s, int max) {
        String value = safe(s);
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
