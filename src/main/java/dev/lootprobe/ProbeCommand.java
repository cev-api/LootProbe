package dev.lootprobe;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "probe", description = "Run structure and loot probes against a temporary server world.")
public final class ProbeCommand implements Callable<Integer> {

    @Option(names = "--mc-version", description = "Minecraft version (example: 1.21.1). Optional when --server-jar is set.")
    private String mcVersion;

    @Option(names = "--seed", required = true, description = "World seed as signed long")
    private long seed;

    @Option(names = "--server-jar", description = "Use local server jar instead of downloading official one")
    private Path serverJar;

    @Option(names = "--datapack", description = "Path to datapack folder/zip; can be set multiple times")
    private List<Path> datapacks = new ArrayList<>();

    @Option(names = "--structure", description = "Structure id to locate; can be set multiple times")
    private List<String> structures = new ArrayList<>();

    @Option(names = "--structure-dimension", defaultValue = "minecraft:overworld", description = "Dimension for locate commands (minecraft:overworld|minecraft:the_nether|minecraft:the_end)")
    private String structureDimension;

    @Option(names = "--loot-table", description = "Loot table id to sample; can be set multiple times")
    private List<String> lootTables = new ArrayList<>();

    @Option(names = "--loot-dimension", defaultValue = "minecraft:overworld", description = "Dimension for loot sampling")
    private String lootDimension;

    @Option(names = "--samples", defaultValue = "3", description = "Sample runs for each loot table")
    private int samples;

    @Option(names = "--java-bin", defaultValue = "java", description = "Java executable used to launch server")
    private String javaBin;

    @Option(names = "--work-dir", description = "Directory for temp run files; default is system temp dir")
    private Path workDir;

    @Option(names = "--output", defaultValue = "probe-result.json", description = "Output JSON file path")
    private Path output;

    @Option(names = "--startup-timeout-sec", defaultValue = "180", description = "Server startup timeout in seconds")
    private int startupTimeoutSec;

    @Option(names = "--scan-center-x", description = "If set with --scan-center-z and --scan-radius, runs bounded world scan mode")
    private Integer scanCenterX;

    @Option(names = "--scan-center-z", description = "If set with --scan-center-x and --scan-radius, runs bounded world scan mode")
    private Integer scanCenterZ;

    @Option(names = "--scan-radius", description = "Bounded scan radius in blocks around scan center")
    private Integer scanRadius;

    @Option(names = "--scan-dimension", defaultValue = "minecraft:overworld", description = "Dimension for bounded scan mode")
    private String scanDimension;

    @Option(names = "--paper-plugin-jar", description = "LootProbe Paper plugin jar path (required for scan mode unless default path exists)")
    private Path paperPluginJar;

    @Option(
            names = "--auto-datapack-structures",
            defaultValue = "false",
            fallbackValue = "true",
            arity = "0..1",
            description = "In scan mode, include structures added by datapacks (use --auto-datapack-structures=true)"
    )
    private boolean autoDatapackStructures;

    @Option(names = "--locate-step", defaultValue = "768", description = "Locate sample grid step in blocks (bigger = faster, less exhaustive)")
    private int locateStep;

    @Option(names = "--extract-chunk-radius", defaultValue = "5", description = "Chunk radius around each structure center to generate/extract (smaller = faster)")
    private int extractChunkRadius;

    @Option(
            names = "--extract-parallel-chunks",
            defaultValue = "true",
            fallbackValue = "true",
            arity = "0..1",
            description = "Enable parallel chunk prefetch during extraction (faster). Not recommended above 10,000 radius when maximum accuracy is required (~99.7% at larger ranges)"
    )
    private boolean extractParallelChunks;

    @Option(names = "--extract-parallel-chunk-count", defaultValue = "4", description = "Max in-flight chunks per structure job when --extract-parallel-chunks is enabled")
    private int extractParallelChunkCount;

    @Option(names = "--extract-parallel-structures", defaultValue = "1", description = "Number of structure extraction jobs to run in parallel")
    private int extractParallelStructureJobs;

    @Option(names = "--extract-timeout-sec", defaultValue = "90", description = "Per-structure extraction timeout in seconds (slow ones are skipped)")
    private int extractTimeoutSec;

    @Option(names = "--extract-start-timeout-ms", defaultValue = "8000", description = "RCON timeout for extract-start command response in milliseconds")
    private int extractStartTimeoutMs;

    @Option(names = "--extract-status-timeout-ms", defaultValue = "12000", description = "RCON timeout for extract-status polling response in milliseconds")
    private int extractStatusTimeoutMs;

    @Option(names = "--max-structures", description = "Optional cap on number of discovered structures to extract (nearest first)")
    private Integer maxStructures;

    @Option(names = "--ultra-lean", defaultValue = "true", description = "Aggressively disable non-essential gameplay systems while probing")
    private boolean ultraLean;

    @Override
    public Integer call() throws Exception {
        ProbeConfig config = new ProbeConfig();
        config.mcVersion = mcVersion;
        config.seed = seed;
        config.serverJar = serverJar;
        config.datapacks = new ArrayList<>(datapacks);
        config.structures = new ArrayList<>(structures);
        config.structureTargets = new ArrayList<>();
        for (String id : structures) {
            if (id != null && !id.isBlank()) {
                config.structureTargets.add(new ProbeConfig.StructureTarget(id.trim(), structureDimension));
            }
        }
        config.structureDimension = structureDimension;
        config.lootTables = new ArrayList<>(lootTables);
        config.lootDimension = lootDimension;
        config.samples = samples;
        config.javaBin = javaBin;
        config.workDir = workDir;
        config.output = output;
        config.startupTimeoutSec = startupTimeoutSec;
        config.scanCenterX = scanCenterX;
        config.scanCenterZ = scanCenterZ;
        config.scanRadius = scanRadius;
        config.scanDimension = scanDimension;
        config.paperPluginJar = paperPluginJar;
        config.autoDatapackStructures = autoDatapackStructures;
        config.locateStep = locateStep;
        config.extractChunkRadius = extractChunkRadius;
        config.extractParallelChunks = extractParallelChunks;
        config.extractParallelChunkCount = extractParallelChunkCount;
        config.extractParallelStructureJobs = extractParallelStructureJobs;
        config.extractTimeoutSec = extractTimeoutSec;
        config.extractStartCommandTimeoutMs = extractStartTimeoutMs;
        config.extractStatusReadTimeoutMs = extractStatusTimeoutMs;
        config.maxStructures = maxStructures;
        config.ultraLean = ultraLean;

        ProbeRunner runner = new ProbeRunner();
        runner.run(config, new ProbeListener() {
            @Override
            public void onInfo(String message) {
                System.out.println(message);
            }

            @Override
            public void onServerLog(String line) {
                System.out.println("[server] " + line);
            }
        });
        return 0;
    }
}
