package net.birb.crackers.util;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * No-op stand-in for the online shared-seed database.
 * <p>
 * Crackers is fully self-contained: it never phones home, never submits your
 * seeds anywhere, and never performs the "join a fake server to authenticate"
 * dance. These methods keep the call sites in the cracking engine happy while
 * doing nothing.
 */
public class Database {

    public static Component joinFakeServerForAuth() {
        return null;
    }

    public static @Nullable Long getSeed(String connection, long hashedSeed) {
        return null;
    }

    public static void handleDatabaseCall(Long seed) {
        // intentionally does nothing
    }

    public static void fetchSeeds() {
        // intentionally does nothing
    }
}
