package net.birb.crackers.config;

import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Structure;
import net.birb.crackers.Features;
import net.birb.crackers.cracker.storage.DataStorage;
import net.birb.crackers.cracker.storage.ScheduledSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class StructureSave {
    private static final Logger logger = LoggerFactory.getLogger("structureSave");

    public static final Path saveDir = Paths.get(FabricLoader.getInstance().getConfigDir().toFile().toString(), "SeedCrackerX saved structures");

    public static void saveStructures(ScheduledSet<DataStorage.Entry<Feature.Data<?>>> baseData) {
        try {
            Files.createDirectories(saveDir);
            Path saveFile = saveDir.resolve(getWorldName());
            Files.deleteIfExists(saveFile);
            Files.createFile(saveFile);
            try (FileWriter writer = new FileWriter(saveFile.toFile())) {
                for (DataStorage.Entry<Feature.Data<?>> dataEntry : baseData) {
                    if (dataEntry.data.feature instanceof Structure structure) {
                        String data = Structure.getName(structure.getClass()) +
                            ";" + dataEntry.data.chunkX +
                            ";" + dataEntry.data.chunkZ +
                            "\n";
                        writer.write(data);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("seedcracker couldn't save structures", e);
        }
    }

    /** Removes the saved-progress file for the current world/server (used by "Clear Data"). */
    public static void deleteSave() {
        try {
            Files.deleteIfExists(saveDir.resolve(getWorldName()));
            Files.deleteIfExists(seedFile());
        } catch (IOException e) {
            logger.error("seedcracker couldn't delete the saved structures file", e);
        }
    }

    /**
     * Persists a cracked world seed for the current world/server so rejoining
     * does not re-run the cracking pipeline.
     *
     * @param seed       the cracked world seed
     * @param hashedSeed the server's hashed seed at the time of cracking (0 if unknown)
     */
    public static void saveSeed(long seed, long hashedSeed) {
        try {
            Files.createDirectories(saveDir);
            Path file = seedFile();
            Files.deleteIfExists(file);
            try (FileWriter writer = new FileWriter(file.toFile())) {
                writer.write(seed + ";" + hashedSeed + "\n");
            }
        } catch (IOException e) {
            logger.error("seedcracker couldn't save the cracked seed", e);
        }
    }

    /**
     * Loads a previously cracked seed for this world/server, if one exists and
     * still matches the current hashed seed (so a world reset is detected).
     *
     * @param currentHashedSeed the hashed seed from the current login packet
     * @return the saved world seed, or {@code null} if none / mismatched / invalid
     */
    public static Long loadSeed(long currentHashedSeed) {
        try {
            Path file = seedFile();
            if (!Files.exists(file)) return null;
            try (
                FileInputStream fis = new FileInputStream(file.toFile());
                Scanner sc = new Scanner(fis)
            ) {
                if (!sc.hasNextLine()) return null;
                String[] info = sc.nextLine().trim().split(";");
                if (info.length < 1) return null;
                long seed = Long.parseLong(info[0]);
                long savedHash = info.length >= 2 ? Long.parseLong(info[1]) : 0L;
                // If we have both hashes and they differ, the world was likely reset.
                if (savedHash != 0L && currentHashedSeed != 0L && savedHash != currentHashedSeed) {
                    logger.info("saved seed hash mismatch - treating as a new world");
                    deleteSave();
                    return null;
                }
                return seed;
            }
        } catch (FileNotFoundException e) {
            return null;
        } catch (Exception e) {
            logger.error("seedcracker couldn't load the cracked seed", e);
            return null;
        }
    }

    private static Path seedFile() {
        String name = getWorldName();
        if (name.endsWith(".txt")) {
            name = name.substring(0, name.length() - 4) + ".seed";
        } else {
            name = name + ".seed";
        }
        return saveDir.resolve(name);
    }

    public static List<RegionStructure.Data<?>> loadStructures() {
        List<RegionStructure.Data<?>> result = new ArrayList<>();
        try {
            Files.createDirectories(saveDir);
            Path saveFile = saveDir.resolve(getWorldName());
            try (
                FileInputStream fis = new FileInputStream(saveFile.toFile());
                Scanner sc = new Scanner(fis)
            ) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    String[] info = line.split(";");
                    if (info.length != 3) continue;
                    String structureName = info[0];
                    for (RegionStructure<?,?> idk : Features.STRUCTURE_TYPES) {
                        if (structureName.equals(idk.getName())) {
                            result.add(idk.at(Integer.parseInt(info[1]), Integer.parseInt(info[2])));
                            break;
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            logger.warn("seedcracker couldn't find a structures file");
            return result;
        } catch (IOException e) {
            logger.error("seedcracker couldn't load previous structures", e);
        }
        return result;
    }

    private static String getWorldName() {
        Minecraft minecraftClient = Minecraft.getInstance();
        if (minecraftClient.getConnection() != null) {
            Connection connection = minecraftClient.getConnection().getConnection();
            if (connection.isMemoryConnection()) {
                String address = minecraftClient.getSingleplayerServer().getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
                return address.replace("/","_").replace(":", "_")+".txt";
            } else {
                return connection.getRemoteAddress().toString().replace("/","_").replace(":","_")+".txt";
            }
        }
        return "Invalid.txt";
    }
}
