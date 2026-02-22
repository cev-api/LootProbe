package dev.lootprobe;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LeanRuntimeConfigurer {

    private LeanRuntimeConfigurer() {
    }

    public static void apply(RconClient rcon, Set<String> dimensions) throws IOException {
        rcon.command("difficulty peaceful");
        rcon.command("kill @e[type=!player,type=!item,type=!item_display,type=!text_display]");

        for (String dim : dimensions) {
            run(rcon, dim, "gamerule doMobSpawning false");
            run(rcon, dim, "gamerule doWeatherCycle false");
            run(rcon, dim, "gamerule doDaylightCycle false");
            run(rcon, dim, "gamerule randomTickSpeed 0");
            run(rcon, dim, "gamerule doFireTick false");
            run(rcon, dim, "gamerule doPatrolSpawning false");
            run(rcon, dim, "gamerule doTraderSpawning false");
            run(rcon, dim, "gamerule doInsomnia false");
            run(rcon, dim, "gamerule disableRaids true");
            run(rcon, dim, "gamerule doWardenSpawning false");
            run(rcon, dim, "gamerule spectatorsGenerateChunks false");
            run(rcon, dim, "gamerule announceAdvancements false");
            run(rcon, dim, "time set day");
            run(rcon, dim, "weather clear 1000000");
            run(rcon, dim, "kill @e[type=!player,type=!item,type=!item_display,type=!text_display]");
        }
    }

    private static void run(RconClient rcon, String dimension, String cmd) throws IOException {
        String response = rcon.command("execute in " + dimension + " run " + cmd);
        if (response != null && response.toLowerCase().contains("unknown")) {
            // Ignore gamerules unavailable in specific versions.
        }
    }

    public static Set<String> dimensionsFor(String structureDimension, String lootDimension, String scanDimension, boolean scanMode) {
        Set<String> dims = new LinkedHashSet<>();
        if (structureDimension != null && !structureDimension.isBlank()) {
            dims.add(structureDimension);
        }
        if (lootDimension != null && !lootDimension.isBlank()) {
            dims.add(lootDimension);
        }
        if (scanMode && scanDimension != null && !scanDimension.isBlank()) {
            dims.add(scanDimension);
        }
        if (dims.isEmpty()) {
            dims.add("minecraft:overworld");
        }
        return dims;
    }
}
