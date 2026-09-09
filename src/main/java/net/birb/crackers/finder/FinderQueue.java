package net.birb.crackers.finder;

import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.util.Pools;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dispatches chunk data to every enabled finder on a background thread pool.
 * <p>
 * Rendering was intentionally removed from Crackers, so this class only feeds
 * chunks into the finders; the discovered data flows into the cracking engine
 * via each finder's {@code findInChunk}.
 */
public final class FinderQueue {

    private static final FinderQueue INSTANCE = new FinderQueue();

    private FinderQueue() {
    }

    public static FinderQueue get() {
        return INSTANCE;
    }

    public void onChunkData(Level world, ChunkPos chunkPos) {
        if (!Config.get().active) return;
        // Already have the seed - no need to keep scanning chunks.
        if (SeedCracker.foundSeed != null) return;

        for (Finder.Type type : getActiveFinderTypes()) {
            Pools.FINDERS.execute(() -> {
                try {
                    for (Finder finder : type.finderBuilder.build(world, chunkPos)) {
                        if (finder.isValidDimension(world.dimensionType())) {
                            finder.findInChunk();
                        }
                    }
                } catch (Throwable t) {
                    // Chunk data is read off-thread while the client mutates it,
                    // so a torn read is possible. Losing one chunk's scan is
                    // fine; taking the pool thread down with it is not.
                    //
                    // This used to be logged only with debug on, which meant a
                    // finder that threw on every single chunk - as the trial
                    // chamber finder did - simply collected nothing, silently,
                    // with no way to tell that from "there are none nearby".
                    // The first few failures per type are always reported.
                    reportFailure(type, chunkPos, t);
                }
            });
        }
    }

    /** Failures already reported per finder type, so one bad type cannot spam. */
    private final Map<Finder.Type, AtomicInteger> failures = new ConcurrentHashMap<>();

    private static final int MAX_REPORTS_PER_TYPE = 3;

    private void reportFailure(Finder.Type type, ChunkPos chunkPos, Throwable t) {
        int seen = this.failures.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        if (seen <= MAX_REPORTS_PER_TYPE) {
            SeedCracker.LOGGER.warn("Crackers: the {} finder failed on chunk {} - it will collect nothing until this is fixed",
                    type, chunkPos, t);
            if (seen == MAX_REPORTS_PER_TYPE) {
                SeedCracker.LOGGER.warn("Crackers: further {} finder failures will not be logged", type);
            }
        } else if (Config.get().debug) {
            SeedCracker.LOGGER.warn("finder {} failed on chunk {}", type, chunkPos, t);
        }
    }

    /** How many times each finder type has thrown, for {@code /cracker status}. */
    public Map<Finder.Type, Integer> failureCounts() {
        Map<Finder.Type, Integer> out = new java.util.LinkedHashMap<>();
        this.failures.forEach((type, count) -> out.put(type, count.get()));
        return out;
    }

    public List<Finder.Type> getActiveFinderTypes() {
        List<Finder.Type> active = new ArrayList<>();
        for (Finder.Type type : Finder.Type.values()) {
            if (type.isEnabled()) active.add(type);
        }
        return active;
    }
}
