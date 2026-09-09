package net.birb.crackers.finder.decorator;

import com.seedfinding.mccore.version.MCVersion;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.cracker.DataAddedEvent;
import net.birb.crackers.cracker.PillarData;
import net.birb.crackers.finder.BlockFinder;
import net.birb.crackers.finder.Finder;
import net.birb.crackers.render.Cuboid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads the ten obsidian pillars around the End spawn.
 * <p>
 * Their heights are a shuffle of ten fixed values driven by the low 16 bits of
 * the world seed, so seeing the layout is worth as much as the entire lifting
 * requirement.
 */
public class EndPillarsFinder extends Finder {

    /** Lowest and highest a pillar cap can sit: 76 + index * 3 for index 0..9. */
    private static final int MIN_CAP_Y = 76;
    private static final int MAX_CAP_Y = 76 + 3 * 9;

    private final boolean alreadyFound;
    protected BedrockMarkerFinder[] bedrockMarkers = new BedrockMarkerFinder[10];

    public EndPillarsFinder(Level world, ChunkPos chunkPos) {
        super(world, chunkPos);

        this.alreadyFound = !SeedCracker.get().getDataStorage().addPillarData(null, DataAddedEvent.POKE_PILLARS);
        if (this.alreadyFound) return;

        for (int i = 0; i < this.bedrockMarkers.length; i++) {
            double x = 42.0D * Math.cos(2.0D * (-Math.PI + (Math.PI / 10.0D) * (double) i));
            double z = 42.0D * Math.sin(2.0D * (-Math.PI + (Math.PI / 10.0D) * (double) i));
            if (Config.get().getVersion().isOlderThan(MCVersion.v1_14)) {
                x = Math.round(x);
                z = Math.round(z);
            }
            BlockPos pillar = BlockPos.containing(x, 0, z);
            this.bedrockMarkers[i] = new BedrockMarkerFinder(this.world, ChunkPos.containing(pillar), pillar);
        }
    }

    public static List<Finder> create(Level world, ChunkPos chunkPos) {
        List<Finder> finders = new ArrayList<>();
        finders.add(new EndPillarsFinder(world, chunkPos));
        return finders;
    }

    @Override
    public List<BlockPos> findInChunk() {
        List<BlockPos> result = new ArrayList<>();

        for (BedrockMarkerFinder bedrockMarker : this.bedrockMarkers) {
            if (bedrockMarker == null) continue;
            List<BlockPos> hits = bedrockMarker.findInChunk();
            // Exactly one cap per pillar, or the reading is not trustworthy.
            if (hits.size() != 1) return new ArrayList<>();
            result.addAll(hits);
        }

        if (result.size() == this.bedrockMarkers.length) {
            PillarData pillarData = new PillarData(result.stream().map(Vec3i::getY).collect(Collectors.toList()));

            if (SeedCracker.get().getDataStorage().addPillarData(pillarData, DataAddedEvent.POKE_PILLARS)) {
                result.forEach(pos -> this.cuboids.add(new Cuboid(pos, ARGB.color(128, 0, 128))));
            }
        }

        return result;
    }

    @Override
    public boolean isValidDimension(DimensionType dimension) {
        return this.isEnd(dimension);
    }

    /**
     * Looks for the bedrock cap of one specific pillar.
     * <p>
     * The pillar coordinate used to be passed in and then ignored, so this
     * searched every column of the chunk instead of the one the pillar is in.
     * Any stray bedrock in that chunk within the cap height range would be read
     * as a pillar height, and one wrong height poisons the entire pillar seed.
     */
    public static class BedrockMarkerFinder extends BlockFinder {

        public BedrockMarkerFinder(Level world, ChunkPos chunkPos, BlockPos pillar) {
            super(world, chunkPos, Blocks.BEDROCK);
            this.searchPositions = columnFor(pillar);
        }

        private static List<BlockPos> columnFor(BlockPos pillar) {
            int localX = pillar.getX() & 15;
            int localZ = pillar.getZ() & 15;
            List<BlockPos> column = new ArrayList<>(MAX_CAP_Y - MIN_CAP_Y + 1);
            for (int y = MIN_CAP_Y; y <= MAX_CAP_Y; y++) {
                column.add(new BlockPos(localX, y, localZ));
            }
            return column;
        }

        @Override
        public boolean isValidDimension(DimensionType dimension) {
            return true;
        }
    }
}
