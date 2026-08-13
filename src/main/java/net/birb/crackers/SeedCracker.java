package net.birb.crackers;

import com.mojang.logging.LogUtils;
import net.birb.crackers.api.SeedCrackerAPI;
import net.birb.crackers.command.CrackerCommand;
import net.birb.crackers.config.Config;
import net.birb.crackers.cracker.storage.DataStorage;
import net.birb.crackers.finder.FinderQueue;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;

public class SeedCracker implements ModInitializer {
    public static final String MOD_ID = "crackers";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ArrayList<SeedCrackerAPI> entrypoints = new ArrayList<>();

    private static SeedCracker INSTANCE;
    private final DataStorage dataStorage = new DataStorage();

    /** The world seed once it has been cracked / read. {@code null} while unknown. */
    public static volatile Long foundSeed = null;
    /** Human readable description of how the seed was obtained (for the GUI). */
    public static volatile String foundVia = null;
    /** Short status line shown in the GUI while working. */
    public static volatile String status = "Idle";

    public static SeedCracker get() {
        return INSTANCE;
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;
        Config.load();
        Features.init(Config.get().getVersion());
        FabricLoader.getInstance().getEntrypointContainers("crackers", SeedCrackerAPI.class).forEach(entrypoint ->
                entrypoints.add(entrypoint.getEntrypoint()));

        FinderQueue.registerEvents();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CrackerCommand.register(dispatcher));

        LOGGER.info("Crackers initialized");
    }

    public DataStorage getDataStorage() {
        return this.dataStorage;
    }

    /** Called by the cracking engine (and the singleplayer reader) when a seed is known. */
    public static void reportSeed(long seed, String via) {
        foundSeed = seed;
        foundVia = via;
        status = "Seed found!";
        entrypoints.forEach(e -> e.pushWorldSeed(seed));
        // Persist so rejoining this server does not re-crack from scratch.
        long hashed = 0L;
        try {
            var hashedData = get().getDataStorage().hashedSeedData;
            if (hashedData != null) hashed = hashedData.getHashedSeed();
        } catch (Exception ignored) {
        }
        net.birb.crackers.config.StructureSave.saveSeed(seed, hashed);
    }

    public void reset() {
        SeedCracker.get().getDataStorage().clear();
        FinderQueue.get().finderControl.deleteFinders();
        foundSeed = null;
        foundVia = null;
        status = "Idle";
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
