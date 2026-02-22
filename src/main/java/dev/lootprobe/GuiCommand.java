package dev.lootprobe;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "gui", description = "Launch desktop GUI.")
public final class GuiCommand implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        LootProbeGui.launchBlocking();
        return 0;
    }
}
