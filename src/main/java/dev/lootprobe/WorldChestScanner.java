package dev.lootprobe;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorldChestScanner {
    private static final int PLUGIN_DISCOVERY_MAX_STRUCTURES = 3;
    private static final int PLUGIN_DISCOVERY_MAX_WORK_UNITS = 1200;
    private static final Pattern XYZ_FULL = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]");
    private static final Pattern XYZ_TILDE_Y = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*~\\s*,\\s*(-?\\d+)\\s*\\]");
    private static final Pattern XYZ_GENERIC = Pattern.compile("(-?\\d+)\\s*,\\s*(?:~\\s*,\\s*)?(-?\\d+)");

    private WorldChestScanner() {
    }

    public static ScanReport scan(
            RconClient rcon,
            Path runDir,
            int centerX,
            int centerZ,
            int radius,
            List<ProbeConfig.StructureTarget> structureTargets,
            Path discoveryCacheFile,
            boolean showProgress,
            int locateStep,
            int extractChunkRadius,
            boolean extractParallelChunks,
            int extractParallelChunkCount,
            int extractParallelStructureJobs,
            int extractTimeoutSec,
            Integer maxStructures
    ) throws Exception {
        return scan(
                rcon,
                runDir,
                centerX,
                centerZ,
                radius,
                structureTargets,
                discoveryCacheFile,
                showProgress,
                locateStep,
                extractChunkRadius,
                extractParallelChunks,
                extractParallelChunkCount,
                extractParallelStructureJobs,
                extractTimeoutSec,
                maxStructures,
                List.of(),
                null
        );
    }

    public static ScanReport scan(
            RconClient rcon,
            Path runDir,
            int centerX,
            int centerZ,
            int radius,
            List<ProbeConfig.StructureTarget> structureTargets,
            Path discoveryCacheFile,
            boolean showProgress,
            int locateStep,
            int extractChunkRadius,
            boolean extractParallelChunks,
            int extractParallelChunkCount,
            int extractParallelStructureJobs,
            int extractTimeoutSec,
            Integer maxStructures,
            List<ScannedStructure> resumeStructures,
            ProgressPrinter.ProgressListener progressListener
    ) throws Exception {
        if (structureTargets == null || structureTargets.isEmpty()) {
            throw new IllegalArgumentException("Scan mode requires at least one --structure.");
        }
        Map<String, List<String>> targetsByDimension = groupTargetsByDimension(structureTargets);
        if (targetsByDimension.isEmpty()) {
            throw new IllegalArgumentException("Scan mode requires at least one valid structure target.");
        }

        List<Point> samplePoints = buildSamplePoints(centerX, centerZ, radius, locateStep);
        int totalStructures = targetsByDimension.values().stream().mapToInt(List::size).sum();
        ProgressPrinter locateProgress = showProgress
                ? new ProgressPrinter(Math.max(samplePoints.size() * totalStructures, 1), 30, progressListener)
                : null;
        if (locateProgress != null) {
            locateProgress.info("scan: discovering structures...");
        }
        List<StructureStart> starts = loadDiscoveryCache(discoveryCacheFile);
        if (!starts.isEmpty()) {
            if (locateProgress != null) {
                locateProgress.info("scan: loaded " + starts.size() + " structure starts from cache.");
            }
        } else {
            starts = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : targetsByDimension.entrySet()) {
                String dimension = entry.getKey();
                List<String> structureFilter = entry.getValue();
                List<StructureStart> startsForDim = List.of();
                int pluginWorkUnits = samplePoints.size() * structureFilter.size();
                boolean hasVillage = structureFilter.stream()
                        .filter(id -> id != null)
                        .map(id -> id.trim().toLowerCase())
                        .anyMatch("minecraft:village"::equals);
                boolean tryPluginDiscovery = !hasVillage
                        && shouldUsePluginDiscovery(structureFilter.size(), pluginWorkUnits);
                if (tryPluginDiscovery) {
                    startsForDim = discoverStructureStartsViaPlugin(
                            rcon, runDir, dimension, centerX, centerZ, radius, structureFilter, locateStep
                    );
                } else if (locateProgress != null) {
                    if (hasVillage) {
                        locateProgress.info("scan: plugin discovery disabled for minecraft:village (Paper locateNearestStructure can stall); using sampled /locate mode...");
                    } else {
                        locateProgress.info("scan: plugin discovery skipped for large workload (" + pluginWorkUnits
                                + " locate operations) in " + dimension + "; using sampled /locate mode...");
                    }
                }
                if (startsForDim.isEmpty()) {
                    if (locateProgress != null) {
                        locateProgress.info("scan: plugin discovery unavailable/empty in " + dimension + ", falling back to /locate samples...");
                    }
                    startsForDim = discoverStructureStarts(
                            rcon, dimension, centerX, centerZ, radius, structureFilter, samplePoints, locateProgress
                    );
                }
                starts.addAll(startsForDim);
            }
            saveDiscoveryCache(discoveryCacheFile, starts);
        }

        starts.sort(
                Comparator
                        .comparingInt((StructureStart s) -> dimensionOrder(s.dimension))
                        .thenComparing(s -> s.id != null ? s.id : "")
                        .thenComparingLong(s -> dist2(centerX, centerZ, s.x, s.z))
                        .thenComparingInt(s -> s.x)
                        .thenComparingInt(s -> s.z)
        );
        if (maxStructures != null && maxStructures > 0 && starts.size() > maxStructures) {
            starts = new ArrayList<>(starts.subList(0, maxStructures));
        }
        if (locateProgress != null) {
            locateProgress.done("locate pass complete");
            locateProgress.info("scan: discovered " + starts.size() + " structure starts");
        }
        ScanReport report = new ScanReport();
        report.seed = 0L;
        report.dimension = targetsByDimension.size() == 1
                ? targetsByDimension.keySet().iterator().next()
                : "mixed";
        report.centerX = centerX;
        report.centerZ = centerZ;
        report.radius = radius;
        report.locateSampleCount = samplePoints.size();

        Path pluginDataDir = runDir.resolve("plugins").resolve("LootProbePaperPlugin").resolve("out");
        Files.createDirectories(pluginDataDir);
        ObjectMapper mapper = new ObjectMapper();

        int index = 0;
        Map<String, ScannedStructure> resumeByKey = indexResumableStructures(resumeStructures);
        ProgressPrinter extractProgress = showProgress
                ? new ProgressPrinter(Math.max(starts.size(), 1), 30, progressListener)
                : null;
        if (extractProgress != null) {
            extractProgress.info("scan: extracting chest/container data from generated chunks...");
        }
        Map<String, Integer> resultIndexByKey = new LinkedHashMap<>();
        List<StructureStart> pending = new ArrayList<>();
        int resumedCount = 0;
        for (StructureStart start : starts) {
            String key = structureKey(start.dimension, start.id, start.x, start.z);
            ScannedStructure resumed = resumeByKey.get(key);
            if (resumed != null) {
                upsertSuccess(report, resultIndexByKey, resumed);
                if (resumed.chunkStats != null) {
                    report.totalChunksRequested += resumed.chunkStats.requested;
                    report.totalChunksAlreadyLoaded += resumed.chunkStats.alreadyLoaded;
                    report.totalChunksNewlyLoaded += resumed.chunkStats.newlyLoaded;
                    report.totalChunksAlreadyGenerated += resumed.chunkStats.alreadyGenerated;
                    report.totalChunksNewlyGenerated += resumed.chunkStats.newlyGenerated;
                }
                resumedCount++;
                if (extractProgress != null) {
                    extractProgress.step("resumed " + start.id + " at " + start.x + "," + start.z);
                }
            } else {
                pending.add(start);
            }
        }
        if (extractProgress != null && resumedCount > 0) {
            extractProgress.info("scan: resumed " + resumedCount + " completed structures from prior output.");
        }
        final int maxAttempts = 3;
        final int maxParallelStructures = Math.min(8, Math.max(1, extractParallelStructureJobs));
        final int effectiveParallelChunkCount = Math.min(12, Math.max(1, extractParallelChunkCount));
        for (int attempt = 1; attempt <= maxAttempts && !pending.isEmpty(); attempt++) {
            List<StructureStart> queue = new ArrayList<>(pending);
            List<StructureStart> retryLater = new ArrayList<>();
            if (extractProgress != null && attempt > 1) {
                extractProgress.info("scan: retry pass " + attempt + " for " + pending.size() + " timed-out structures...");
            }
            List<ActiveExtractJob> activeJobs = new ArrayList<>();
            while (!queue.isEmpty() || !activeJobs.isEmpty()) {
                while (!queue.isEmpty() && activeJobs.size() < maxParallelStructures) {
                    StructureStart start = queue.remove(0);
                    int effectiveChunkRadius = chooseExtractChunkRadius(start.id, extractChunkRadius);
                    String token = "scan-" + Instant.now().toEpochMilli() + "-" + (index++);
                    String relativeOut = "out/" + token + ".json";
                    Path outFile = pluginDataDir.resolve(token + ".json");
                    Files.deleteIfExists(outFile);

                    int commandTimeoutMs = Math.max(10_000, Math.min(20_000, extractTimeoutSec * 1000));
                    String commandSuffix = start.dimension + " " + start.id + " " + start.x + " " + start.z + " "
                            + effectiveChunkRadius + " " + relativeOut + " " + extractParallelChunks + " " + effectiveParallelChunkCount;
                    String response;
                    try {
                        response = rcon.commandOnce("lootprobe_extract_start " + commandSuffix, 4_000);
                    } catch (SocketTimeoutException timeout) {
                        upsertTimeout(report, resultIndexByKey, start, "timeout pass " + attempt + "/" + maxAttempts);
                        retryLater.add(start);
                        if (extractProgress != null) {
                            extractProgress.step("deferred " + start.id + " at " + start.x + "," + start.z + " r=" + effectiveChunkRadius);
                        }
                        continue;
                    }
                    if (looksUnknownCommand(response)) {
                        response = rcon.commandOnce("lootprobepaperplugin:lootprobe_extract_start " + commandSuffix, 4_000);
                    }
                    boolean asyncMode = !looksUnknownCommand(response) && parseExtractJobId(response) != null;
                    if (!asyncMode) {
                        // Fallback to legacy synchronous plugin command.
                        response = rcon.commandOnce("lootprobe_extract " + commandSuffix, commandTimeoutMs);
                        if (looksUnknownCommand(response)) {
                            response = rcon.commandOnce("lootprobepaperplugin:lootprobe_extract " + commandSuffix, commandTimeoutMs);
                        }
                        if (looksUnknownCommand(response)) {
                            throw new IOException("Plugin command not available. Response: " + response);
                        }
                        boolean wrote = waitForFile(outFile, extractTimeoutSec);
                        if (!wrote) {
                            upsertTimeout(report, resultIndexByKey, start, "timeout pass " + attempt + "/" + maxAttempts);
                            retryLater.add(start);
                            if (extractProgress != null) {
                                extractProgress.step("deferred " + start.id + " at " + start.x + "," + start.z + " r=" + effectiveChunkRadius);
                            }
                            continue;
                        }
                        consumeExtractOutput(mapper, outFile, start, report, resultIndexByKey);
                        if (extractProgress != null) {
                            extractProgress.step("extracted " + start.id + " at " + start.x + "," + start.z + " r=" + effectiveChunkRadius);
                        }
                        continue;
                    }

                    ActiveExtractJob job = new ActiveExtractJob();
                    job.start = start;
                    job.jobId = parseExtractJobId(response);
                    job.outFile = outFile;
                    job.effectiveChunkRadius = effectiveChunkRadius;
                    job.deadlineMs = System.currentTimeMillis() + commandTimeoutMs;
                    activeJobs.add(job);
                }

                if (activeJobs.isEmpty()) {
                    continue;
                }

                Thread.sleep(200);
                long now = System.currentTimeMillis();
                for (int i = activeJobs.size() - 1; i >= 0; i--) {
                    ActiveExtractJob active = activeJobs.get(i);
                    StructureStart start = active.start;
                    if (now >= active.deadlineMs) {
                        upsertTimeout(report, resultIndexByKey, start, "timeout pass " + attempt + "/" + maxAttempts);
                        retryLater.add(start);
                        if (extractProgress != null) {
                            extractProgress.step("deferred " + start.id + " at " + start.x + "," + start.z + " r=" + active.effectiveChunkRadius);
                        }
                        activeJobs.remove(i);
                        continue;
                    }

                    String status = rcon.commandOnce("lootprobe_extract_status " + active.jobId, 4_000);
                    if (looksUnknownCommand(status)) {
                        status = rcon.commandOnce("lootprobepaperplugin:lootprobe_extract_status " + active.jobId, 4_000);
                    }
                    String normalized = status != null ? status.trim().toLowerCase() : "";
                    if (normalized.startsWith("failed")) {
                        upsertTimeout(report, resultIndexByKey, start, "failed pass " + attempt + "/" + maxAttempts);
                        retryLater.add(start);
                        if (extractProgress != null) {
                            extractProgress.step("deferred " + start.id + " at " + start.x + "," + start.z + " r=" + active.effectiveChunkRadius);
                        }
                        activeJobs.remove(i);
                        continue;
                    }
                    if (!normalized.startsWith("done")) {
                        continue;
                    }
                    boolean wrote = waitForFile(active.outFile, extractTimeoutSec);
                    if (!wrote) {
                        upsertTimeout(report, resultIndexByKey, start, "timeout pass " + attempt + "/" + maxAttempts);
                        retryLater.add(start);
                        if (extractProgress != null) {
                            extractProgress.step("deferred " + start.id + " at " + start.x + "," + start.z + " r=" + active.effectiveChunkRadius);
                        }
                        activeJobs.remove(i);
                        continue;
                    }
                    consumeExtractOutput(mapper, active.outFile, start, report, resultIndexByKey);
                    if (extractProgress != null) {
                        extractProgress.step("extracted " + start.id + " at " + start.x + "," + start.z + " r=" + active.effectiveChunkRadius);
                    }
                    activeJobs.remove(i);
                }
            }
            pending = retryLater;
        }
        if (extractProgress != null && !pending.isEmpty()) {
            extractProgress.info("scan: failed structures after retries (" + pending.size() + "):");
            for (StructureStart failed : pending) {
                extractProgress.info("scan: failed " + failed.id + " in " + failed.dimension + " at " + failed.x + "," + failed.z);
            }
        }
        if (extractProgress != null) {
            extractProgress.done("extraction complete");
        }

        report.structures.sort(
                Comparator
                        .comparingInt((ScannedStructure s) -> dimensionOrder(s.dimension))
                        .thenComparing(s -> s.id != null ? s.id : "")
                        .thenComparingLong(s -> dist2(centerX, centerZ, s.x, s.z))
                        .thenComparingInt(s -> s.x)
                        .thenComparingInt(s -> s.z)
        );
        return report;
    }

    private static void consumeExtractOutput(
            ObjectMapper mapper,
            Path outFile,
            StructureStart start,
            ScanReport report,
            Map<String, Integer> resultIndexByKey
    ) throws IOException {
        PluginStructureDump dump = mapper.readValue(outFile.toFile(), PluginStructureDump.class);
        ScannedStructure structure = new ScannedStructure();
        structure.id = start.id;
        structure.dimension = start.dimension;
        structure.type = shortType(start.id);
        structure.x = start.x;
        structure.y = start.y;
        structure.z = start.z;
        if (dump != null && dump.chests != null) {
            for (ChestData chest : dump.chests) {
                structure.chests.add(chest);
            }
        }
        if (dump != null && dump.chunkStats != null) {
            structure.chunkStats = dump.chunkStats;
            report.totalChunksRequested += dump.chunkStats.requested;
            report.totalChunksAlreadyLoaded += dump.chunkStats.alreadyLoaded;
            report.totalChunksNewlyLoaded += dump.chunkStats.newlyLoaded;
            report.totalChunksAlreadyGenerated += dump.chunkStats.alreadyGenerated;
            report.totalChunksNewlyGenerated += dump.chunkStats.newlyGenerated;
        }
        structure.chests.sort(Comparator.comparingInt((ChestData c) -> c.x).thenComparingInt(c -> c.z).thenComparingInt(c -> c.y));
        upsertSuccess(report, resultIndexByKey, structure);
    }

    private static Map<String, ScannedStructure> indexResumableStructures(List<ScannedStructure> resumeStructures) {
        Map<String, ScannedStructure> byKey = new LinkedHashMap<>();
        if (resumeStructures == null || resumeStructures.isEmpty()) {
            return byKey;
        }
        for (ScannedStructure structure : resumeStructures) {
            if (structure == null || structure.id == null || structure.id.isBlank()) {
                continue;
            }
            if (structure.error != null && !structure.error.isBlank()) {
                continue;
            }
            String key = structureKey(structure.dimension, structure.id, structure.x, structure.z);
            byKey.putIfAbsent(key, structure);
        }
        return byKey;
    }

    private static void upsertTimeout(
            ScanReport report,
            Map<String, Integer> indexByKey,
            StructureStart start,
            String error
    ) {
        String key = structureKey(start.dimension, start.id, start.x, start.z);
        Integer idx = indexByKey.get(key);
        ScannedStructure timedOut = new ScannedStructure();
        timedOut.id = start.id;
        timedOut.dimension = start.dimension;
        timedOut.type = shortType(start.id);
        timedOut.x = start.x;
        timedOut.y = start.y;
        timedOut.z = start.z;
        timedOut.error = error;
        if (idx == null) {
            indexByKey.put(key, report.structures.size());
            report.structures.add(timedOut);
        } else {
            report.structures.set(idx, timedOut);
        }
    }

    private static void upsertSuccess(
            ScanReport report,
            Map<String, Integer> indexByKey,
            ScannedStructure structure
    ) {
        String key = structureKey(structure.dimension, structure.id, structure.x, structure.z);
        Integer idx = indexByKey.get(key);
        if (idx == null) {
            indexByKey.put(key, report.structures.size());
            report.structures.add(structure);
        } else {
            report.structures.set(idx, structure);
        }
    }

    private static String structureKey(String dimension, String id, int x, int z) {
        return (dimension != null ? dimension : "-") + "|" + id + "|" + x + "|" + z;
    }

    private static List<StructureStart> loadDiscoveryCache(Path cacheFile) {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            DiscoveryCacheFile cached = mapper.readValue(cacheFile.toFile(), DiscoveryCacheFile.class);
            if (cached == null || cached.starts == null || cached.starts.isEmpty()) {
                return List.of();
            }
            List<StructureStart> out = new ArrayList<>();
            for (DiscoverStartEntry e : cached.starts) {
                if (e == null || e.id == null || e.id.isBlank() || e.dimension == null || e.dimension.isBlank()) {
                    continue;
                }
                StructureStart s = new StructureStart();
                s.id = e.id;
                s.dimension = e.dimension;
                s.x = e.x;
                s.y = e.y;
                s.z = e.z;
                out.add(s);
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void saveDiscoveryCache(Path cacheFile, List<StructureStart> starts) {
        if (cacheFile == null || starts == null || starts.isEmpty()) {
            return;
        }
        try {
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            DiscoveryCacheFile out = new DiscoveryCacheFile();
            out.createdUtc = Instant.now().toString();
            out.starts = new ArrayList<>();
            for (StructureStart s : starts) {
                DiscoverStartEntry entry = new DiscoverStartEntry();
                entry.id = s.id;
                entry.dimension = s.dimension;
                entry.x = s.x;
                entry.y = s.y;
                entry.z = s.z;
                out.starts.add(entry);
            }
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), out);
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldUsePluginDiscovery(int structureCount, int workUnits) {
        if (structureCount <= 0) {
            return false;
        }
        if (structureCount > PLUGIN_DISCOVERY_MAX_STRUCTURES) {
            return false;
        }
        return workUnits <= PLUGIN_DISCOVERY_MAX_WORK_UNITS;
    }

    private static int chooseExtractChunkRadius(String structureId, int requestedRadius) {
        int requested = Math.max(2, requestedRadius);
        String id = structureId != null ? structureId.toLowerCase() : "";
        int recommended;
        if (id.contains("buried_treasure")) {
            recommended = 2;
        } else if (id.contains("ruined_portal")) {
            recommended = 3;
        } else if (id.contains("shipwreck")) {
            recommended = 3;
        } else if (id.contains("ocean_ruin")) {
            recommended = 3;
        } else if (id.contains("mineshaft")) {
            recommended = 3;
        } else if (id.contains("trial_chambers")) {
            recommended = 4;
        } else if (id.contains("ancient_city")) {
            recommended = 5;
        } else {
            recommended = requested;
        }
        return Math.max(2, Math.min(requested, recommended));
    }

    private static Map<String, List<String>> groupTargetsByDimension(List<ProbeConfig.StructureTarget> structureTargets) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (ProbeConfig.StructureTarget target : structureTargets) {
            if (target == null || target.id == null || target.id.isBlank()) {
                continue;
            }
            String dimension = target.normalizedDimension("minecraft:overworld");
            grouped.computeIfAbsent(dimension, ignored -> new HashSet<>()).add(target.id.trim());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : grouped.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    private static boolean waitForFile(Path outFile, int timeoutSec) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(outFile) && Files.size(outFile) > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static boolean looksUnknownCommand(String response) {
        if (response == null) {
            return true;
        }
        String s = response.toLowerCase();
        return s.contains("unknown") || s.contains("not found");
    }

    private static String parseExtractJobId(String response) {
        if (response == null) {
            return null;
        }
        String s = response.trim();
        int idx = s.indexOf("job=");
        if (idx < 0) {
            return null;
        }
        int start = idx + 4;
        int end = s.indexOf(' ', start);
        if (end < 0) {
            end = s.length();
        }
        String id = s.substring(start, end).trim();
        return id.isEmpty() ? null : id;
    }

    private static List<StructureStart> discoverStructureStarts(
            RconClient rcon,
            String dimension,
            int centerX,
            int centerZ,
            int radius,
            List<String> structureFilter,
            List<Point> samplePoints,
            ProgressPrinter progress
    ) throws IOException {
        List<StructureStart> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int unparsedExamples = 0;

        for (String structureId : structureFilter) {
            int before = out.size();
            for (Point p : samplePoints) {
                String response = rcon.command("execute in " + dimension + " positioned " + p.x + " 80 " + p.z + " run locate structure " + structureId);
                Coords coords = parseLocateCoords(response);
                if (coords != null && dist2(centerX, centerZ, coords.x, coords.z) <= (long) radius * radius) {
                    String key = structureId + "|" + (coords.x >> 4) + "|" + (coords.z >> 4);
                    if (seen.add(key)) {
                        StructureStart start = new StructureStart();
                        start.id = structureId;
                        start.dimension = dimension;
                        start.x = coords.x;
                        start.y = coords.y != null ? coords.y : 0;
                        start.z = coords.z;
                        out.add(start);
                    }
                } else if (coords == null && progress != null && unparsedExamples < 3) {
                    progress.info("scan: locate response could not be parsed for " + structureId + ": " + summarizeResponse(response));
                    unparsedExamples++;
                }
                if (progress != null) {
                    progress.step("locating " + structureId);
                }
            }
            boolean villageNoResults = "minecraft:village".equalsIgnoreCase(structureId) && out.size() == before;
            if (villageNoResults) {
                if (progress != null) {
                    progress.info("scan: no starts found from minecraft:village; retrying explicit village_* variants...");
                }
                discoverVillageVariants(
                        rcon, dimension, centerX, centerZ, radius, samplePoints, progress, seen, out
                );
            }
        }
        return out;
    }

    private static void discoverVillageVariants(
            RconClient rcon,
            String dimension,
            int centerX,
            int centerZ,
            int radius,
            List<Point> samplePoints,
            ProgressPrinter progress,
            Set<String> seen,
            List<StructureStart> out
    ) throws IOException {
        List<String> variants = List.of(
                "minecraft:village_plains",
                "minecraft:village_desert",
                "minecraft:village_savanna",
                "minecraft:village_snowy",
                "minecraft:village_taiga"
        );
        for (String variant : variants) {
            for (Point p : samplePoints) {
                String response = rcon.command("execute in " + dimension + " positioned " + p.x + " 80 " + p.z + " run locate structure " + variant);
                Coords coords = parseLocateCoords(response);
                if (coords != null && dist2(centerX, centerZ, coords.x, coords.z) <= (long) radius * radius) {
                    String key = "minecraft:village" + "|" + (coords.x >> 4) + "|" + (coords.z >> 4);
                    if (seen.add(key)) {
                        StructureStart start = new StructureStart();
                        start.id = "minecraft:village";
                        start.dimension = dimension;
                        start.x = coords.x;
                        start.y = coords.y != null ? coords.y : 0;
                        start.z = coords.z;
                        out.add(start);
                    }
                }
                if (progress != null) {
                    progress.step("locating minecraft:village");
                }
            }
        }
    }

    private static List<StructureStart> discoverStructureStartsViaPlugin(
            RconClient rcon,
            Path runDir,
            String dimension,
            int centerX,
            int centerZ,
            int radius,
            List<String> structureFilter,
            int locateStep
    ) throws Exception {
        List<StructureStart> out = new ArrayList<>();
        if (structureFilter == null || structureFilter.isEmpty()) {
            return out;
        }
        Path pluginDataDir = runDir.resolve("plugins").resolve("LootProbePaperPlugin").resolve("out");
        Files.createDirectories(pluginDataDir);
        String token = "discover-" + Instant.now().toEpochMilli();
        String relativeOut = "out/" + token + ".json";
        Path outFile = pluginDataDir.resolve(token + ".json");
        Files.deleteIfExists(outFile);

        StringBuilder cmd = new StringBuilder("lootprobe_discover ")
                .append(dimension).append(' ')
                .append(centerX).append(' ')
                .append(centerZ).append(' ')
                .append(radius).append(' ')
                .append(Math.max(128, locateStep)).append(' ')
                .append(relativeOut);
        for (String id : structureFilter) {
            cmd.append(' ').append(id);
        }

        String response = rcon.command(cmd.toString());
        if (looksUnknownCommand(response)) {
            response = rcon.command("lootprobepaperplugin:" + cmd);
        }
        if (looksUnknownCommand(response)) {
            return out;
        }
        if (!waitForFile(outFile, 30)) {
            return out;
        }

        ObjectMapper mapper = new ObjectMapper();
        PluginDiscoverDump dump = mapper.readValue(outFile.toFile(), PluginDiscoverDump.class);
        if (dump == null || dump.starts == null) {
            return out;
        }
        for (DiscoverStart s : dump.starts) {
            if (s == null || s.id == null) {
                continue;
            }
            StructureStart start = new StructureStart();
            start.id = s.id;
            start.dimension = dimension;
            start.x = s.x;
            start.y = s.y;
            start.z = s.z;
            out.add(start);
        }
        return out;
    }

    private static List<Point> buildSamplePoints(int centerX, int centerZ, int radius, int locateStep) {
        List<Point> points = new ArrayList<>();
        int step = Math.max(128, locateStep);
        for (int x = centerX - radius; x <= centerX + radius; x += step) {
            for (int z = centerZ - radius; z <= centerZ + radius; z += step) {
                if (dist2(centerX, centerZ, x, z) <= (long) radius * radius) {
                    points.add(new Point(x, z));
                }
            }
        }
        if (points.isEmpty()) {
            points.add(new Point(centerX, centerZ));
        }
        return points;
    }

    private static Coords parseLocateCoords(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        Matcher full = XYZ_FULL.matcher(response);
        if (full.find()) {
            return new Coords(
                    Integer.parseInt(full.group(1)),
                    Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3))
            );
        }
        Matcher tilde = XYZ_TILDE_Y.matcher(response);
        if (tilde.find()) {
            return new Coords(
                    Integer.parseInt(tilde.group(1)),
                    null,
                    Integer.parseInt(tilde.group(2))
            );
        }
        Matcher generic = XYZ_GENERIC.matcher(response);
        if (generic.find()) {
            return new Coords(
                    Integer.parseInt(generic.group(1)),
                    null,
                    Integer.parseInt(generic.group(2))
            );
        }
        return null;
    }

    private static String summarizeResponse(String response) {
        if (response == null) {
            return "<null>";
        }
        String oneLine = response.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }

    private static String shortType(String id) {
        int idx = id.lastIndexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    private static int dimensionOrder(String dimension) {
        if (dimension == null) {
            return 99;
        }
        return switch (dimension) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            default -> 3;
        };
    }

    private static long dist2(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private record Coords(int x, Integer y, int z) {
    }

    private record Point(int x, int z) {
    }

    private static final class StructureStart {
        String id;
        String dimension;
        int x;
        int y;
        int z;
    }

    private static final class ActiveExtractJob {
        StructureStart start;
        String jobId;
        Path outFile;
        int effectiveChunkRadius;
        long deadlineMs;
    }

    public static final class ScanReport {
        public long seed;
        public String dimension;
        public int centerX;
        public int centerZ;
        public int radius;
        public int locateSampleCount;
        public int totalChunksRequested;
        public int totalChunksAlreadyLoaded;
        public int totalChunksNewlyLoaded;
        public int totalChunksAlreadyGenerated;
        public int totalChunksNewlyGenerated;
        public List<ScannedStructure> structures = new ArrayList<>();
    }

    public static final class ScannedStructure {
        public String id;
        public String dimension;
        public String type;
        public int x;
        public int y;
        public int z;
        public String error;
        public ChunkStats chunkStats = new ChunkStats();
        public List<ChestData> chests = new ArrayList<>();
    }

    public static final class PluginStructureDump {
        public String structureId;
        public String dimension;
        public int centerX;
        public int centerZ;
        public ChunkStats chunkStats = new ChunkStats();
        public List<ChestData> chests = new ArrayList<>();
    }

    public static final class PluginDiscoverDump {
        public String dimension;
        public int centerX;
        public int centerZ;
        public int radius;
        public int locateStep;
        public int samplePoints;
        public List<DiscoverStart> starts = new ArrayList<>();
    }

    public static final class DiscoverStart {
        public String id;
        public int x;
        public int y;
        public int z;
    }

    public static final class ChunkStats {
        public int requested;
        public int alreadyLoaded;
        public int newlyLoaded;
        public int alreadyGenerated;
        public int newlyGenerated;
    }

    public static final class ChestData {
        public int x;
        public int y;
        public int z;
        public String blockId;
        public String lootTable;
        public Long lootTableSeed;
        public String rawLootCommandResponse;
        public List<LootSampler.ItemStackData> items = new ArrayList<>();
    }

    public static final class DiscoveryCacheFile {
        public String createdUtc;
        public List<DiscoverStartEntry> starts = new ArrayList<>();
    }

    public static final class DiscoverStartEntry {
        public String id;
        public String dimension;
        public int x;
        public int y;
        public int z;
    }
}
