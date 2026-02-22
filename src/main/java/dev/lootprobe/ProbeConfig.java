package dev.lootprobe;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProbeConfig {
    public String mcVersion;
    public long seed;
    public Path serverJar;
    public List<Path> datapacks = new ArrayList<>();
    public List<String> structures = new ArrayList<>();
    public String structureDimension = "minecraft:overworld";
    public List<String> lootTables = new ArrayList<>();
    public String lootDimension = "minecraft:overworld";
    public int samples = 3;
    public String javaBin = "java";
    public Path workDir;
    public Path output = Path.of("probe-result.json");
    public int startupTimeoutSec = 180;
    public Integer scanCenterX;
    public Integer scanCenterZ;
    public Integer scanRadius;
    public String scanDimension = "minecraft:overworld";
    public Path paperPluginJar;
    public boolean autoDatapackStructures = true;
    public int locateStep = 768;
    public int extractChunkRadius = 5;
    public boolean extractParallelChunks = true;
    public int extractParallelChunkCount = 4;
    public int extractParallelStructureJobs = 1;
    public int extractTimeoutSec = 90;
    public int extractStartCommandTimeoutMs = 8_000;
    public int extractStatusReadTimeoutMs = 12_000;
    public Integer maxStructures;
    public boolean ultraLean = true;
    public boolean reuseServerIfPossible = false;
    public List<StructureTarget> structureTargets = new ArrayList<>();

    public boolean isScanMode() {
        return scanCenterX != null && scanCenterZ != null && scanRadius != null && scanRadius > 0;
    }

    public Duration startupTimeout() {
        return Duration.ofSeconds(Math.max(startupTimeoutSec, 1));
    }

    public static final class StructureTarget {
        public String id;
        public String dimension;

        public StructureTarget() {
        }

        public StructureTarget(String id, String dimension) {
            this.id = id;
            this.dimension = dimension;
        }

        public String normalizedDimension(String fallback) {
            String d = dimension != null && !dimension.isBlank() ? dimension : fallback;
            return (d != null && !d.isBlank()) ? d : "minecraft:overworld";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StructureTarget that)) return false;
            return Objects.equals(id, that.id) && Objects.equals(dimension, that.dimension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, dimension);
        }
    }
}
