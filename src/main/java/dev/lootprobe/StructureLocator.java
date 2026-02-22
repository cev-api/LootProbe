package dev.lootprobe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StructureLocator {
    private static final Pattern XYZ_FULL = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]");
    private static final Pattern XYZ_TILDE_Y = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*~\\s*,\\s*(-?\\d+)\\s*\\]");

    private StructureLocator() {
    }

    public static List<StructureLocation> locateAll(RconClient rcon, List<String> structureIds, String defaultDimension) throws IOException {
        List<StructureLocation> out = new ArrayList<>();
        for (String id : structureIds) {
            out.add(locateOne(rcon, id, defaultDimension));
        }
        return out;
    }

    public static List<StructureLocation> locateAllTargets(
            RconClient rcon,
            List<ProbeConfig.StructureTarget> structureTargets,
            String defaultDimension
    ) throws IOException {
        List<StructureLocation> out = new ArrayList<>();
        for (ProbeConfig.StructureTarget target : structureTargets) {
            if (target == null || target.id == null || target.id.isBlank()) {
                continue;
            }
            out.add(locateOne(rcon, target.id.trim(), target.normalizedDimension(defaultDimension)));
        }
        return out;
    }

    private static StructureLocation locateOne(RconClient rcon, String id, String defaultDimension) throws IOException {
        String dimension = inferDimension(id, defaultDimension);
        String response = rcon.command("execute in " + dimension + " run locate structure " + id);
        StructureLocation location = new StructureLocation();
        location.structureId = id;
        location.dimension = dimension;
        location.rawResponse = response;

        Matcher full = XYZ_FULL.matcher(response);
        Matcher tilde = XYZ_TILDE_Y.matcher(response);
        if (full.find()) {
            location.found = true;
            location.x = Integer.parseInt(full.group(1));
            location.y = Integer.parseInt(full.group(2));
            location.z = Integer.parseInt(full.group(3));
        } else if (tilde.find()) {
            location.found = true;
            location.x = Integer.parseInt(tilde.group(1));
            location.y = null;
            location.z = Integer.parseInt(tilde.group(2));
        } else {
            location.found = false;
        }
        return location;
    }

    private static String inferDimension(String id, String defaultDimension) {
        String s = id.toLowerCase();
        if (s.contains("end_city")) {
            return "minecraft:the_end";
        }
        if (s.contains("bastion") || s.contains("fortress") || s.contains("nether_fossil")) {
            return "minecraft:the_nether";
        }
        return defaultDimension;
    }

    public static final class StructureLocation {
        public String structureId;
        public String dimension;
        public boolean found;
        public Integer x;
        public Integer y;
        public Integer z;
        public String rawResponse;
    }
}
