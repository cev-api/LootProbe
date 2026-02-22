package dev.lootprobe;

public interface ProbeListener {
    ProbeListener NO_OP = new ProbeListener() {
    };

    default void onInfo(String message) {
    }

    default void onProgress(String stage, int current, int total, String label) {
    }

    default void onServerLog(String line) {
    }
}
