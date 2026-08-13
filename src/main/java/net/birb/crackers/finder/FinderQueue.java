package net.birb.crackers.finder;

import net.birb.crackers.config.Config;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Dispatches chunk data to every enabled finder on a background thread pool.
 * <p>
 * Rendering was intentionally removed from Crackers, so this class only feeds
 * chunks into the finders; the discovered data flows into the cracking engine
 * via each finder's {@code findInChunk}.
 */
public class FinderQueue {

    private final static FinderQueue INSTANCE = new FinderQueue();
    public static ExecutorService SERVICE = Executors.newFixedThreadPool(5);

    public FinderControl finderControl = new FinderControl();

    private FinderQueue() {
        this.clear();
    }

    public static void registerEvents() {
        // No world rendering in Crackers - nothing to register.
    }

    public static FinderQueue get() {
        return INSTANCE;
    }

    public void onChunkData(Level world, ChunkPos chunkPos) {
        if (!Config.get().active) return;
        // Already have the seed - no need to keep scanning chunks.
        if (net.birb.crackers.SeedCracker.foundSeed != null) return;

        getActiveFinderTypes().forEach(type -> SERVICE.submit(() -> {
            try {
                List<Finder> finders = type.finderBuilder.build(world, chunkPos);

                finders.forEach(finder -> {
                    if (finder.isValidDimension(world.dimensionType())) {
                        finder.findInChunk();
                        this.finderControl.addFinder(type, finder);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));
    }

    public List<Finder.Type> getActiveFinderTypes() {
        return Arrays.stream(Finder.Type.values())
                .filter(type -> type.enabled.get())
                .collect(Collectors.toList());
    }

    public void clear() {
        this.finderControl = new FinderControl();
    }
}
