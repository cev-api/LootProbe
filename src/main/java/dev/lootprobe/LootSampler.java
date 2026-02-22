package dev.lootprobe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LootSampler {
    private static final int ORIGIN_X = 0;
    private static final int ORIGIN_Y = 240;
    private static final int ORIGIN_Z = 0;
    private static final Pattern ID_PATTERN = Pattern.compile(":\\s*\"([^\"]+)\"");
    private static final Pattern COUNT_PATTERN = Pattern.compile(":\\s*([0-9]+)");

    private LootSampler() {
    }

    public static List<LootSampleSet> sampleAll(RconClient rcon, List<String> lootTables, int samplesPerTable, String dimension) throws IOException {
        List<LootSampleSet> out = new ArrayList<>();
        primeArea(rcon, dimension);
        for (String table : lootTables) {
            LootSampleSet set = new LootSampleSet();
            set.lootTableId = table;
            set.dimension = dimension;
            for (int i = 0; i < samplesPerTable; i++) {
                set.samples.add(sampleOne(rcon, table, dimension));
            }
            out.add(set);
        }
        cleanup(rcon, dimension);
        return out;
    }

    private static LootSample sampleOne(RconClient rcon, String table, String dimension) throws IOException {
        LootSample sample = new LootSample();
        RollResult roll = rollTable(rcon, table, dimension, null);
        sample.rawLootCommandResponse = roll.rawCommandResponse;
        for (ItemStackData stack : roll.items) {
            sample.itemNbt.add(stack.rawItemNbt);
        }
        if (sample.itemNbt.isEmpty()) {
            sample.note = "No items captured. Table may require context (block/entity/tool) not provided by /loot spawn.";
        }
        return sample;
    }

    public static RollResult rollTable(RconClient rcon, String lootTable, String dimension, Long seed) throws IOException {
        RollResult result = new RollResult();
        result.lootTable = lootTable;
        result.seed = seed;

        rcon.command("execute in " + dimension + " run kill @e[type=item,tag=lootprobe_item]");
        String lootCommand = "execute in " + dimension + " run loot spawn " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z + " loot " + lootTable;
        if (seed != null) {
            lootCommand += " " + seed;
        }
        result.rawCommandResponse = rcon.command(lootCommand);
        if (seed != null && looksLikeCommandError(result.rawCommandResponse)) {
            return rollTable(rcon, lootTable, dimension, null);
        }
        rcon.command("execute in " + dimension + " positioned " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z
                + " run tag @e[type=item,distance=..8] add lootprobe_item");

        int slot = 0;
        while (true) {
            String full = rcon.command("execute in " + dimension + " positioned " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z
                    + " run data get entity @e[type=item,tag=lootprobe_item,sort=nearest,limit=1] Item");
            if (full.contains("No entity was found")) {
                break;
            }
            String idOut = rcon.command("execute in " + dimension + " positioned " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z
                    + " run data get entity @e[type=item,tag=lootprobe_item,sort=nearest,limit=1] Item.id");
            String countOut = rcon.command("execute in " + dimension + " positioned " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z
                    + " run data get entity @e[type=item,tag=lootprobe_item,sort=nearest,limit=1] Item.count");
            ItemStackData stack = new ItemStackData();
            stack.slot = slot++;
            stack.itemId = parseId(idOut);
            stack.count = parseCount(countOut);
            stack.id = stack.itemId;
            stack.nbt = full;
            stack.rawItemNbt = full;
            result.items.add(stack);
            rcon.command("execute in " + dimension + " positioned " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z
                    + " run kill @e[type=item,tag=lootprobe_item,sort=nearest,limit=1]");
        }
        return result;
    }

    private static boolean looksLikeCommandError(String response) {
        String s = response.toLowerCase(Locale.ROOT);
        return s.contains("unknown") || s.contains("incorrect") || s.contains("expected");
    }

    private static String parseId(String out) {
        Matcher m = ID_PATTERN.matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private static int parseCount(String out) {
        Matcher m = COUNT_PATTERN.matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static void primeArea(RconClient rcon, String dimension) throws IOException {
        rcon.command("execute in " + dimension + " run forceload add 0 0 0 0");
        rcon.command("execute in " + dimension + " run setblock 0 239 0 stone");
        rcon.command("execute in " + dimension + " run kill @e[type=item,tag=lootprobe_item]");
    }

    private static void cleanup(RconClient rcon, String dimension) throws IOException {
        rcon.command("execute in " + dimension + " run kill @e[type=item,tag=lootprobe_item]");
        rcon.command("execute in " + dimension + " run forceload remove all");
    }

    public static final class LootSampleSet {
        public String lootTableId;
        public String dimension;
        public List<LootSample> samples = new ArrayList<>();
    }

    public static final class LootSample {
        public List<String> itemNbt = new ArrayList<>();
        public String note;
        public String rawLootCommandResponse;
    }

    public static final class RollResult {
        public String lootTable;
        public Long seed;
        public String rawCommandResponse;
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
}
