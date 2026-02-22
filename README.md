# Loot Probe

Loot Probe is a real-runtime Minecraft seed scanning and loot extraction engine. It doesn’t simulate loot, it instead runs a real (lean, modified) server runtime and extracts generated loot directly, including datapack-modified loot tables.

For practical scan ranges (≤10,000 blocks), results are effectively indistinguishable from a live server. At larger ranges, especially with aggressive parallel settings, accuracy can reduce to around ~99.7%, which is still far beyond traditional seed simulators.


![Image](https://i.imgur.com/8JJaMg0.png)


## What This App Means

Most seed tools are great for map-level scouting, but they usually depend on simulation assumptions and limited structure support.  

Loot Probe is built for actual extraction quality:

- It runs against real Minecraft server behavior.
- It can capture structures and chests that simulation-based approaches often miss.
- It extracts real container contents, not guessed loot summaries.

In the project’s benchmark scenario (same seed + same map size vs SeedMapper), Loot Probe produced:

- `+222%` more item stacks
- `+772%` more chests

Why: it is not relying on map simulation only, and it can include structures like Ancient Cities, Villages, and other server-resolved placements/chests.

### Performance Note
 
With parallel scanning enabled, Ancient City collection in a `12,500-block` radius can complete in `~3 minutes` (hardware/config dependent). Scanning all structures in the same radius typically completes in `~30 minutes`. Compared to SeedMapper’s purely simulated approach, the primary tradeoff is time (and running a small temporary server) in exchange for runtime-accurate and truly complete results.

### Parallel Mode

Parallel chunk prefetch is enabled by default in GUI and CLI because it provides a major speedup for real extraction workloads.

- What it does: preloads chunk data for structure extraction in parallel, reducing idle wait between extraction tasks.
- `Parallel Chunk Count`: how many chunk-load jobs are in flight per structure extraction worker.
- `Parallel Structures`: how many structure extraction workers run at the same time.
- Recommended range: use it freely at or below `10,000` radius for best balance.
- Accuracy tradeoff: above `10,000` radius, very aggressive parallel extraction can reduce effective accuracy to roughly `~99.7%`.
- Test note: in the project’s runs, both values were maxed (`12` chunk count, `8` structures) and remained stable/usable. Reducing either value can improve extraction accuracy consistency.

## Feature: Datapack Loot Extraction

This is one of Loot Probe's biggest differentiators:

- It can load datapacks and extract chest contents against datapack-modified loot tables.
- It is not limited to vanilla table assumptions when a world/server changes loot behavior.
- It can auto-include datapack-added structures so those containers are scanned too.

Meaning: if your server pack changes chest tables (or adds structure content paths), Loot Probe can report what actually exists there, not just vanilla expectations.

## Practical In-Game Real Use

Coupled with [Wurst7-CevAPI](https://github.com/cev-api/Wurst7-CevAPI) and its LootSearch hack you're able to find, search and waypoint every chest you've scanned sorted by nearest to the player.

![In Use](https://i.imgur.com/pJRVSMC.png)

## How It Works

Loot Probe combines:

1. Java CLI/GUI app (`src/main/java/dev/lootprobe`)
2. Coupled Paper plugin for chunk/chest extraction (`paper-plugin`)
3. Optional Cubiomes native bridge for fast biome map + structure preview in GUI

It does targeted scanning and extraction, then writes JSON results for browsing/exporting.

## Build Metadata

- Project: `LootProbe`
- Group: `dev.lootprobe`
- Main artifact: `lootprobe`
- Plugin artifact: `lootprobe-paper-plugin`
- Version: `0.1.0`
- Author: `CevAPI`

## Coupled Paper Plugin (Required For Real Extraction)

Loot Probe's extraction pipeline is coupled to the Paper plugin.  
The main app orchestrates scanning, but real structure/chest extraction is executed through the plugin inside a running Paper server.

What this means in practice:

- If you want real chest contents from actual generated structures, the Paper plugin is required.
- Without the plugin, you can still use non-extraction features (GUI browsing, JSON browsing, config/edit workflows), but region extraction/probe results that depend on server runtime cannot run correctly.
- The plugin is versioned with this repo and should be built from the same source state as the main app to avoid protocol/format drift.

Plugin responsibilities:

- Receive scan tasks from the main process.
- Load/generate chunks for target regions/structures.
- Enumerate containers/chests and serialize real item stacks back to the app.
- Respect datapack-modified loot behavior because extraction happens in server runtime context.

## Custom Cubiomes Bridge DLL

LootProbe includes custom native bridge code that links the Java app to Cubiomes:

- Bridge source: `native/cubiomes/cubiomes_bridge.c`
- Bridge build script: `native/cubiomes/build-bridge.ps1`
- Bridge output: `cubiomes_bridge.dll` (repo root at build time)

This bridge is project code (not a third-party binary drop-in). It exports the functions used by LootProbe for:

- Biome map rendering (`lp_render_map`)
- Vanilla structure preview generation (`lp_generate_structures`)
- Native library initialization/error reporting (`lp_init`, `lp_last_error`)

### Compile `cubiomes_bridge.dll`

Prereq: GCC on PATH (MinGW-w64 is fine).

```powershell
powershell -ExecutionPolicy Bypass -File native\cubiomes\build-bridge.ps1
```

The script compiles:

- `native/cubiomes/cubiomes_bridge.c` -> `cubiomes_bridge.dll`

### About `cubiomes.dll` (bring your own is best)

You should provide your own `cubiomes.dll` build/release matching your environment, instead of relying on random binaries.

Requirements for compatibility:

- Same architecture as JVM/bridge (`x64` with `x64`)
- Exports required Cubiomes symbols used by the bridge (`setupGenerator`, `applySeed`, `getBiomeAt`, etc.)
- Placed where LootProbe can load it (typically beside `lootprobe-0.1.0.jar` and `cubiomes_bridge.dll`)

If `cubiomes.dll` is missing or incompatible, native map/preview features fail and the app will report bridge load/symbol errors.

## Features

- Seed scan by radius (`center x/z + radius`)
- Structure targeting per dimension
- Chest extraction with item stacks
- Datapack loot extraction (`--datapack`) with datapack structure discovery (`--auto-datapack-structures`)
- GUI with interactive map, selection sync, zoom/pan, icon scale
- CLI modes for automation
- Terminal JSON browser (`browse`)

## Requirements

- Java `21+`
- Maven `3.9+`
- Paper-compatible server jar (required for scan extraction)
- Built `lootprobe-paper-plugin` jar from `paper-plugin` (required for scan extraction)
- (Optional GUI map acceleration) `cubiomes.dll` + `cubiomes_bridge.dll`

## Build

Build Paper plugin:

```powershell
cd paper-plugin
mvn -DskipTests package
cd ..
```

Expected plugin artifact:

- `paper-plugin/target/lootprobe-paper-plugin-<version>.jar`

Build main app:

```powershell
mvn -DskipTests clean package
```

Main output jar:

- `target/lootprobe-0.1.0.jar`

## CLI Commands

Top-level:

```powershell
java -jar target\lootprobe-0.1.0.jar --help
```

Available commands:

- `probe` : run scan/probe workflow
- `gui` : launch desktop GUI
- `browse` : browse existing JSON result file

### `probe` command

```powershell
java -jar target\lootprobe-0.1.0.jar probe --help
```

Important options and meanings:

- `--seed` (required): world seed
- `--mc-version`: MC version string (ex: `1.21.11`)
- `--server-jar`: local server jar path
- `--output`: result JSON output file
- `--work-dir`: custom temp/run dir
- `--startup-timeout-sec`: server startup timeout
- `--structure`: structure id to target (repeatable)
- `--structure-dimension`: dimension for locate mode
- `--loot-table`: loot table id to sample (repeatable)
- `--loot-dimension`: dimension for loot sampling
- `--samples`: loot sample iterations per table

Scan mode (bounded region):

- `--scan-center-x`
- `--scan-center-z`
- `--scan-radius`
- `--scan-dimension`
- `--paper-plugin-jar` (path to coupled plugin jar; if omitted, app attempts default repo path)

Datapacks:

- `--datapack` (repeatable path)
- `--auto-datapack-structures=true|false`: auto-include structure ids discovered from loaded datapacks

Performance controls:

- `--locate-step` (larger = faster, less exhaustive)
- `--extract-chunk-radius`
- `--extract-parallel-chunks=true|false` (default: `true`; consider disabling for max accuracy beyond `10,000` radius)
- `--extract-parallel-chunk-count` (parallel chunk-load depth per structure worker; higher is faster, lower can improve consistency)
- `--extract-parallel-structures` (number of structure workers running simultaneously; higher is faster, lower can improve consistency)
- `--extract-timeout-sec`
- `--max-structures`
- `--ultra-lean=true|false`

### `browse` command

```powershell
java -jar target\lootprobe-0.1.0.jar browse --input result.json
```

Interactive commands inside browser:

- `summary`
- `list`
- `show <idx>`
- `items <idx>`
- `find <text>`
- `help`
- `quit`

### `gui` command

```powershell
java -jar target\lootprobe-0.1.0.jar gui
```

GUI notes:

- `Results` tab is in the same left-side tab strip as `Core / Scan / Targets`
- `Map Icon Scale` is in `Scan` settings
- Datapack extraction is configured in the `Datapack` field on the run page; use `Auto include datapack structures` in `Scan` for automatic structure target expansion

## Practical Examples

### 1) Ancient City scan in radius 12500 (parallel tuned)

```powershell
java -jar target\lootprobe-0.1.0.jar probe `
  --mc-version 1.21.11 `
  --server-jar Z:\path\paper-1.21.11-111.jar `
  --seed 1966547910137014246 `
  --scan-dimension minecraft:overworld `
  --scan-center-x 0 `
  --scan-center-z 0 `
  --scan-radius 12500 `
  --structure minecraft:ancient_city `
  --paper-plugin-jar paper-plugin\target\lootprobe-paper-plugin-0.1.0.jar `
  --extract-parallel-chunks=true `
  --extract-parallel-chunk-count 12 `
  --extract-parallel-structures 8 `
  --output ancient-city-12500.json
```

### 2) Multi-structure scan (including villages)

```powershell
java -jar target\lootprobe-0.1.0.jar probe `
  --mc-version 1.21.11 `
  --server-jar Z:\path\paper-1.21.11-111.jar `
  --seed 1966547910137014246 `
  --scan-dimension minecraft:overworld `
  --scan-center-x 0 `
  --scan-center-z 0 `
  --scan-radius 12500 `
  --structure minecraft:ancient_city `
  --structure minecraft:village `
  --structure minecraft:trial_chambers `
  --output mixed-structures.json
```

### 3) Datapack-aware scan

```powershell
java -jar target\lootprobe-0.1.0.jar probe `
  --mc-version 1.21.11 `
  --server-jar Z:\path\paper-1.21.11-111.jar `
  --seed 1966547910137014246 `
  --datapack Z:\path\my_datapack.zip `
  --auto-datapack-structures=true `
  --scan-center-x 0 `
  --scan-center-z 0 `
  --scan-radius 12500 `
  --scan-dimension minecraft:overworld `
  --structure minecraft:ancient_city `
  --output datapack-scan.json
```

This mode applies datapack loot table changes during extraction, so output chest contents reflect datapack behavior.

## Result JSON (high-level)

Primary sections:

- `mcVersion`, `seed`, timing/run metadata
- `structures` (locate results)
- `lootSamples` (table sampling results)
- `regionScan` (bounded scan output)
  - discovered structures
  - extracted chests
  - items per chest

## Notes

- Performance and totals vary by seed, version, structure set, and hardware.
- The benchmark percentages in this README are project-reported comparison numbers for same-seed/same-area runs.
- Extraction failures that mention missing plugin, missing plugin channel, or no chest payload usually indicate a bad `--paper-plugin-jar` path or plugin/main version mismatch.
- Safest setup is: rebuild both modules together, then pass the freshly built plugin jar explicitly via `--paper-plugin-jar`.
- For fastest GUI map interaction, keep Cubiomes files beside the jar:
  - `cubiomes.dll`
  - `cubiomes_bridge.dll`
