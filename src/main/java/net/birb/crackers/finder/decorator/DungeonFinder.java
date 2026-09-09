package net.birb.crackers.finder.decorator;

import net.birb.crackers.Features;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.cracker.DataAddedEvent;
import net.birb.crackers.cracker.decorator.Decorator;
import net.birb.crackers.finder.BlockFinder;
import net.birb.crackers.finder.Finder;
import net.birb.crackers.render.Cuboid;
import net.birb.crackers.util.BiomeFixer;
import net.birb.crackers.util.PosIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds monster rooms by their spawner and cobblestone floor.
 * <p>
 * On 1.18 and later a dungeon contributes no bits to the structure seed - its
 * placement moved to the Xoroshiro RNG - but the floor pattern still helps
 * confirm the final world seed, so it is worth collecting.
 * <p>
 * The old anti-x-ray block-update exploit, the floor-call reversal and the
 * dungeon size probe that went with them have been removed: all three were
 * behind a {@code version <= 1.17.1} check, and this mod only loads on 26.2.
 */
public class DungeonFinder extends BlockFinder {

    /** The 9x9 slab under a spawner that a real dungeon floor fills. */
    private static final Set<BlockPos> POSSIBLE_FLOOR_POSITIONS = PosIterator.create(
            new BlockPos(-4, -1, -4),
            new BlockPos(4, -1, 4)
    );

    /** How many of those must be cobblestone before we believe it. */
    private static final int MIN_FLOOR_BLOCKS = 20;

    public DungeonFinder(Level world, ChunkPos chunkPos) {
        super(world, chunkPos, Blocks.SPAWNER);
        this.searchPositions = CHUNK_POSITIONS;
    }

    public static List<Finder> create(Level world, ChunkPos chunkPos) {
        List<Finder> finders = new ArrayList<>();

        for (int chunkX = chunkPos.x() - 1; chunkX <= chunkPos.x() + 1; chunkX++) {
            for (int chunkZ = chunkPos.z() - 1; chunkZ <= chunkPos.z() + 1; chunkZ++) {
                if (surroundingChunksLoaded(chunkX, chunkZ, world)) {
                    finders.add(new DungeonFinder(world, new ChunkPos(chunkX, chunkZ)));
                }
            }
        }

        return finders;
    }

    private static boolean surroundingChunksLoaded(int chunkX, int chunkZ, Level world) {
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                if (world.getChunkSource().getChunkNow(x, z) == null) return false;
            }
        }
        return true;
    }

    @Override
    public List<BlockPos> findInChunk() {
        // Every position holding a mob spawner in this chunk.
        List<BlockPos> result = super.findInChunk();
        if (result.size() != 1) return new ArrayList<>();

        result.removeIf(pos -> {
            BlockEntity blockEntity = this.world.getBlockEntity(pos);
            if (!(blockEntity instanceof SpawnerBlockEntity)) return true;

            int floor = 0;
            for (BlockPos offset : POSSIBLE_FLOOR_POSITIONS) {
                Block block = this.world.getBlockState(pos.offset(offset)).getBlock();
                if (block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE) floor++;
            }
            return floor < MIN_FLOOR_BLOCKS;
        });

        if (result.size() != 1) return new ArrayList<>();

        BlockPos pos = result.get(0);
        Biome biome = this.world.getNoiseBiome((this.chunkPos.x() << 2) + 2, 0, (this.chunkPos.z() << 2) + 2).value();

        Decorator.Data<?> data = pos.getY() < 0
                ? Features.DEEP_DUNGEON.at(pos.getX(), pos.getY(), pos.getZ(), BiomeFixer.swap(biome))
                : Features.DUNGEON.at(pos.getX(), pos.getY(), pos.getZ(), null, null, BiomeFixer.swap(biome), null);

        if (SeedCracker.get().getDataStorage().addBaseData(data, DataAddedEvent.POKE_BIOMES)) {
            this.cuboids.add(new Cuboid(pos, ARGB.color(255, 0, 0)));
        }
        return result;
    }

    @Override
    public boolean isValidDimension(DimensionType dimension) {
        return this.isOverworld(dimension);
    }

}
