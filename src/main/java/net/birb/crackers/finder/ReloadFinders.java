package net.birb.crackers.finder;

import net.birb.crackers.finder.decorator.ore.EmeraldOreFinder;
import net.birb.crackers.finder.structure.*;
import net.birb.crackers.util.HeightContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ReloadFinders {

    private ReloadFinders() {
    }

    /**
     * Feeds every currently loaded chunk back through the finders.
     * <p>
     * Finders normally only see a chunk when its packet arrives, so after
     * clearing the data - or turning collection back on - nothing at all is
     * scanned until the player walks into terrain the server has not sent
     * before. It looks exactly like the mod has stopped working, and that is
     * what players reported after pressing Reset.
     */
    public static void rescanLoadedChunks() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.level == null || client.player == null) return;
            int radius = client.options.renderDistance().get();
            int centerX = client.player.blockPosition().getX() >> 4;
            int centerZ = client.player.blockPosition().getZ() >> 4;
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    if (client.level.getChunkSource().getChunkNow(x, z) == null) continue;
                    FinderQueue.get().onChunkData(client.level, new ChunkPos(x, z));
                }
            }
        });
    }

    /**
     * Rebuilds every finder's search positions for a new world height.
     * <p>
     * Runs on the network thread while chunk-scanning threads are iterating
     * these same lists, so nothing is mutated in place: a fresh list is built
     * and published with a single assignment to a volatile field. The previous
     * version cleared and refilled the shared list, which readers would happily
     * walk straight off the end of.
     */
    public static void reloadHeight(int minY, int maxY) {
        List<BlockPos> positions = new ArrayList<>(16 * 16 * Math.max(0, maxY - minY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        Finder.CHUNK_POSITIONS = Collections.unmodifiableList(positions);
        Finder.heightContext = new HeightContext(minY, maxY);

        EmeraldOreFinder.reloadSearchPositions();
        AbstractTempleFinder.reloadSearchPositions();
        BuriedTreasureFinder.reloadSearchPositions();
        EndCityFinder.reloadSearchPositions();
        MonumentFinder.reloadSearchPositions();
        OutpostFinder.reloadSearchPositions();
        IglooFinder.reloadSearchPositions();
        TrialChambersFinder.reloadSearchPositions();
    }

}
