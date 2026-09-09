package net.birb.crackers;

import com.mojang.logging.LogUtils;
import net.birb.crackers.api.SeedCrackerAPI;
import net.birb.crackers.command.CrackerCommand;
import net.birb.crackers.config.Config;
import net.birb.crackers.config.StructureSave;
import net.birb.crackers.cracker.storage.DataStorage;
import net.birb.crackers.util.Pools;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;

public class SeedCracker implements ClientModInitializer {
    public static final String MOD_ID = "crackers";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ArrayList<SeedCrackerAPI> entrypoints = new ArrayList<>();

    private static SeedCracker INSTANCE;
    private final DataStorage dataStorage = new DataStorage();

    /** The world seed once it has been cracked / read. {@code null} while unknown. */
    public static volatile Long foundSeed = null;
    /** Human readable description of how the seed was obtained (for the GUI). */
    public static volatile String foundVia = null;

    public static SeedCracker get() {
        return INSTANCE;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        Config.load();
        Features.init(Config.get().getVersion());
        FabricLoader.getInstance().getEntrypointContainers("crackers", SeedCrackerAPI.class).forEach(entrypoint ->
                entrypoints.add(entrypoint.getEntrypoint()));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CrackerCommand.register(dispatcher));

        // The solver pools are daemon threads, so the JVM can exit without
        // this. Stopping them anyway means a long lift does not keep burning
        // cores while the game is trying to shut down.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Pools.shutdown());

        LOGGER.info("Crackers initialized");
    }

    public DataStorage getDataStorage() {
        return this.dataStorage;
    }

    /** Called by the cracking engine (and the singleplayer reader) when a seed is known. */
    public static void reportSeed(long seed, String via) {
        foundSeed = seed;
        foundVia = via;
        entrypoints.forEach(e -> e.pushWorldSeed(seed));

        // Persist so rejoining this server does not re-crack from scratch.
        long hashed = 0L;
        try {
            var hashedData = get().getDataStorage().hashedSeedData;
            if (hashedData != null) hashed = hashedData.getHashedSeed();
        } catch (Exception ignored) {
        }
        StructureSave.saveSeed(seed, hashed);
    }

    public void reset() {
        this.dataStorage.clear();
        foundSeed = null;
        foundVia = null;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
