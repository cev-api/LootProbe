package dev.lootprobe.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StructureSearchResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class LootProbePaperPlugin extends JavaPlugin implements CommandExecutor {
    private static final int CHEST_ATTACH_RADIUS = 256;
    private static final int MAX_JOB_TICKS = 20 * 60; // 60s hard cap per job
    private static final int PARALLEL_JOB_DEFAULT_IN_FLIGHT_CHUNKS = 4;
    private static final int PARALLEL_JOB_MAX_IN_FLIGHT_CHUNKS = 12;
    private static final int ZERO_SEED_LOG_EXAMPLE_LIMIT = 12;
    private static final int UNSUPPORTED_CONTEXT_LOG_EXAMPLE_LIMIT = 12;
    private static final int POST_LOAD_SETTLE_TICKS = 2;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, ExtractJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (getCommand("lootprobe_extract") != null) {
            getCommand("lootprobe_extract").setExecutor(this);
        }
        if (getCommand("lootprobe_extract_start") != null) {
            getCommand("lootprobe_extract_start").setExecutor(this);
        }
        if (getCommand("lootprobe_extract_status") != null) {
            getCommand("lootprobe_extract_status").setExecutor(this);
        }
        if (getCommand("lootprobe_discover") != null) {
            getCommand("lootprobe_discover").setExecutor(this);
        }
    }

    @Override
    public void onDisable() {
        for (ExtractJob job : jobs.values()) {
            if (job.task != null) {
                job.task.cancel();
            }
        }
        jobs.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "lootprobe_discover" -> handleDiscover(sender, args);
            case "lootprobe_extract_start" -> handleExtractStart(sender, args);
            case "lootprobe_extract_status" -> handleExtractStatus(sender, args);
            case "lootprobe_extract" -> handleExtractLegacy(sender, args);
            default -> true;
        };
    }

    private boolean handleExtractLegacy(CommandSender sender, String[] args) {
        // Backward-compatible legacy entrypoint.
        if (args.length < 6) {
            sender.sendMessage("Usage: /lootprobe_extract <dimension> <structureId> <centerX> <centerZ> <chunkRadius> <relativeOutputFile> [parallelChunks] [parallelChunkCount]");
            return true;
        }
        String response = startExtractJob(args);
        sender.sendMessage(response);
        return true;
    }

    private boolean handleExtractStart(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage("Usage: /lootprobe_extract_start <dimension> <structureId> <centerX> <centerZ> <chunkRadius> <relativeOutputFile> [parallelChunks] [parallelChunkCount]");
            return true;
        }
        sender.sendMessage(startExtractJob(args));
        return true;
    }

    private boolean handleExtractStatus(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("Usage: /lootprobe_extract_status <jobId>");
            return true;
        }
        ExtractJob job = jobs.get(args[0]);
        if (job == null) {
            sender.sendMessage("not_found");
            return true;
        }
        if (job.state == JobState.RUNNING) {
            sender.sendMessage("running " + job.completedChunks + "/" + job.totalChunks);
            return true;
        }
        if (job.state == JobState.FAILED) {
            sender.sendMessage("failed " + (job.error != null ? job.error : "unknown"));
            return true;
        }
        sender.sendMessage("done " + job.relativeOut + " " + job.completedChunks + "/" + job.totalChunks);
        return true;
    }

    private String startExtractJob(String[] args) {
        String dimension = args[0];
        String structureId = args[1];
        int centerX;
        int centerZ;
        int chunkRadius;
        boolean parallelChunks = false;
        int parallelChunkCount = PARALLEL_JOB_DEFAULT_IN_FLIGHT_CHUNKS;
        try {
            centerX = Integer.parseInt(args[2]);
            centerZ = Integer.parseInt(args[3]);
            chunkRadius = Math.max(2, Integer.parseInt(args[4]));
            if (args.length >= 7) {
                parallelChunks = parseBooleanArg(args[6]);
            }
            if (args.length >= 8) {
                parallelChunkCount = Integer.parseInt(args[7]);
            }
        } catch (NumberFormatException e) {
            return "failed invalid_number";
        }
        String relativeOut = args[5];

        World world = resolveWorld(dimension);
        if (world == null) {
            return "failed world_not_found";
        }

        String jobId = UUID.randomUUID().toString().replace("-", "");
        ExtractJob job = new ExtractJob();
        job.id = jobId;
        job.dimension = dimension;
        job.structureId = structureId;
        job.centerX = centerX;
        job.centerZ = centerZ;
        job.chunkRadius = chunkRadius;
        job.parallelChunks = parallelChunks;
        int requestedInFlight = Math.max(1, parallelChunkCount);
        job.maxInFlightChunks = parallelChunks ? Math.min(PARALLEL_JOB_MAX_IN_FLIGHT_CHUNKS, requestedInFlight) : 1;
        job.relativeOut = relativeOut;
        job.state = JobState.RUNNING;
        job.startedMs = System.currentTimeMillis();
        job.dump.structureId = structureId;
        job.dump.dimension = dimension;
        job.dump.centerX = centerX;
        job.dump.centerZ = centerZ;

        int centerChunkX = Math.floorDiv(centerX, 16);
        int centerChunkZ = Math.floorDiv(centerZ, 16);
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                ChunkCoord coord = new ChunkCoord(centerChunkX + dx, centerChunkZ + dz);
                job.pending.add(coord);
                job.requestedChunks.add(coord);
            }
        }
        job.totalChunks = job.pending.size();
        jobs.put(jobId, job);
        getLogger().info("lootprobe_extract_start job=" + jobId + " structure=" + structureId
                + " dim=" + dimension + " center=" + centerX + "," + centerZ + " chunkRadius=" + chunkRadius);
        if (parallelChunks) {
            getLogger().info("lootprobe_extract_start job=" + jobId + " parallelChunks=true maxInFlight=" + job.maxInFlightChunks);
        }
        scheduleExtractJob(world, job);
        return "job=" + jobId;
    }

    private void scheduleExtractJob(World world, ExtractJob job) {
        job.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (job.state != JobState.RUNNING) {
                if (job.task != null) {
                    job.task.cancel();
                }
                return;
            }
            job.ticks++;
            if (job.ticks > MAX_JOB_TICKS) {
                failJob(job, "job_timeout");
                return;
            }

            for (int i = job.inFlight.size() - 1; i >= 0; i--) {
                InFlightChunk inFlight = job.inFlight.get(i);
                if (!inFlight.future.isDone()) {
                    continue;
                }
                try {
                    Chunk chunk = inFlight.future.getNow(null);
                    if (chunk == null) {
                        throw new IllegalStateException("chunk_null");
                    }
                    if (!inFlight.wasLoaded && world.isChunkLoaded(inFlight.coord.x, inFlight.coord.z)) {
                        job.dump.chunkStats.newlyLoaded++;
                    }
                    boolean nowGenerated = isChunkGeneratedSafe(world, inFlight.coord.x, inFlight.coord.z);
                    if (!inFlight.wasGenerated && nowGenerated) {
                        job.dump.chunkStats.newlyGenerated++;
                    }
                    job.completedChunks++;
                    job.inFlight.remove(i);
                } catch (Exception e) {
                    failJob(job, "chunk_load_failed");
                    return;
                }
            }

            if (job.pending.isEmpty() && job.inFlight.isEmpty()) {
                if (job.settleTicksRemaining > 0) {
                    job.settleTicksRemaining--;
                    return;
                }
                if (!job.postLoadProcessed) {
                    processAllRequestedChunks(world, job);
                    job.postLoadProcessed = true;
                }
                finishJob(job);
                return;
            }

            while (!job.pending.isEmpty() && job.inFlight.size() < job.maxInFlightChunks) {
                ChunkCoord next = job.pending.removeFirst();
                job.dump.chunkStats.requested++;
                boolean wasLoaded = world.isChunkLoaded(next.x, next.z);
                if (wasLoaded) {
                    job.dump.chunkStats.alreadyLoaded++;
                }
                boolean wasGenerated = isChunkGeneratedSafe(world, next.x, next.z);
                if (wasGenerated) {
                    job.dump.chunkStats.alreadyGenerated++;
                }
                InFlightChunk inFlight = new InFlightChunk();
                inFlight.coord = next;
                inFlight.wasLoaded = wasLoaded;
                inFlight.wasGenerated = wasGenerated;
                inFlight.future = world.getChunkAtAsync(next.x, next.z, true);
                job.inFlight.add(inFlight);
            }
        }, 1L, 1L);
    }

    private void processAllRequestedChunks(World world, ExtractJob job) {
        for (ChunkCoord coord : job.requestedChunks) {
            Chunk chunk = world.getChunkAt(coord.x, coord.z);
            processChunk(job, chunk);
        }
    }

    private void processChunk(ExtractJob job, Chunk chunk) {
        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        // Force chunk write/post-gen hook.
        world.getBlockAt(chunkX * 16 + 8, world.getMinHeight() + 1, chunkZ * 16 + 8).getType();

        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Container container)) {
                continue;
            }
            Location loc = state.getLocation();
            if (dist2(job.centerX, job.centerZ, loc.getBlockX(), loc.getBlockZ()) > (long) CHEST_ATTACH_RADIUS * CHEST_ATTACH_RADIUS) {
                continue;
            }
            ChestData chest = new ChestData();
            chest.x = loc.getBlockX();
            chest.y = loc.getBlockY();
            chest.z = loc.getBlockZ();
            chest.blockId = key(container.getType());
            if (!isTargetContainer(chest.blockId)) {
                continue;
            }

            if (state instanceof Lootable lootable) {
                LootTable table = lootable.getLootTable();
                chest.lootTable = table != null && table.getKey() != null ? table.getKey().toString() : null;
                try {
                    chest.lootTableSeed = lootable.getSeed();
                } catch (Throwable ignored) {
                }
                if (chest.lootTable != null && chest.lootTableSeed != null && chest.lootTableSeed == 0L) {
                    job.zeroSeedLootTableCount++;
                    if (job.zeroSeedExamples.size() < ZERO_SEED_LOG_EXAMPLE_LIMIT) {
                        job.zeroSeedExamples.add(chest.x + "," + chest.y + "," + chest.z + " table=" + chest.lootTable);
                    }
                }
            }
            Inventory inventory = container.getInventory();
            readInventoryItems(inventory, chest.items);
            if (chest.items.isEmpty() && state instanceof Lootable lootable) {
                populateFromLootTable(job, lootable, inventory, loc, chest);
            }
            job.dump.chests.add(chest);
        }
    }

    private void finishJob(ExtractJob job) {
        try {
            job.dump.chests.sort(Comparator.comparingInt((ChestData c) -> c.x).thenComparingInt(c -> c.z).thenComparingInt(c -> c.y));
            File outFile = new File(getDataFolder(), job.relativeOut);
            File parent = outFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            mapper.writeValue(outFile, job.dump);
            job.state = JobState.DONE;
            int itemCount = 0;
            for (ChestData chest : job.dump.chests) {
                itemCount += chest.items != null ? chest.items.size() : 0;
            }
            long took = System.currentTimeMillis() - job.startedMs;
            getLogger().info("lootprobe_extract done structure=" + job.structureId
                    + " chunks=" + job.dump.chunkStats.requested
                    + " generated=" + job.dump.chunkStats.newlyGenerated
                    + " chests=" + job.dump.chests.size()
                    + " items=" + itemCount
                    + " zeroSeedLootTables=" + job.zeroSeedLootTableCount
                    + " skippedContextLootTables=" + job.unsupportedContextChestCount
                    + " out=" + outFile.getAbsolutePath()
                    + " tookMs=" + took);
            if (!job.zeroSeedExamples.isEmpty()) {
                for (String sample : job.zeroSeedExamples) {
                    getLogger().info("lootprobe_extract zero_seed_example structure=" + job.structureId + " chest=" + sample);
                }
            }
            if (job.unsupportedContextChestCount > 0) {
                getLogger().warning("lootprobe_extract skipped_context_summary structure=" + job.structureId
                        + " chests=" + job.unsupportedContextChestCount
                        + " uniqueLootTables=" + job.unsupportedContextByTable.size());
                job.unsupportedContextByTable.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(8)
                        .forEach(e -> getLogger().warning("lootprobe_extract skipped_context_table structure="
                                + job.structureId + " table=" + e.getKey() + " count=" + e.getValue()));
                for (String sample : job.unsupportedContextExamples) {
                    getLogger().warning("lootprobe_extract skipped_context_example structure=" + job.structureId + " " + sample);
                }
            }
        } catch (Exception e) {
            failJob(job, "write_failed");
        } finally {
            if (job.task != null) {
                job.task.cancel();
            }
        }
    }

    private void failJob(ExtractJob job, String error) {
        job.error = error;
        job.state = JobState.FAILED;
        getLogger().warning("lootprobe_extract failed job=" + job.id + " structure=" + job.structureId + " error=" + error);
        if (job.task != null) {
            job.task.cancel();
        }
    }

    private boolean handleDiscover(CommandSender sender, String[] args) {
        if (args.length < 7) {
            sender.sendMessage("Usage: /lootprobe_discover <dimension> <centerX> <centerZ> <radius> <locateStep> <relativeOutputFile> <structureId...>");
            return true;
        }

        String dimension = args[0];
        int centerX;
        int centerZ;
        int radius;
        int locateStep;
        try {
            centerX = Integer.parseInt(args[1]);
            centerZ = Integer.parseInt(args[2]);
            radius = Math.max(1, Integer.parseInt(args[3]));
            locateStep = Math.max(128, Integer.parseInt(args[4]));
        } catch (NumberFormatException e) {
            sender.sendMessage("centerX/centerZ/radius/locateStep must be integers.");
            return true;
        }
        String relativeOut = args[5];

        List<String> structureIds = new ArrayList<>();
        for (int i = 6; i < args.length; i++) {
            String s = args[i].trim();
            if (!s.isEmpty()) {
                structureIds.add(s);
            }
        }
        if (structureIds.isEmpty()) {
            sender.sendMessage("At least one structure id is required.");
            return true;
        }

        World world = resolveWorld(dimension);
        if (world == null) {
            sender.sendMessage("World not found for dimension: " + dimension);
            return true;
        }

        long started = System.currentTimeMillis();
        getLogger().info("lootprobe_discover start dim=" + dimension
                + " center=" + centerX + "," + centerZ
                + " radius=" + radius
                + " locateStep=" + locateStep
                + " structures=" + structureIds.size());
        try {
            PluginDiscoverDump dump = discover(world, dimension, centerX, centerZ, radius, locateStep, structureIds);
            File outFile = new File(getDataFolder(), relativeOut);
            File parent = outFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            mapper.writeValue(outFile, dump);
            long took = System.currentTimeMillis() - started;
            getLogger().info("lootprobe_discover done starts=" + dump.starts.size() + " out=" + outFile.getAbsolutePath() + " tookMs=" + took);
            sender.sendMessage("lootprobe_discover wrote " + outFile.getAbsolutePath());
        } catch (Exception e) {
            sender.sendMessage("lootprobe_discover failed: " + e.getMessage());
            getLogger().warning("lootprobe_discover failed: " + e);
        }
        return true;
    }

    private PluginDiscoverDump discover(
            World world,
            String dimension,
            int centerX,
            int centerZ,
            int radius,
            int locateStep,
            List<String> structureIds
    ) {
        PluginDiscoverDump dump = new PluginDiscoverDump();
        dump.dimension = dimension;
        dump.centerX = centerX;
        dump.centerZ = centerZ;
        dump.radius = radius;
        dump.locateStep = locateStep;

        int searchRadiusChunks = Math.max(64, (radius / 16) + 16);
        Set<String> seen = new HashSet<>();

        for (String structureId : structureIds) {
            List<Structure> resolvedStructures = resolveStructures(structureId);
            if (resolvedStructures.isEmpty()) {
                getLogger().info("lootprobe_discover skip unresolved structure id=" + structureId);
                continue;
            }
            if (resolvedStructures.size() > 1) {
                getLogger().info("lootprobe_discover expanded structure id=" + structureId + " to " + resolvedStructures.size() + " variants");
            }
            for (Structure structure : resolvedStructures) {
                for (int x = centerX - radius; x <= centerX + radius; x += locateStep) {
                    for (int z = centerZ - radius; z <= centerZ + radius; z += locateStep) {
                        if (dist2(centerX, centerZ, x, z) > (long) radius * radius) {
                            continue;
                        }
                        dump.samplePoints++;
                        StructureSearchResult nearest = world.locateNearestStructure(new Location(world, x, 80, z), structure, searchRadiusChunks, false);
                        if (nearest == null || nearest.getLocation() == null) {
                            continue;
                        }
                        Location loc = nearest.getLocation();
                        int sx = loc.getBlockX();
                        int sy = loc.getBlockY();
                        int sz = loc.getBlockZ();
                        if (dist2(centerX, centerZ, sx, sz) > (long) radius * radius) {
                            continue;
                        }
                        String key = structureId + "|" + (sx >> 4) + "|" + (sz >> 4);
                        if (!seen.add(key)) {
                            continue;
                        }
                        DiscoverStart start = new DiscoverStart();
                        start.id = structureId;
                        start.x = sx;
                        start.y = sy;
                        start.z = sz;
                        dump.starts.add(start);
                    }
                }
            }
        }
        dump.starts.sort(Comparator.comparing((DiscoverStart s) -> s.id).thenComparingInt(s -> s.x).thenComparingInt(s -> s.z));
        return dump;
    }

    private void populateFromLootTable(ExtractJob job, Lootable lootable, Inventory inventory, Location loc, ChestData chest) {
        LootTable table = lootable.getLootTable();
        if (table == null) {
            return;
        }
        long seed = 0L;
        try {
            seed = lootable.getSeed();
        } catch (Throwable ignored) {
        }
        if (seed == 0L) {
            chest.rawLootCommandResponse = "loot_seed_zero_unresolved";
            return;
        }
        Random random = new Random(seed);
        Collection<ItemStack> rolled;
        try {
            LootContext context = new LootContext.Builder(loc).build();
            rolled = table.populateLoot(random, context);
        } catch (IllegalArgumentException ex) {
            // Some datapack tables require parameters that are not available for generic
            // chest reconstruction (tool, attacker, damage source, etc). Skip gracefully.
            chest.rawLootCommandResponse = "loot_context_unsupported:" + ex.getMessage();
            String tableId = chest.lootTable != null ? chest.lootTable : "<unknown>";
            job.unsupportedContextChestCount++;
            job.unsupportedContextByTable.merge(tableId, 1, Integer::sum);
            if (job.unsupportedContextExamples.size() < UNSUPPORTED_CONTEXT_LOG_EXAMPLE_LIMIT) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "missing_context";
                job.unsupportedContextExamples.add("chest=" + chest.x + "," + chest.y + "," + chest.z
                        + " table=" + tableId + " reason=" + msg);
            }
            return;
        } catch (Throwable ex) {
            chest.rawLootCommandResponse = "loot_populate_failed:" + ex.getClass().getSimpleName();
            return;
        }
        int slot = 0;
        for (ItemStack item : rolled) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            chest.items.add(toItemData(item, slot++));
        }
    }

    private void readInventoryItems(Inventory inv, List<ItemStackData> out) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            out.add(toItemData(item, i));
        }
    }

    private ItemStackData toItemData(ItemStack item, int slot) {
        ItemStackData data = new ItemStackData();
        data.slot = slot;
        data.count = item.getAmount();
        data.itemId = key(item.getType());
        data.id = data.itemId;
        data.displayName = item.getType().name().toLowerCase(Locale.ROOT);
        data.nbt = item.toString();
        return data;
    }

    private static World resolveWorld(String dimension) {
        Environment target = switch (dimension) {
            case "minecraft:the_end" -> Environment.THE_END;
            case "minecraft:the_nether" -> Environment.NETHER;
            default -> Environment.NORMAL;
        };
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == target) {
                return world;
            }
        }
        String raw = dimension.contains(":") ? dimension.substring(dimension.indexOf(':') + 1) : dimension;
        World byName = Bukkit.getWorld(raw);
        return byName != null ? byName : Bukkit.getWorld(dimension);
    }

    private static String key(Material material) {
        NamespacedKey key = material.getKey();
        return key != null ? key.toString() : material.name().toLowerCase(Locale.ROOT);
    }

    private static long dist2(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - x2;
        long dz = (long) z1 - z2;
        return dx * dx + dz * dz;
    }

    private static boolean isTargetContainer(String blockId) {
        if (blockId == null) {
            return false;
        }
        String s = blockId.toLowerCase(Locale.ROOT);
        return s.endsWith("chest")
                || s.endsWith("barrel")
                || s.endsWith("dispenser")
                || s.endsWith("dropper")
                || s.endsWith("hopper")
                || s.endsWith("shulker_box");
    }

    private static boolean isChunkGeneratedSafe(World world, int chunkX, int chunkZ) {
        try {
            return world.isChunkGenerated(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean parseBooleanArg(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true")
                || normalized.equals("1")
                || normalized.equals("yes")
                || normalized.equals("on");
    }

    private static List<Structure> resolveStructures(String structureId) {
        List<Structure> out = new ArrayList<>();
        NamespacedKey key = NamespacedKey.fromString(structureId);
        if (key == null) {
            return out;
        }
        Structure exact = Registry.STRUCTURE.get(key);
        if (exact != null) {
            out.add(exact);
            return out;
        }
        String namespace = key.getNamespace();
        String value = key.getKey();
        if ("village".equals(value)) {
            for (Structure structure : Registry.STRUCTURE) {
                NamespacedKey structureKey = structure.getKey();
                if (structureKey == null) {
                    continue;
                }
                if (!namespace.equals(structureKey.getNamespace())) {
                    continue;
                }
                if (!structureKey.getKey().startsWith("village_")) {
                    continue;
                }
                out.add(structure);
            }
        }
        return out;
    }

    private enum JobState {
        RUNNING, DONE, FAILED
    }

    private static final class ChunkCoord {
        final int x;
        final int z;

        private ChunkCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class ExtractJob {
        String id;
        String dimension;
        String structureId;
        int centerX;
        int centerZ;
        int chunkRadius;
        boolean parallelChunks;
        int maxInFlightChunks = 1;
        String relativeOut;
        long startedMs;
        int ticks;
        int totalChunks;
        int completedChunks;
        int settleTicksRemaining = POST_LOAD_SETTLE_TICKS;
        boolean postLoadProcessed;
        BukkitTask task;
        JobState state = JobState.RUNNING;
        String error;
        int zeroSeedLootTableCount;
        final List<String> zeroSeedExamples = new ArrayList<>();
        int unsupportedContextChestCount;
        final Map<String, Integer> unsupportedContextByTable = new ConcurrentHashMap<>();
        final List<String> unsupportedContextExamples = new ArrayList<>();
        final PluginStructureDump dump = new PluginStructureDump();
        final ArrayDeque<ChunkCoord> pending = new ArrayDeque<>();
        final List<ChunkCoord> requestedChunks = new ArrayList<>();
        final List<InFlightChunk> inFlight = new ArrayList<>();
    }

    private static final class InFlightChunk {
        ChunkCoord coord;
        CompletableFuture<Chunk> future;
        boolean wasLoaded;
        boolean wasGenerated;
    }

    public static final class PluginStructureDump {
        public String structureId;
        public String dimension;
        public int centerX;
        public int centerZ;
        public ChunkStats chunkStats = new ChunkStats();
        public List<ChestData> chests = new ArrayList<>();
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
        public List<ItemStackData> items = new ArrayList<>();
    }

    public static final class ItemStackData {
        public int slot;
        public int count;
        public String itemId;
        public String id;
        public String displayName;
        public String nbt;
        public List<String> enchantments = new ArrayList<>();
        public List<Integer> enchantmentLevels = new ArrayList<>();
        public String rawItemNbt;
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
}
