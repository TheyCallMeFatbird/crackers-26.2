package net.birb.crackers.finder;

import net.birb.crackers.finder.decorator.EndPillarsFinder;
import net.birb.crackers.finder.decorator.ore.EmeraldOreFinder;
import net.birb.crackers.finder.structure.*;
import net.birb.crackers.util.HeightContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public class ReloadFinders {
    public Minecraft client = Minecraft.getInstance();

    public static void reloadHeight(int minY, int maxY) {
        Finder.CHUNK_POSITIONS.clear();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    Finder.CHUNK_POSITIONS.add(new BlockPos(x, y, z));
                }
            }
        }
        Finder.heightContext = new HeightContext(minY, maxY);

        EmeraldOreFinder.reloadSearchPositions();
        EndPillarsFinder.BedrockMarkerFinder.reloadSearchPositions();
        AbstractTempleFinder.reloadSearchPositions();
        BuriedTreasureFinder.reloadSearchPositions();
        EndCityFinder.reloadSearchPositions();
        MonumentFinder.reloadSearchPositions();
        OutpostFinder.reloadSearchPositions();
        IglooFinder.reloadSearchPositions();
        TrialChambersFinder.reloadSearchPositions();
    }

    public void reload() {
        int renderdistance = client.options.renderDistance().get();

        int playerChunkX = (int) (Math.round(client.player.getX()) >> 4);
        int playerChunkZ = (int) (Math.round(client.player.getZ()) >> 4);
        for (int i = playerChunkX - renderdistance; i < playerChunkX + renderdistance; i++) {
            for (int j = playerChunkZ - renderdistance; j < playerChunkZ + renderdistance; j++) {
                FinderQueue.get().onChunkData(client.level, new ChunkPos(i, j));
            }
        }
    }
}
