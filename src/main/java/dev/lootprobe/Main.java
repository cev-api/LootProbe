package dev.lootprobe;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "loot-probe",
        mixinStandardHelpOptions = true,
        version = "loot-probe 0.1.0",
        description = "Uses real Minecraft server code to locate structures and sample loot tables.",
        subcommands = {ProbeCommand.class, GuiCommand.class, BrowseCommand.class}
)
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new CommandLine(new Main()).execute(args);
        } catch (Throwable t) {
            t.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
