package dev.lootprobe;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CubiomesBridge {
    private interface NativeBridge extends Library {
        int lp_init(String cubiomesPath);
        String lp_last_error();
        int lp_render_map(long seed, int mc, int dim, int centerX, int centerZ, int radius, int width, int height, int[] outArgb);
        int lp_generate_structures(long seed, int mc, int dim, int minX, int minZ, int maxX, int maxZ,
                                   int[] structureTypes, int structureTypeCount, int maxOut, int[] outTriplets);
    }

    public record StructurePoint(int type, int x, int z) {}

    private static final int DIM_NETHER = -1;
    private static final int DIM_OVERWORLD = 0;
    private static final int DIM_END = 1;

    // Cubiomes enum MCVersion values from biomes.h
    private static final int MC_1_16_5 = 19;
    private static final int MC_1_17_1 = 20;
    private static final int MC_1_18_2 = 21;
    private static final int MC_1_19_4 = 23;
    private static final int MC_1_20_6 = 24;
    private static final int MC_1_21_WD = 27;

    // Cubiomes enum StructureType values from finders.h
    private static final Map<String, Integer> OVERWORLD_STRUCTS = new LinkedHashMap<>();
    private static final Map<String, Integer> NETHER_STRUCTS = new LinkedHashMap<>();
    private static final Map<String, Integer> END_STRUCTS = new LinkedHashMap<>();

    static {
        OVERWORLD_STRUCTS.put("minecraft:desert_pyramid", 1);
        OVERWORLD_STRUCTS.put("minecraft:jungle_temple", 2);
        OVERWORLD_STRUCTS.put("minecraft:swamp_hut", 3);
        OVERWORLD_STRUCTS.put("minecraft:igloo", 4);
        OVERWORLD_STRUCTS.put("minecraft:village", 5);
        OVERWORLD_STRUCTS.put("minecraft:ocean_ruin", 6);
        OVERWORLD_STRUCTS.put("minecraft:shipwreck", 7);
        OVERWORLD_STRUCTS.put("minecraft:monument", 8);
        OVERWORLD_STRUCTS.put("minecraft:mansion", 9);
        OVERWORLD_STRUCTS.put("minecraft:pillager_outpost", 10);
        OVERWORLD_STRUCTS.put("minecraft:ruined_portal", 11);
        OVERWORLD_STRUCTS.put("minecraft:ancient_city", 13);
        OVERWORLD_STRUCTS.put("minecraft:buried_treasure", 14);
        OVERWORLD_STRUCTS.put("minecraft:mineshaft", 15);
        OVERWORLD_STRUCTS.put("minecraft:trail_ruins", 23);
        OVERWORLD_STRUCTS.put("minecraft:trial_chambers", 24);

        NETHER_STRUCTS.put("minecraft:fortress", 18);
        NETHER_STRUCTS.put("minecraft:bastion_remnant", 19);
        NETHER_STRUCTS.put("minecraft:ruined_portal", 12);

        END_STRUCTS.put("minecraft:end_city", 20);
    }

    private NativeBridge bridge;
    private boolean initialized;
    private String lastError = "";

    public boolean ensureInitialized(String bridgeDllPath, String cubiomesDllPath) {
        if (initialized && bridge != null) {
            return true;
        }
        try {
            String libName = stripLibrarySuffix(Path.of(bridgeDllPath).toAbsolutePath().toString());
            bridge = Native.load(libName, NativeBridge.class);
            int rc = bridge.lp_init(cubiomesDllPath != null && !cubiomesDllPath.isBlank() ? cubiomesDllPath : null);
            if (rc != 0) {
                lastError = safeError();
                return false;
            }
            initialized = true;
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return false;
        }
    }

    public String getLastError() {
        return lastError != null ? lastError : "";
    }

    public int[] renderMap(long seed, String mcVersion, String dimension, int centerX, int centerZ, int radius, int width, int height) {
        if (!initialized || bridge == null) {
            return null;
        }
        int[] argb = new int[Math.max(0, width * height)];
        int rc = bridge.lp_render_map(seed, mapMcVersion(mcVersion), mapDimension(dimension), centerX, centerZ, radius, width, height, argb);
        if (rc != 0) {
            lastError = safeError();
            return null;
        }
        return argb;
    }

    public List<StructurePoint> generateStructures(
            long seed,
            String mcVersion,
            String dimension,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            List<String> structureIds,
            int maxOut
    ) {
        if (!initialized || bridge == null || structureIds == null || structureIds.isEmpty()) {
            return List.of();
        }
        int dim = mapDimension(dimension);
        int[] types = structureTypesForDimension(structureIds, dim);
        if (types.length == 0) {
            return List.of();
        }
        int limit = Math.max(1, maxOut);
        int[] out = new int[limit * 3];
        int count = bridge.lp_generate_structures(seed, mapMcVersion(mcVersion), dim, minX, minZ, maxX, maxZ, types, types.length, limit, out);
        if (count < 0) {
            lastError = safeError();
            return List.of();
        }
        List<StructurePoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int o = i * 3;
            points.add(new StructurePoint(out[o], out[o + 1], out[o + 2]));
        }
        return points;
    }

    private String safeError() {
        try {
            String msg = bridge != null ? bridge.lp_last_error() : null;
            if (msg != null && !msg.isBlank()) {
                return msg.trim();
            }
        } catch (Throwable ignored) {
        }
        return "Unknown cubiomes bridge error.";
    }

    private static int mapDimension(String dimension) {
        if ("minecraft:the_nether".equals(dimension)) {
            return DIM_NETHER;
        }
        if ("minecraft:the_end".equals(dimension)) {
            return DIM_END;
        }
        return DIM_OVERWORLD;
    }

    private static int mapMcVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return MC_1_21_WD;
        }
        String s = raw.trim().toLowerCase();
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        if (s.startsWith("1.21")) {
            return MC_1_21_WD;
        }
        if (s.startsWith("1.20")) {
            return MC_1_20_6;
        }
        if (s.startsWith("1.19")) {
            return MC_1_19_4;
        }
        if (s.startsWith("1.18")) {
            return MC_1_18_2;
        }
        if (s.startsWith("1.17")) {
            return MC_1_17_1;
        }
        if (s.startsWith("1.16")) {
            return MC_1_16_5;
        }
        return MC_1_21_WD;
    }

    private static int[] structureTypesForDimension(List<String> ids, int dim) {
        Map<String, Integer> source = switch (dim) {
            case DIM_NETHER -> NETHER_STRUCTS;
            case DIM_END -> END_STRUCTS;
            default -> OVERWORLD_STRUCTS;
        };
        List<Integer> out = new ArrayList<>();
        for (String id : ids) {
            Integer st = source.get(id);
            if (st != null) {
                out.add(st);
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static String stripLibrarySuffix(String path) {
        String p = path;
        if (p.toLowerCase().endsWith(".dll")) {
            p = p.substring(0, p.length() - 4);
        }
        return p;
    }
}
