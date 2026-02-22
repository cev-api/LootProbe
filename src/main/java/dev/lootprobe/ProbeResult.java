package dev.lootprobe;

import java.util.ArrayList;
import java.util.List;

public final class ProbeResult {
    public String startTimeUtc;
    public String finishTimeUtc;
    public long durationMs;
    public double durationSeconds;
    public String mcVersion;
    public long seed;
    public String serverJar;
    public String runRootDir;
    public String serverRunDir;
    public List<String> datapacks = new ArrayList<>();
    public List<StructureLocator.StructureLocation> structures = new ArrayList<>();
    public List<LootSampler.LootSampleSet> lootSamples = new ArrayList<>();
    public WorldChestScanner.ScanReport regionScan;
    public DatapackInspector.DatapackInfluence datapackInfluence;
}
