package dev.lootprobe;

public final class SeedfindingMapSampler {
    private long versionSalt = 0x1F123BB5L;

    public void setVersionText(String versionText) {
        if (versionText == null || versionText.isBlank()) {
            versionSalt = 0x1F123BB5L;
            return;
        }
        versionSalt = mix64(versionText.trim().toLowerCase().hashCode());
    }

    public Integer sampleBiome(long seed, int x, int z) {
        long h = seed ^ versionSalt ^ (x * 341873128712L) ^ (z * 132897987541L);
        h = mix64(h);
        return (int) Math.floorMod(h, 256);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
