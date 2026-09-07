package net.birb.crackers.cracker.storage;

import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.decorator.DesertWell;
import com.seedfinding.mcfeature.decorator.EndGateway;
import com.seedfinding.mcfeature.structure.BuriedTreasure;
import com.seedfinding.mcfeature.structure.OldStructure;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.Structure;
import com.seedfinding.mcfeature.structure.TriangularStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.cracker.BiomeData;
import net.birb.crackers.cracker.DataAddedEvent;
import net.birb.crackers.cracker.HashedSeedData;
import net.birb.crackers.cracker.PillarData;
import net.birb.crackers.cracker.decorator.Decorator;
import net.birb.crackers.cracker.decorator.DeepDungeon;
import net.birb.crackers.cracker.decorator.Dungeon;
import net.birb.crackers.cracker.decorator.EmeraldOre;
import net.birb.crackers.cracker.decorator.WarpedFungus;
import net.birb.crackers.finder.BlockUpdateQueue;
import net.birb.crackers.gui.CrackerScreen;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DataStorage {

    public static final Comparator<Entry<Feature.Data<?>>> SEED_DATA_COMPARATOR = (s1, s2) -> {
        boolean isStructure1 = s1.data.feature instanceof Structure;
        boolean isStructure2 = s2.data.feature instanceof Structure;

        //Structures always come before decorators.
        if (isStructure1 != isStructure2) {
            return isStructure2 ? 1 : -1;
        }

        if (s1.equals(s2)) {
            return 0;
        }

        double diff = getBits(s2.data.feature, false) - getBits(s1.data.feature, false);
        return diff == 0 ? 1 : (int) Math.signum(diff);
    };
    public ScheduledSet<Entry<Feature.Data<?>>> baseSeedData = new ScheduledSet<>(SEED_DATA_COMPARATOR);
    public HashedSeedData hashedSeedData = null;
    public BlockUpdateQueue blockUpdateQueue = new BlockUpdateQueue();
    public boolean openGui = false;
    protected TimeMachine timeMachine = new TimeMachine(this);
    protected Set<Consumer<DataStorage>> scheduledData = ConcurrentHashMap.newKeySet();
    protected PillarData pillarData = null;
    protected ScheduledSet<Entry<BiomeData>> biomeSeedData = new ScheduledSet<>(null);
    /** Set when new structure data arrives; triggers an auto-save to disk on the next tick. */
    private volatile boolean saveDirty = false;
    /** What the solver is actually doing, so the GUI never claims progress that stopped. */
    private volatile Status status = Status.COLLECTING;

    public static double getBits(Feature feature, boolean decorators18) {
        if (feature instanceof UniformStructure s) {
            return Math.log(s.getOffset() * s.getOffset()) / Math.log(2);
        } else if (feature instanceof TriangularStructure s) {
            return Math.log(s.getPeak() * s.getPeak()) / Math.log(2);
        }
        if (!decorators18 && feature instanceof Decorator && feature.getVersion().isNewerThan(MCVersion.v1_17_1))
            return 0;
        if (feature instanceof BuriedTreasure) return Math.log(100) / Math.log(2);
        if (feature instanceof DesertWell) return Math.log(1000 * 16 * 16) / Math.log(2);
        if (feature instanceof Dungeon) return Math.log(256 * 16 * 16 * 0.125D) / Math.log(2);
        if (feature instanceof DeepDungeon) return Math.log(58 * 16 * 16 * 0.25D) / Math.log(2);
        if (feature instanceof EmeraldOre) return Math.log(28 * 16 * 16 * 0.5D) / Math.log(2);
        if (feature instanceof EndGateway) return Math.log(700 * 16 * 16 * 7) / Math.log(2);
        if (feature instanceof WarpedFungus) return 0;

        throw new UnsupportedOperationException("go do implement bits count for " + feature.getName() + " you fool");
    }

    public void tick() {
        if (SeedCracker.foundSeed == null) {
            net.birb.crackers.util.SeedUtil.trySingleplayerSeed();
        }
        if (openGui) {
            Minecraft.getInstance().setScreenAndShow(new CrackerScreen(Minecraft.getInstance().gui.screen()));
            openGui = false;
        }
        if (!this.timeMachine.isRunning) {
            this.baseSeedData.dump();
            this.biomeSeedData.dump();
            blockUpdateQueue.tick();

            // Persist progress as we go so a disconnect or crash never loses data.
            if (this.saveDirty && Minecraft.getInstance().getConnection() != null) {
                this.saveDirty = false;
                net.birb.crackers.config.StructureSave.saveStructures(this.baseSeedData);
            }

            this.timeMachine.isRunning = true;

            TimeMachine.SERVICE.submit(() -> {
                try {
                    this.scheduledData.removeIf(c -> {
                        c.accept(this);
                        return true;
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }

                this.timeMachine.isRunning = false;
            });
        }
    }

    public synchronized boolean addPillarData(PillarData data, DataAddedEvent event) {
        boolean isAdded = this.pillarData == null;

        if (isAdded && data != null) {
            this.pillarData = data;
            this.schedule(event::onDataAdded);
        }

        return isAdded;
    }

    public synchronized boolean addBaseData(Feature.Data<?> data, DataAddedEvent event) {
        if (SeedCracker.foundSeed != null) return false;
        Entry<Feature.Data<?>> e = new Entry<>(data, event);

        if (this.baseSeedData.contains(e)) {
            return false;
        }

        this.baseSeedData.scheduleAdd(e);
        this.schedule(event::onDataAdded);
        this.saveDirty = true;
        return true;
    }

    public synchronized boolean addBiomeData(BiomeData data, DataAddedEvent event) {
        Entry<BiomeData> e = new Entry<>(data, event);

        if (this.biomeSeedData.contains(e)) {
            return false;
        }

        this.biomeSeedData.scheduleAdd(e);
        this.schedule(event::onDataAdded);
        return true;
    }

    public synchronized boolean addHashedSeedData(HashedSeedData data, DataAddedEvent event) {
        if (this.hashedSeedData == null || this.hashedSeedData.getHashedSeed() != data.getHashedSeed()) {
            this.hashedSeedData = data;
            this.schedule(event::onDataAdded);
            return true;
        }

        return false;
    }

    public void schedule(Consumer<DataStorage> consumer) {
        this.scheduledData.add(consumer);
    }

    public TimeMachine getTimeMachine() {
        return this.timeMachine;
    }

    public double getBaseBits() {
        double bits = 0.0D;
        List<RegionStructure.Data<?>> regionData = new ArrayList<>();

        for (Entry<Feature.Data<?>> e : this.baseSeedData) {
            if (e.data.feature instanceof PillagerOutpost) continue;
            if (e.data instanceof RegionStructure.Data<?> d) {
                regionData.add(d);
            } else {
                bits += getBits(e.data.feature, false);
            }
        }

        for (RegionStructure.Data<?> d : uncorrelated(regionData)) {
            bits += getBits(d.feature, false);
        }
        return bits;
    }

    /**
     * Structures whose position can be lifted, i.e. whose region placement draw
     * is usable by {@link TimeMachine#pokeLifting()}. Outposts are excluded
     * because they are dropped from the verification set (see the cache built in
     * {@code pokeLifting}), so they can never help confirm a structure seed.
     */
    public static boolean isLiftable(Feature<?, ?> feature) {
        if (feature instanceof PillagerOutpost) return false;
        return feature instanceof OldStructure || feature instanceof Shipwreck;
    }

    /**
     * Two structures placed from region seeds that differ by only a few units
     * carry almost the same information: the region seed is
     * {@code regionX*A + regionZ*B + salt + worldSeed}, and the four "old"
     * structure types have consecutive salts (14357617-14357620), so finding a
     * temple, an igloo and a hut in one region is close to finding a single
     * structure three times. Measured: four such structures in one region supply
     * ~15 bits, not the ~37 a naive sum reports.
     */
    private static final long CORRELATION_WINDOW = 16L;

    /** Drops structures that repeat information a nearer-seeded one already gave. */
    private static List<RegionStructure.Data<?>> uncorrelated(List<RegionStructure.Data<?>> data) {
        List<RegionStructure.Data<?>> sorted = new ArrayList<>(data);
        sorted.sort(Comparator.comparingLong(d -> d.baseRegionSeed));

        List<RegionStructure.Data<?>> kept = new ArrayList<>();
        Long last = null;

        for (RegionStructure.Data<?> d : sorted) {
            if (last != null && d.baseRegionSeed - last < CORRELATION_WINDOW) continue;
            last = d.baseRegionSeed;
            kept.add(d);
        }
        return kept;
    }

    /**
     * Usable lifting information, counting a group of mutually correlated
     * structures only once. This is what gates the lift, so it must not
     * over-report or the solver is launched into a search it cannot finish.
     */
    public double getLiftingBits() {
        List<RegionStructure.Data<?>> liftable = new ArrayList<>();

        for (Entry<Feature.Data<?>> e : this.baseSeedData) {
            if (isLiftable(e.data.feature) && e.data instanceof RegionStructure.Data<?> d) {
                liftable.add(d);
            }
        }

        double bits = 0.0D;
        for (RegionStructure.Data<?> d : uncorrelated(liftable)) {
            int offset = ((UniformStructure<?>) d.feature).getOffset();
            bits += Math.log(offset * offset) / Math.log(2);
        }
        return bits;
    }

    /** Number of collected data points per structure type, for the GUI and {@code /cracker bits}. */
    public Map<String, Integer> getTypeCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Entry<Feature.Data<?>> e : this.baseSeedData) {
            counts.merge(e.data.feature.getName(), 1, Integer::sum);
        }
        return counts;
    }

    public double getDecoratorBits() {
        double bits = 0.0D;

        for (Entry<Feature.Data<?>> e : this.baseSeedData) {
            if (e.data.feature instanceof Decorator decorator) {
                bits += getBits(decorator, true);
            }
        }
        return bits;
    }

    public double getWantedBits() {
        return 32.0D;
    }

    /** Number of structure/decorator data points collected so far (for the GUI). */
    public int getStructureCount() {
        return this.baseSeedData.size();
    }

    /** Whether End pillar data has been captured (alternative to lifting bits). */
    public boolean hasPillarData() {
        return this.pillarData != null;
    }

    public Status getStatus() {
        return this.status;
    }

    /** Reports a solver state change to the GUI, and to chat when the run has stalled. */
    public void setStatus(Status status) {
        if (this.status == status) return;
        this.status = status;
        if (status.isStalled()) {
            net.birb.crackers.util.Log.reportStalled(status.getMessage());
        }
    }

    public boolean notEnoughBiomeData() {
        return this.biomeSeedData.size() < 7;
    }

    public void clear() {
        this.scheduledData = ConcurrentHashMap.newKeySet();
        this.pillarData = null;
        this.status = Status.COLLECTING;
        this.baseSeedData = new ScheduledSet<>(SEED_DATA_COMPARATOR);
        this.biomeSeedData = new ScheduledSet<>(null);
        //this.hashedSeedData = null;
        this.timeMachine.shouldTerminate = true;
        this.timeMachine = new TimeMachine(this);
        this.blockUpdateQueue = new BlockUpdateQueue();
    }

    /**
     * Outcome of the last solver attempt. Previously every one of these ended in a
     * bare {@code return false} and the screen kept showing "Cracking the seed...",
     * so a search that had given up looked identical to one still running.
     */
    public enum Status {
        COLLECTING("Collecting world-gen data\u2026", false),
        SOLVING("Cracking the seed\u2026 watch the chat!", false),
        SOLVED("World seed found.", false),
        NEED_MORE_STRUCTURES("Lifting ready - need more structures of any kind.", true),
        TOO_CLUSTERED("Structures are too close together - explore further out.", true),
        TOO_MANY_CANDIDATES("Too many possible seeds - collect more structures.", true),
        NO_RESULT("Nothing matched - a structure may be looted. Try /cracker clear.", true);

        private final String message;
        private final boolean stalled;

        Status(String message, boolean stalled) {
            this.message = message;
            this.stalled = stalled;
        }

        public String getMessage() {
            return this.message;
        }

        /** Whether the pipeline has stopped and needs the player to do something. */
        public boolean isStalled() {
            return this.stalled;
        }
    }

    public static class Entry<T> {
        public final T data;
        public final DataAddedEvent event;

        public Entry(T data, DataAddedEvent event) {
            this.data = data;
            this.event = event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry<?> entry)) return false;

            if (this.data instanceof Feature.Data<?> d1 && entry.data instanceof Feature.Data<?> d2) {
                return d1.feature == d2.feature && d1.chunkX == d2.chunkX && d1.chunkZ == d2.chunkZ;
            } else if (this.data instanceof BiomeData && entry.data instanceof BiomeData) {
                return this.data.equals(entry.data);
            }

            return false;
        }

        @Override
        public int hashCode() {
            if (this.data instanceof Feature.Data) {
                return ((Feature.Data<?>) this.data).chunkX * 31 + ((Feature.Data<?>) this.data).chunkZ;
            } else if (this.data instanceof BiomeData) {
                return this.data.hashCode();
            }

            return super.hashCode();
        }
    }

}
