package dev.lootprobe;

public final class ProgressPrinter {
    public interface ProgressListener {
        void onProgress(int current, int total, String label);

        default void onInfo(String message) {
        }
    }

    private final int total;
    private final int width;
    private final ProgressListener listener;
    private int current;
    private long lastDrawMillis;
    private int lastPrintedLength;

    public ProgressPrinter(int total) {
        this(total, 30, null);
    }

    public ProgressPrinter(int total, int width) {
        this(total, width, null);
    }

    public ProgressPrinter(int total, int width, ProgressListener listener) {
        this.total = Math.max(total, 1);
        this.width = Math.max(width, 10);
        this.listener = listener;
    }

    public synchronized void info(String message) {
        clearProgressLine();
        System.out.println(message);
        if (listener != null) {
            listener.onInfo(message);
        }
    }

    public synchronized void step(String label) {
        current++;
        draw(label, false);
    }

    public synchronized void done(String label) {
        current = total;
        clearProgressLine();
        System.out.println(finalLine(label));
        if (listener != null) {
            listener.onProgress(current, total, label);
        }
    }

    private void draw(String label, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastDrawMillis < 80) {
            return;
        }
        lastDrawMillis = now;

        int clamped = Math.min(current, total);
        double ratio = clamped / (double) total;
        int filled = (int) Math.round(ratio * width);

        StringBuilder bar = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '#' : '-');
        }
        int pct = (int) Math.round(ratio * 100.0);
        String line = "[" + bar + "] " + String.format("%3d", pct) + "% (" + clamped + "/" + total + ") " + label;
        printProgressLine(line);
        if (listener != null) {
            listener.onProgress(clamped, total, label);
        }
    }

    private String finalLine(String label) {
        StringBuilder bar = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            bar.append('#');
        }
        return "[" + bar + "] 100% (" + total + "/" + total + ") " + label;
    }

    private void printProgressLine(String line) {
        String padded = line;
        if (line.length() < lastPrintedLength) {
            padded = line + " ".repeat(lastPrintedLength - line.length());
        }
        lastPrintedLength = padded.length();
        System.out.print("\r" + padded);
    }

    private void clearProgressLine() {
        if (lastPrintedLength <= 0) {
            return;
        }
        System.out.print("\r" + " ".repeat(lastPrintedLength) + "\r");
        lastPrintedLength = 0;
    }
}
