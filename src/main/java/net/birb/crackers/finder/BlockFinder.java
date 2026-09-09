package net.birb.crackers.finder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds every occurrence of one block in a chunk.
 *
 * <h2>Why this is not a plain loop any more</h2>
 * A full-height chunk column is 16 x 16 x 384 = 98,304 positions. Shipwreck and
 * dungeon finders each build nine of these per received chunk packet, so the old
 * implementation - one {@code getBlockState} per position, per finder - cost
 * roughly 1.8 million block lookups for every chunk the server sent, on
 * background threads, while the player was just walking around.
 * <p>
 * Almost all of that work was provably pointless. Chunk storage is already
 * divided into 16-cube sections, each carrying a palette of the block states it
 * contains, so {@code maybeHas} answers "could there be a chest in here?" by
 * scanning a few dozen palette entries instead of 4,096 positions. A section
 * that cannot contain the target is skipped whole.
 * <p>
 * Spawners, chests, end gateways and emerald ore are rare, so in practice this
 * turns the per-chunk cost from about 98,304 lookups per finder into a couple
 * of dozen palette scans plus the handful of sections that really do contain
 * one.
 */
public abstract class BlockFinder extends Finder {

    private static final byte UNKNOWN = 0;
    private static final byte SKIP = 1;
    private static final byte SCAN = 2;

    private final Block targetBlock;
    private final Predicate<BlockState> palettePredicate;
    protected List<BlockPos> searchPositions = new ArrayList<>();

    public BlockFinder(Level world, ChunkPos chunkPos, Block block) {
        super(world, chunkPos);
        this.targetBlock = block;
        this.palettePredicate = state -> state.getBlock() == block;
    }

    @Override
    public List<BlockPos> findInChunk() {
        List<BlockPos> result = new ArrayList<>();
        if (this.searchPositions.isEmpty()) return result;

        ChunkAccess chunk = this.world.getChunk(this.chunkPos.getWorldPosition());
        if (chunk == null) return result;

        LevelChunkSection[] sections = chunk.getSections();
        if (sections.length == 0) return result;

        // Verdict per section, worked out at most once each. Caching this
        // rather than relying on the iteration order keeps the finder correct
        // whatever order its search positions happen to be in.
        byte[] verdict = new byte[sections.length];
        BlockPos origin = this.chunkPos.getWorldPosition();

        for (BlockPos pos : this.searchPositions) {
            int index = chunk.getSectionIndex(pos.getY());
            if (index < 0 || index >= sections.length) continue;

            if (verdict[index] == UNKNOWN) {
                LevelChunkSection section = sections[index];
                boolean usable = section != null && !section.hasOnlyAir()
                        && section.maybeHas(this.palettePredicate);
                verdict[index] = usable ? SCAN : SKIP;
            }
            if (verdict[index] == SKIP) continue;

            BlockState state = sections[index].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            if (state.getBlock() == this.targetBlock) {
                result.add(origin.offset(pos));
            }
        }

        return result;
    }
}
