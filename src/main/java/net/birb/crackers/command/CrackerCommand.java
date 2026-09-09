package net.birb.crackers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.seedfinding.mcfeature.Feature;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.config.StructureSave;
import net.birb.crackers.cracker.Advisor;
import net.birb.crackers.cracker.solver.SolverDiagnostics;
import net.birb.crackers.cracker.storage.DataStorage;
import net.birb.crackers.finder.ReloadFinders;
import net.birb.crackers.gui.CrackerScreen;
import net.birb.crackers.util.Log;
import net.birb.crackers.util.Pools;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers {@code /cracker} and its sub-commands.
 * <p>
 * Running {@code /cracker} on its own opens the screen; everything else is a
 * shortcut for something the screen also does, plus {@code check}, which has no
 * screen equivalent because it does real work.
 */
public final class CrackerCommand {

    private CrackerCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("cracker")
                        .executes(ctx -> openGui());

        root.then(literal("gui", ctx -> openGui()));
        root.then(literal("on", ctx -> setActive(true)));
        root.then(literal("off", ctx -> setActive(false)));
        root.then(literal("status", ctx -> printStatus()));
        root.then(literal("bits", ctx -> printStatus()));
        root.then(literal("check", ctx -> runCheck()));
        root.then(literal("clear", ctx -> {
            StructureSave.deleteSave();
            SeedCracker.get().reset();
            Log.problem("Cleared everything collected here, including the saved file.");
            ReloadFinders.rescanLoadedChunks();
            return 1;
        }));
        root.then(literal("debug", ctx -> {
            Config.get().debug = !Config.get().debug;
            Config.save();
            Log.headline("Debug logging " + (Config.get().debug ? "on" : "off") + ".");
            return 1;
        }));

        LiteralCommandNode<FabricClientCommandSource> node = dispatcher.register(root);

        // A redirect, not a copy: this forwards every sub-command, so
        // /crackers check behaves exactly like /cracker check. Registering it
        // as its own literal made the alias silently ignore arguments.
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("crackers")
                .executes(ctx -> openGui())
                .redirect(node));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(
            String name, com.mojang.brigadier.Command<FabricClientCommandSource> action) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name).executes(action);
    }

    private static int openGui() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreenAndShow(new CrackerScreen(null)));
        return 1;
    }

    private static int setActive(boolean active) {
        Config.get().active = active;
        Config.save();
        if (active) {
            Log.success("Collecting world data. Explore and it will work in the background.");
            ReloadFinders.rescanLoadedChunks();
        } else {
            Log.problem("Stopped collecting. Run /cracker on to resume.");
        }
        return 1;
    }

    private static int printStatus() {
        DataStorage storage = SeedCracker.get().getDataStorage();

        if (SeedCracker.foundSeed != null) {
            Log.printSeed("crackers.foundSeed", SeedCracker.foundSeed);
            if (SeedCracker.foundVia != null) Log.detail(SeedCracker.foundVia);
            return 1;
        }

        Advisor.Advice advice = Advisor.advise();
        Log.headline(advice.headline());

        Log.detail(String.format("Position data %d/%d  ·  total data %d/%d  ·  hashed seed %s",
                (int) storage.getLiftingBits(), (int) Advisor.LIFTING_TARGET,
                (int) storage.getBaseBits(), (int) storage.getWantedBits(),
                storage.hasUsableHashedSeed() ? "yes" : "no"));

        StringBuilder breakdown = new StringBuilder();
        for (Map.Entry<String, Integer> e : storage.getTypeCounts().entrySet()) {
            if (!breakdown.isEmpty()) breakdown.append(", ");
            breakdown.append(e.getValue()).append("x ").append(net.birb.crackers.Features.displayName(e.getKey()));
        }
        Log.detail(breakdown.isEmpty() ? "Nothing found yet." : "Found: " + breakdown);

        int liftable = Advisor.countLiftable(storage);
        if (storage.getStructureCount() > 0) {
            Log.detail(liftable + " of " + storage.getStructureCount() + " count towards position data.");
        }

        for (String step : advice.steps()) {
            if (!step.isBlank()) Log.detail(step);
        }
        return 1;
    }

    /**
     * Checks whether the collected structures can all come from one world.
     * <p>
     * This is the answer to "I found loads of structures and it still did not
     * work". Runs off-thread because it sieves the data once per structure.
     */
    private static int runCheck() {
        DataStorage storage = SeedCracker.get().getDataStorage();
        List<Feature.Data<?>> data = storage.solverInput();

        if (data.isEmpty()) {
            Log.problem("Nothing collected yet, so there is nothing to check.");
            return 1;
        }

        Log.headline("Checking " + data.size() + " structures for conflicts…");
        Log.detail("This can take a few seconds.");
        Pools.COORDINATOR.execute(() -> {
            SolverDiagnostics.Report report = SolverDiagnostics.analyse(
                    data, Pools.SOLVER, Pools.solverThreads(), new AtomicBoolean());
            Log.headline(report.summary());
            for (String line : report.details()) {
                if (!line.isBlank()) Log.detail(line);
            }
        });
        return 1;
    }
}
