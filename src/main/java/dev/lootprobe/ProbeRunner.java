package dev.lootprobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class ProbeRunner {
    private static final Object REUSE_LOCK = new Object();
    private static CachedServer cachedServer;

    public ProbeResult run(ProbeConfig config, ProbeListener listener) throws Exception {
        ProbeListener sink = listener != null ? listener : ProbeListener.NO_OP;
        validate(config);
        config.mcVersion = resolveMcVersion(config, sink);

        Instant startedAt = Instant.now();
        sink.onInfo("loot-probe: start (UTC) " + startedAt);
        sink.onInfo("loot-probe: preparing run...");

        sink.onInfo("loot-probe: resolving server jar...");
        Path jar = config.serverJar != null
                ? config.serverJar
                : MojangVersionResolver.downloadServerJar(config.mcVersion, Path.of(".cache"));

        ProbeResult result = new ProbeResult();
        result.startTimeUtc = startedAt.toString();
        result.mcVersion = config.mcVersion;
        result.seed = config.seed;
        result.serverJar = jar.toAbsolutePath().toString();
        result.datapacks = config.datapacks.stream().map(path -> path.toAbsolutePath().toString()).toList();

        sink.onInfo("loot-probe: inspecting datapacks (" + config.datapacks.size() + ")...");
        result.datapackInfluence = DatapackInspector.inspect(config.datapacks);

        boolean scanMode = config.isScanMode();
        Path pluginJarToLoad = scanMode ? resolvePluginJar(config) : null;
        boolean allowReuse = config.reuseServerIfPossible && scanMode;

        ServerSession session = null;
        try {
            if (allowReuse) {
                session = acquireReusableSession(config, sink, jar, pluginJarToLoad);
            } else {
                session = startEphemeralSession(config, sink, jar, pluginJarToLoad);
            }

            result.runRootDir = session.runRoot.toAbsolutePath().toString();
            result.serverRunDir = session.runner.getRunDir().toAbsolutePath().toString();
            sink.onInfo("loot-probe: temp data dir " + result.serverRunDir);
            sink.onInfo(session.reused
                    ? "loot-probe: reusing existing temporary server session."
                    : "loot-probe: started new temporary server session.");
            sink.onInfo("loot-probe: server ready, connecting RCON...");

            try (RconClient rcon = new RconClient("127.0.0.1", session.rconPort, session.rconPassword)) {
                rcon.connectAndLogin();
                if (config.ultraLean) {
                    sink.onInfo("loot-probe: applying ultra-lean runtime profile...");
                    LeanRuntimeConfigurer.apply(
                            rcon,
                            LeanRuntimeConfigurer.dimensionsFor(
                                    config.structureDimension,
                                    config.lootDimension,
                                    config.scanDimension,
                                    scanMode
                            )
                    );
                }

                List<ProbeConfig.StructureTarget> effectiveStructures = resolveEffectiveStructures(config, result.datapackInfluence, scanMode);
                sink.onInfo("loot-probe: effective structures=" + effectiveStructures.size()
                        + " (base=" + baseStructureCount(config)
                        + ", datapackAdded=" + (result.datapackInfluence != null ? result.datapackInfluence.addedStructures.size() : 0)
                        + ", auto=" + config.autoDatapackStructures + ")");
                if (!effectiveStructures.isEmpty()) {
                    sink.onInfo("loot-probe: locating " + effectiveStructures.size() + " structures...");
                    result.structures = StructureLocator.locateAllTargets(rcon, effectiveStructures, config.structureDimension);
                }
                if (!config.lootTables.isEmpty()) {
                    sink.onInfo("loot-probe: sampling " + config.lootTables.size() + " loot tables...");
                    result.lootSamples = LootSampler.sampleAll(rcon, config.lootTables, Math.max(config.samples, 1), config.lootDimension);
                }
                if (scanMode) {
                    sink.onInfo("loot-probe: scanning region and extracting containers...");
                    Path discoveryCacheFile = buildDiscoveryCacheFile(config, effectiveStructures);
                    List<WorldChestScanner.ScannedStructure> resumeStructures = loadResumeStructures(config, sink);
                    result.regionScan = WorldChestScanner.scan(
                            rcon,
                            session.runner.getRunDir(),
                            config.scanCenterX,
                            config.scanCenterZ,
                            config.scanRadius,
                            effectiveStructures,
                            discoveryCacheFile,
                            true,
                            Math.max(128, config.locateStep),
                            Math.max(2, config.extractChunkRadius),
                            config.extractParallelChunks,
                            Math.max(1, config.extractParallelChunkCount),
                            Math.max(1, config.extractParallelStructureJobs),
                            Math.max(10, config.extractTimeoutSec),
                            Math.max(2_000, config.extractStartCommandTimeoutMs),
                            Math.max(2_000, config.extractStatusReadTimeoutMs),
                            config.maxStructures,
                            resumeStructures,
                            new ProgressPrinter.ProgressListener() {
                                @Override
                                public void onProgress(int current, int total, String label) {
                                    sink.onProgress("scan", current, total, label);
                                }

                                @Override
                                public void onInfo(String message) {
                                    sink.onInfo(message);
                                }
                            }
                    );
                    result.regionScan.seed = config.seed;
                }
            }

            Instant finishedAt = Instant.now();
            result.finishTimeUtc = finishedAt.toString();
            result.durationMs = Duration.between(startedAt, finishedAt).toMillis();
            result.durationSeconds = result.durationMs / 1000.0;
            double durationMinutes = result.durationMs / 60_000.0;
            sink.onInfo("loot-probe: finish (UTC) " + finishedAt);
            sink.onInfo(String.format("loot-probe: duration %.2f min (%d ms)", durationMinutes, result.durationMs));

            sink.onInfo("loot-probe: writing output...");
            writeResult(result, config.output);
            sink.onInfo("Wrote result to " + config.output.toAbsolutePath());
            sink.onProgress("complete", 1, 1, "done");
            return result;
        } catch (Exception e) {
            if (session != null && !session.closeOnFinish) {
                synchronized (REUSE_LOCK) {
                    if (cachedServer != null && cachedServer.runner == session.runner) {
                        closeCachedServerLocked(sink, "run failure");
                    }
                }
            }
            throw e;
        } finally {
            if (session != null && session.closeOnFinish) {
                closeSession(session, sink);
            }
        }
    }

    public static void shutdownReusableServer(ProbeListener listener) {
        ProbeListener sink = listener != null ? listener : ProbeListener.NO_OP;
        synchronized (REUSE_LOCK) {
            if (cachedServer == null) {
                return;
            }
            sink.onInfo("loot-probe: shutting down cached server session...");
            closeCachedServerLocked(sink, "application exit");
        }
    }

    private static void closeSession(ServerSession session, ProbeListener sink) {
        try {
            session.runner.close();
        } catch (Exception e) {
            sink.onInfo("loot-probe: server shutdown issue: " + e.getMessage());
        }
        if (session.deleteRunRootOnClose) {
            try {
                deleteRecursively(session.runRoot);
                sink.onInfo("loot-probe: cleaned temp run dir " + session.runRoot);
            } catch (IOException e) {
                sink.onInfo("loot-probe: failed to clean temp run dir " + session.runRoot + " (" + e.getMessage() + ")");
            }
        }
    }

    private static ServerSession acquireReusableSession(
            ProbeConfig config,
            ProbeListener sink,
            Path jar,
            Path pluginJarToLoad
    ) throws Exception {
        ReuseKey key = ReuseKey.from(config, jar, pluginJarToLoad);
        synchronized (REUSE_LOCK) {
            if (cachedServer != null) {
                if (cachedServer.key.equals(key) && cachedServer.runner.isAlive()) {
                    return new ServerSession(
                            cachedServer.runner,
                            cachedServer.runRoot,
                            cachedServer.rconPort,
                            cachedServer.rconPassword,
                            false,
                            cachedServer.deleteRunRootOnClose,
                            true
                    );
                }
                closeCachedServerLocked(sink, "server session changed");
            }

            Path runRoot = config.workDir != null
                    ? Files.createDirectories(config.workDir)
                    : Files.createTempDirectory("loot-probe-");
            boolean deleteRunRoot = config.workDir == null;
            String rconPassword = "lootprobe-" + System.nanoTime();
            int rconPort = 25590 + (int) (Math.abs(System.nanoTime()) % 500);

            sink.onInfo("loot-probe: starting temporary server...");
            MinecraftServerRunner runner = new MinecraftServerRunner(
                    config.javaBin,
                    jar,
                    runRoot.resolve("server-run"),
                    config.seed,
                    config.datapacks,
                    rconPort,
                    rconPassword,
                    config.startupTimeout(),
                    pluginJarToLoad,
                    config.scanDimension,
                    sink::onServerLog
            );
            try {
                runner.start();
            } catch (Exception e) {
                try {
                    runner.close();
                } catch (Exception ignored) {
                }
                if (deleteRunRoot) {
                    try {
                        deleteRecursively(runRoot);
                    } catch (IOException ignored) {
                    }
                }
                throw e;
            }

            cachedServer = new CachedServer(key, runner, runRoot, rconPort, rconPassword, deleteRunRoot);
            return new ServerSession(runner, runRoot, rconPort, rconPassword, false, deleteRunRoot, false);
        }
    }

    private static ServerSession startEphemeralSession(
            ProbeConfig config,
            ProbeListener sink,
            Path jar,
            Path pluginJarToLoad
    ) throws Exception {
        Path runRoot = config.workDir != null
                ? Files.createDirectories(config.workDir)
                : Files.createTempDirectory("loot-probe-");
        boolean deleteRunRoot = config.workDir == null;
        String rconPassword = "lootprobe-" + System.nanoTime();
        int rconPort = 25590 + (int) (Math.abs(System.nanoTime()) % 500);

        sink.onInfo("loot-probe: starting temporary server...");
        MinecraftServerRunner runner = new MinecraftServerRunner(
                config.javaBin,
                jar,
                runRoot.resolve("server-run"),
                config.seed,
                config.datapacks,
                rconPort,
                rconPassword,
                config.startupTimeout(),
                pluginJarToLoad,
                config.scanDimension,
                sink::onServerLog
        );
        try {
            runner.start();
        } catch (Exception e) {
            try {
                runner.close();
            } catch (Exception ignored) {
            }
            if (deleteRunRoot) {
                try {
                    deleteRecursively(runRoot);
                } catch (IOException ignored) {
                }
            }
            throw e;
        }
        return new ServerSession(runner, runRoot, rconPort, rconPassword, true, deleteRunRoot, false);
    }

    private static void closeCachedServerLocked(ProbeListener sink, String reason) {
        if (cachedServer == null) {
            return;
        }
        sink.onInfo("loot-probe: closing cached server session (" + reason + ")...");
        try {
            cachedServer.runner.close();
        } catch (Exception e) {
            sink.onInfo("loot-probe: cached server shutdown issue: " + e.getMessage());
        }
        if (cachedServer.deleteRunRootOnClose) {
            try {
                deleteRecursively(cachedServer.runRoot);
                sink.onInfo("loot-probe: cleaned temp run dir " + cachedServer.runRoot);
            } catch (IOException e) {
                sink.onInfo("loot-probe: failed to clean temp run dir " + cachedServer.runRoot + " (" + e.getMessage() + ")");
            }
        }
        cachedServer = null;
    }

    private static void validate(ProbeConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Probe config is required.");
        }
        if (config.output == null) {
            throw new IllegalArgumentException("output path is required.");
        }
    }

    private static String resolveMcVersion(ProbeConfig config, ProbeListener sink) throws IOException {
        String configured = config.mcVersion != null ? config.mcVersion.trim() : "";
        if (config.serverJar == null) {
            if (configured.isBlank()) {
                throw new IllegalArgumentException("mcVersion is required when serverJar is not set.");
            }
            return configured;
        }

        var detected = MinecraftVersionDetector.detect(config.serverJar);
        if (detected.isPresent()) {
            String resolved = detected.get();
            if (configured.isBlank()) {
                sink.onInfo("loot-probe: detected MC version from server jar: " + resolved);
            } else if (!resolved.equals(configured)) {
                sink.onInfo("loot-probe: server jar MC version " + resolved + " overrides configured " + configured + ".");
            }
            return resolved;
        }

        if (!configured.isBlank()) {
            sink.onInfo("loot-probe: could not detect MC version from server jar, using configured value " + configured + ".");
            return configured;
        }
        throw new IllegalArgumentException("Could not detect MC version from server jar " + config.serverJar.toAbsolutePath()
                + ". Provide --mc-version explicitly.");
    }

    private static Path resolvePluginJar(ProbeConfig config) {
        if (config.paperPluginJar != null) {
            return config.paperPluginJar.toAbsolutePath();
        }
        Path fallback = Path.of("paper-plugin", "target", "lootprobe-paper-plugin-0.1.0.jar").toAbsolutePath();
        if (Files.exists(fallback)) {
            return fallback;
        }
        throw new IllegalArgumentException("Scan mode requires --paper-plugin-jar or built plugin at " + fallback);
    }

    private static List<ProbeConfig.StructureTarget> resolveEffectiveStructures(
            ProbeConfig config,
            DatapackInspector.DatapackInfluence influence,
            boolean scanMode
    ) {
        Set<ProbeConfig.StructureTarget> merged = new LinkedHashSet<>(baseStructureTargets(config));
        if (scanMode && config.autoDatapackStructures && influence != null) {
            for (String id : influence.addedStructures) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                merged.add(new ProbeConfig.StructureTarget(id, config.scanDimension));
            }
        }
        return new ArrayList<>(merged);
    }

    private static int baseStructureCount(ProbeConfig config) {
        return baseStructureTargets(config).size();
    }

    private static List<ProbeConfig.StructureTarget> baseStructureTargets(ProbeConfig config) {
        List<ProbeConfig.StructureTarget> out = new ArrayList<>();
        if (config.structureTargets != null && !config.structureTargets.isEmpty()) {
            for (ProbeConfig.StructureTarget target : config.structureTargets) {
                if (target == null || target.id == null || target.id.isBlank()) {
                    continue;
                }
                out.add(new ProbeConfig.StructureTarget(target.id.trim(), target.normalizedDimension(config.structureDimension)));
            }
            return out;
        }
        for (String id : config.structures) {
            if (id != null && !id.isBlank()) {
                out.add(new ProbeConfig.StructureTarget(id.trim(), config.structureDimension));
            }
        }
        return out;
    }

    private static Path buildDiscoveryCacheFile(ProbeConfig config, List<ProbeConfig.StructureTarget> effectiveStructures) {
        List<String> targetKeys = effectiveStructures.stream()
                .map(t -> t.normalizedDimension("minecraft:overworld") + "|" + t.id)
                .sorted()
                .toList();
        List<String> datapackKeys = config.datapacks.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .map(p -> {
                    try {
                        long size = Files.exists(p) ? Files.size(p) : -1L;
                        long ts = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1L;
                        return p + "|" + size + "|" + ts;
                    } catch (IOException e) {
                        return p + "|-1|-1";
                    }
                })
                .sorted()
                .toList();

        String keyBody = String.join("\n", List.of(
                "mcVersion=" + config.mcVersion,
                "seed=" + config.seed,
                "centerX=" + config.scanCenterX,
                "centerZ=" + config.scanCenterZ,
                "radius=" + config.scanRadius,
                "locateStep=" + config.locateStep,
                "targets=" + String.join(",", targetKeys),
                "datapacks=" + String.join(",", datapackKeys)
        ));
        String hash = sha256Hex(keyBody);
        return Path.of(".lootprobe-cache", "discovery", "starts-" + hash + ".json").toAbsolutePath();
    }

    private static List<WorldChestScanner.ScannedStructure> loadResumeStructures(ProbeConfig config, ProbeListener sink) {
        Path output = config.output != null ? config.output.toAbsolutePath() : null;
        if (output == null || !Files.exists(output)) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            ProbeResult existing = mapper.readValue(output.toFile(), ProbeResult.class);
            if (existing == null || existing.regionScan == null || existing.regionScan.structures == null || existing.regionScan.structures.isEmpty()) {
                return List.of();
            }
            if (existing.seed != config.seed) {
                sink.onInfo("loot-probe: resume skipped (seed mismatch with existing output).");
                return List.of();
            }
            if (existing.mcVersion != null && !existing.mcVersion.equals(config.mcVersion)) {
                sink.onInfo("loot-probe: resume skipped (MC version mismatch with existing output).");
                return List.of();
            }
            sink.onInfo("loot-probe: resume found " + existing.regionScan.structures.size() + " prior structures in output.");
            return existing.regionScan.structures;
        } catch (Exception e) {
            sink.onInfo("loot-probe: resume skipped (could not read existing output: " + e.getMessage() + ").");
            return List.of();
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static void writeResult(ProbeResult result, Path output) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(output.toFile(), result);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static final class ServerSession {
        final MinecraftServerRunner runner;
        final Path runRoot;
        final int rconPort;
        final String rconPassword;
        final boolean closeOnFinish;
        final boolean deleteRunRootOnClose;
        final boolean reused;

        private ServerSession(
                MinecraftServerRunner runner,
                Path runRoot,
                int rconPort,
                String rconPassword,
                boolean closeOnFinish,
                boolean deleteRunRootOnClose,
                boolean reused
        ) {
            this.runner = runner;
            this.runRoot = runRoot;
            this.rconPort = rconPort;
            this.rconPassword = rconPassword;
            this.closeOnFinish = closeOnFinish;
            this.deleteRunRootOnClose = deleteRunRootOnClose;
            this.reused = reused;
        }
    }

    private static final class CachedServer {
        final ReuseKey key;
        final MinecraftServerRunner runner;
        final Path runRoot;
        final int rconPort;
        final String rconPassword;
        final boolean deleteRunRootOnClose;

        private CachedServer(
                ReuseKey key,
                MinecraftServerRunner runner,
                Path runRoot,
                int rconPort,
                String rconPassword,
                boolean deleteRunRootOnClose
        ) {
            this.key = key;
            this.runner = runner;
            this.runRoot = runRoot;
            this.rconPort = rconPort;
            this.rconPassword = rconPassword;
            this.deleteRunRootOnClose = deleteRunRootOnClose;
        }
    }

    private record ReuseKey(
            String mcVersion,
            long seed,
            String javaBin,
            String serverJar,
            List<String> datapacks,
            String pluginJar,
            boolean ultraLean
    ) {
        static ReuseKey from(ProbeConfig config, Path jar, Path pluginJar) {
            List<String> packs = config.datapacks.stream()
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .sorted()
                    .toList();
            String plugin = pluginJar != null ? pluginJar.toAbsolutePath().normalize().toString() : "";
            return new ReuseKey(
                    config.mcVersion,
                    config.seed,
                    Objects.toString(config.javaBin, "java"),
                    jar.toAbsolutePath().normalize().toString(),
                    packs,
                    plugin,
                    config.ultraLean
            );
        }
    }
}
