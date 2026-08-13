package net.birb.crackers.util;

import net.birb.crackers.SeedCracker;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

/**
 * Utilities for the "free" seed sources that don't require any cracking.
 * <p>
 * In singleplayer (and when you are hosting a LAN world) the real world seed
 * lives on the integrated server running inside your own client, so we can just
 * read it directly - instant and 100% reliable.
 */
public final class SeedUtil {

    private SeedUtil() {
    }

    /**
     * If the client is running an integrated server (singleplayer or LAN host),
     * read the world seed straight from it and report it. Cheap no-op otherwise.
     */
    public static void trySingleplayerSeed() {
        if (SeedCracker.foundSeed != null) return;

        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return;

        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) return;
            long seed = overworld.getSeed();
            SeedCracker.reportSeed(seed, "singleplayer host (read directly)");
            Log.printSeed("crackers.foundSeed", seed);
        } catch (Throwable ignored) {
            // Server not fully started yet; we'll try again next tick.
        }
    }
}
