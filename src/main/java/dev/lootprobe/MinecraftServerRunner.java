package dev.lootprobe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class MinecraftServerRunner implements AutoCloseable {
    private final String javaBin;
    private final Path serverJar;
    private final Path runDir;
    private final long seed;
    private final List<Path> datapacks;
    private final int rconPort;
    private final String rconPassword;
    private final Duration startupTimeout;
    private final Path pluginJar;
    private final String primaryDimension;
    private final Consumer<String> logConsumer;

    private Process process;
    private Thread logThread;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public MinecraftServerRunner(
            String javaBin,
            Path serverJar,
            Path runDir,
            long seed,
            List<Path> datapacks,
            int rconPort,
            String rconPassword,
            Duration startupTimeout,
            Path pluginJar,
            String primaryDimension
    ) {
        this(
                javaBin,
                serverJar,
                runDir,
                seed,
                datapacks,
                rconPort,
                rconPassword,
                startupTimeout,
                pluginJar,
                primaryDimension,
                null
        );
    }

    public MinecraftServerRunner(
            String javaBin,
            Path serverJar,
            Path runDir,
            long seed,
            List<Path> datapacks,
            int rconPort,
            String rconPassword,
            Duration startupTimeout,
            Path pluginJar,
            String primaryDimension,
            Consumer<String> logConsumer
    ) {
        this.javaBin = javaBin;
        this.serverJar = serverJar;
        this.runDir = runDir;
        this.seed = seed;
        this.datapacks = datapacks;
        this.rconPort = rconPort;
        this.rconPassword = rconPassword;
        this.startupTimeout = startupTimeout;
        this.pluginJar = pluginJar;
        this.primaryDimension = primaryDimension;
        this.logConsumer = logConsumer;
    }

    public void start() throws IOException, InterruptedException {
        Files.createDirectories(runDir);
        writeConfig();

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-Xms256M",
                "-Xmx2048M",
                "-jar",
                serverJar.toAbsolutePath().toString(),
                "nogui"
        );
        pb.directory(runDir.toFile());
        pb.redirectErrorStream(true);
        process = pb.start();

        logThread = new Thread(() -> consumeLogs(process), "mc-server-log");
        logThread.setDaemon(true);
        logThread.start();

        waitForStartup();
    }

    public Path getWorldDir() {
        return runDir.resolve("probe_world");
    }

    public Path getRunDir() {
        return runDir;
    }

    public int getRconPort() {
        return rconPort;
    }

    public String getRconPassword() {
        return rconPassword;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    private void writeConfig() throws IOException {
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");

        String properties = String.join("\n",
                "accepts-transfers=false",
                "allow-flight=true",
                "allow-nether=true",
                "broadcast-console-to-ops=true",
                "broadcast-rcon-to-ops=false",
                "difficulty=normal",
                "enable-command-block=true",
                "enable-jmx-monitoring=false",
                "enable-query=false",
                "enable-rcon=true",
                "enforce-secure-profile=false",
                "enable-status=false",
                "gamemode=creative",
                "generate-structures=true",
                "hardcore=false",
                "level-name=probe_world",
                "level-seed=" + seed,
                "max-players=1",
                "max-tick-time=-1",
                "motd=loot-probe",
                "network-compression-threshold=-1",
                "online-mode=false",
                "player-idle-timeout=0",
                "pvp=false",
                "rcon.password=" + rconPassword,
                "rcon.port=" + rconPort,
                "simulation-distance=2",
                "spawn-animals=false",
                "spawn-monsters=false",
                "spawn-npcs=false",
                "spawn-protection=0",
                "sync-chunk-writes=false",
                "view-distance=2",
                "white-list=false"
        ) + "\n";
        Files.writeString(runDir.resolve("server.properties"), properties);

        // Keep Bukkit side as lean as possible and disable unnecessary dimensions.
        String bukkitYml = String.join("\n",
                "settings:",
                "  allow-end: true",
                "  warn-on-overload: false",
                "spawn-limits:",
                "  monsters: 0",
                "  animals: 0",
                "  water-animals: 0",
                "  water-ambient: 0",
                "  water-underground-creature: 0",
                "  axolotls: 0",
                "  ambient: 0"
        ) + "\n";
        Files.writeString(runDir.resolve("bukkit.yml"), bukkitYml);

        Path datapackDir = runDir.resolve("probe_world").resolve("datapacks");
        Files.createDirectories(datapackDir);
        for (Path datapack : datapacks) {
            Path source = datapack.toAbsolutePath();
            if (!Files.exists(source)) {
                throw new IOException("Datapack not found: " + source);
            }
            Path target = datapackDir.resolve(source.getFileName().toString());
            if (Files.isDirectory(source)) {
                copyDir(source, target);
            } else {
                Files.copy(source, target);
            }
        }

        if (pluginJar != null) {
            if (!Files.exists(pluginJar)) {
                throw new IOException("Plugin jar not found: " + pluginJar.toAbsolutePath());
            }
            Path pluginsDir = runDir.resolve("plugins");
            Files.createDirectories(pluginsDir);
            Files.copy(pluginJar.toAbsolutePath(), pluginsDir.resolve(pluginJar.getFileName().toString()));
        }
    }

    private static void copyDir(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path dest = target.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(path, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private void consumeLogs(Process proc) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (logConsumer != null) {
                    logConsumer.accept(line);
                }
                String normalized = line.toLowerCase(Locale.ROOT);
                if (normalized.contains("done (") && normalized.contains("for help")) {
                    started.set(true);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void waitForStartup() throws InterruptedException, IOException {
        Instant deadline = Instant.now().plus(startupTimeout);
        while (Instant.now().isBefore(deadline)) {
            if (started.get()) {
                return;
            }
            if (!process.isAlive()) {
                throw new IOException("Server exited before startup completed.");
            }
            Thread.sleep(250);
        }
        throw new IOException("Server did not report ready state before timeout.");
    }

    @Override
    public void close() throws Exception {
        if (process == null || !process.isAlive()) {
            return;
        }
        try (RconClient rcon = new RconClient("127.0.0.1", rconPort, rconPassword)) {
            rcon.connectAndLogin();
            rcon.command("save-off");
            rcon.command("stop");
        } catch (Exception ignored) {
            process.destroy();
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroy();
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
