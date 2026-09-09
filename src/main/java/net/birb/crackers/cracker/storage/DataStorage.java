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
import net.birb.crackers.Features;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.config.StructureSave;
import net.birb.crackers.cracker.BiomeData;
import net.birb.crackers.cracker.DataAddedEvent;
import net.birb.crackers.cracker.HashedSeedData;
import net.birb.crackers.cracker.PillarData;
import net.birb.crackers.cracker.decorator.Decorator;
import net.birb.crackers.cracker.decorator.DeepDungeon;
import net.birb.crackers.cracker.decorator.Dungeon;
import net.birb.crackers.cracker.decorator.EmeraldOre;
import net.birb.crackers.cracker.decorator.WarpedFungus;
import net.birb.crackers.util.Log;
import net.birb.crackers.util.Pools;
import net.birb.crackers.util.SeedUtil;
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

    /**
     * Orders collected data for display: structures before decorators, then by
     * how much each is worth.
     * <p>
     * This used to be the ordering of a {@code TreeSet} that also had to answer
     * {@code contains}, which it could not do correctly - it returns 1 in both
     * directions for two distinct entries worth the same number of bits, so it
     * is not a valid total order and the tree it built was not a valid search
     * tree. Membership now goes through a {@code LinkedHashSet} and this is
     * only ever used to sort a snapshot.
     */
    public static final Comparator<Entry<Feature.Data<?>>> SEED_DATA_COMPARATOR = (s1, s2) -> {
        boolean isStructure1 = s1.data.feature instanceof Structure;
        boolean isStructure2 = s2.data.feature instanceof Structure;

        if (isStructure1 != isStructure2) {
            return isStructure2 ? 1 : -1;
        }

        int byBits = Double.compare(getBits(s2.data.feature, false), getBits(s1.data.feature, false));
        if (byBits != 0) return byBits;

        int byName = Features.nameOf(s1.data.feature).compareTo(Features.nameOf(s2.data.feature));
        if (byName != 0) return byName;

        int byX = Integer.compare(s1.data.chunkX, s2.data.chunkX);
        return byX != 0 ? byX : Integer.compare(s1.data.chunkZ, s2.data.chunkZ);
    };

    public ScheduledSet<Entry<Feature.Data<?>>> baseSeedData = new ScheduledSet<>();
    public volatile HashedSeedData hashedSeedData = null;
    protected TimeMachine timeMachine = new TimeMachine(this);
    protected Set<Consumer<DataStorage>> scheduledData = ConcurrentHashMap.newKeySet();
    /**
     * Structures proven not to belong to this world.
     * <p>
     * Without this, dropping a griefed structure achieves nothing: the moment
     * its chunk is scanned again - on rejoining, or on the rescan that follows
     * a reset - it comes straight back and the search fails the same way.
     */
    private final Set<String> ignored = ConcurrentHashMap.newKeySet();
    protected volatile PillarData pillarData = null;
    protected ScheduledSet<Entry<BiomeData>> biomeSeedData = new ScheduledSet<>();
    /** Set when new structure data arrives; triggers an auto-save on the next tick. */
    private volatile boolean saveDirty = false;
    /** What the solver is actually doing, so the GUI never claims progress that stopped. */
    private volatile Status status = Status.COLLECTING;
    /**
     * The last diagnosis, so the screen can say "Jungle Pyramid at chunk X is
     * the problem" rather than the canned "one of them is probably looted".
     * Chat was already getting the specific version while the screen showed the
     * generic one, which read as the mod contradicting itself.
     */
    private volatile Diagnosis diagnosis = null;

    /** A solver diagnosis in a form the UI can render without importing the solver. */
    public record Diagnosis(String summary, List<String> details) {
    }

    public Diagnosis getDiagnosis() {
        return this.diagnosis;
    }

    public void setDiagnosis(Diagnosis diagnosis) {
        this.diagnosis = diagnosis;
    }

    public static double getBits(Feature<?, ?> feature, boolean decorators18) {
        if (feature instanceof UniformStructure<?> s) {
            return Math.log(s.getOffset() * s.getOffset()) / Math.log(2);
        } else if (feature instanceof TriangularStructure<?> s) {
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

        // An unknown feature type is a bug, but throwing here would take the
        // client down from a background thread over a progress-bar number.
        SeedCracker.LOGGER.warn("no bit count implemented for {}", Features.nameOf(feature));
        return 0;
    }

    public void tick() {
        if (SeedCracker.foundSeed == null) {
            SeedUtil.trySingleplayerSeed();
        }
        if (!this.timeMachine.isRunning) {
            this.baseSeedData.dump();
            this.biomeSeedData.dump();

            // Persist progress as we go so a disconnect or crash never loses data.
            if (this.saveDirty && Minecraft.getInstance().getConnection() != null) {
                this.saveDirty = false;
                StructureSave.saveStructures(this.baseSeedData);
            }

            this.timeMachine.isRunning = true;

            // The coordinator pool is separate from the solver pool on purpose:
            // this task blocks on solver workers, and when both lived in the
            // same five-thread pool a coordinator plus its four workers filled
            // it exactly.
            Pools.COORDINATOR.execute(() -> {
                try {
                    this.scheduledData.removeIf(consumer -> {
                        consumer.accept(this);
                        return true;
                    });
                } catch (Throwable t) {
                    SeedCracker.LOGGER.error("Crackers solver task failed", t);
                } finally {
                    this.timeMachine.isRunning = false;
                }
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
        if (this.ignored.contains(key(data))) return false;
        Entry<Feature.Data<?>> e = new Entry<>(data, event);

        if (this.baseSeedData.contains(e)) {
            return false;
        }

        this.baseSeedData.scheduleAdd(e);
        this.schedule(event::onDataAdded);
        this.saveDirty = true;
        return true;
    }

    /** Beyond this the biome search costs more than the extra samples are worth. */
    private static final int MAX_BIOME_SAMPLES = 256;

    public synchronized boolean addBiomeData(BiomeData data, DataAddedEvent event) {
        if (this.biomeSeedData.size() >= MAX_BIOME_SAMPLES) return false;
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

    /** Drops a structure and refuses to collect it again this session. */
    public synchronized void forget(Feature.Data<?> data) {
        this.ignored.add(key(data));
        String key = key(data);
        this.baseSeedData.removeIf(entry -> key.equals(key(entry.data)));
        this.saveDirty = true;
    }

    public int ignoredCount() {
        return this.ignored.size();
    }

    private static String key(Feature.Data<?> data) {
        return Features.nameOf(data.feature) + "@" + data.chunkX + "," + data.chunkZ;
    }

    public void schedule(Consumer<DataStorage> consumer) {
        this.scheduledData.add(consumer);
    }

    public TimeMachine getTimeMachine() {
        return this.timeMachine;
    }

    public PillarData getPillarData() {
        return this.pillarData;
    }

    /** A stable copy of the collected structures, safe to iterate off-thread. */
    public List<Entry<Feature.Data<?>>> snapshotBaseData() {
        return this.baseSeedData.snapshot();
    }

    public List<Entry<BiomeData>> snapshotBiomeData() {
        return this.biomeSeedData.snapshot();
    }

    public void clearBiomeData() {
        this.biomeSeedData.clear();
    }

    /** Drops anything collected under a different game version. */
    public void dropDataFromOtherVersions() {
        MCVersion version = Config.get().getVersion();
        this.baseSeedData.removeIf(entry -> !entry.data.feature.getVersion().equals(version));
    }

    /**
     * The structures a candidate seed has to satisfy.
     * <p>
     * Decorators are excluded on 1.18+ because their placement moved to the
     * Xoroshiro RNG and no longer depends on the structure seed at all, and
     * pillager outposts are excluded because their {@code canStart} also
     * consults a weak seed and nearby villages.
     * <p>
     * Every caller must use this. {@code /cracker check} once passed the raw
     * data instead, which on a world with thirty-one collected dungeons meant
     * thirty-one leave-one-out solves for structures the solver ignores anyway.
     */
    public List<Feature.Data<?>> solverInput() {
        List<Feature.Data<?>> cache = new ArrayList<>();
        for (Entry<Feature.Data<?>> entry : snapshotBaseData()) {
            Feature<?, ?> feature = entry.data.feature;
            if (feature instanceof Decorator && !feature.getVersion().isOlderThan(MCVersion.v1_18)) continue;
            if (feature instanceof PillagerOutpost) continue;
            cache.add(entry.data);
        }
        return cache;
    }

    public double getBaseBits() {
        double bits = 0.0D;
        List<RegionStructure.Data<?>> regionData = new ArrayList<>();

        for (Entry<Feature.Data<?>> e : this.snapshotBaseData()) {
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
     * is usable by the solver. Outposts are excluded because they are dropped
     * from the verification set, so they can never help confirm a seed.
     */
    public static boolean isLiftable(Feature<?, ?> feature) {
        if (feature instanceof PillagerOutpost) return false;
        // Anything drawn uniformly inside its region works: the four "old"
        // structure types, shipwrecks, and trial chambers, which are a uniform
        // structure too and were simply never listed here.
        return feature instanceof UniformStructure;
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

        for (Entry<Feature.Data<?>> e : this.snapshotBaseData()) {
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
        for (Entry<Feature.Data<?>> e : this.snapshotBaseData()) {
            counts.merge(Features.nameOf(e.data.feature), 1, Integer::sum);
        }
        return counts;
    }

    public double getDecoratorBits() {
        double bits = 0.0D;

        for (Entry<Feature.Data<?>> e : this.snapshotBaseData()) {
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
    public synchronized void setStatus(Status status) {
        if (this.status == status) return;
        this.status = status;
        if (status.isStalled()) {
            Log.reportStalled(status.getMessage());
        }
    }

    /**
     * Biome samples are only worth searching once they cover enough distinct
     * biomes. Counting raw samples would be misleading now that two samples of
     * the same biome at different coordinates are both kept: sixteen readings
     * from one plains chunk are not seven biomes' worth of evidence.
     */
    /** Whether the server gave us a hashed seed we can actually use. */
    public boolean hasUsableHashedSeed() {
        HashedSeedData data = this.hashedSeedData;
        return data != null && data.getHashedSeed() != 0L;
    }

    public boolean notEnoughBiomeData() {
        Set<String> distinct = new java.util.HashSet<>();
        for (Entry<BiomeData> e : this.snapshotBiomeData()) {
            distinct.add(e.data.biome.getName());
        }
        return distinct.size() < 7;
    }

    public void clear() {
        this.scheduledData = ConcurrentHashMap.newKeySet();
        this.pillarData = null;
        this.status = Status.COLLECTING;
        this.diagnosis = null;
        this.ignored.clear();
        this.saveDirty = false;
        this.baseSeedData = new ScheduledSet<>();
        this.biomeSeedData = new ScheduledSet<>();
        this.timeMachine.terminate();
        this.timeMachine = new TimeMachine(this);
    }

    /**
     * Outcome of the last solver attempt.
     * <p>
     * Every one of these used to be a bare {@code return false}, so a search
     * that had given up looked exactly like one still running. Each now carries
     * a headline and the steps that actually resolve it, because "finished
     * search with no results :(" told players nothing they could act on.
     */
    public enum Status {
        COLLECTING("Collecting world data", false),
        SOLVING("Searching for the seed", false),
        SOLVED("World seed found", false),

        NEED_MORE_STRUCTURES("Find a few more structures of any kind", true,
                "The position bar is full, but there is not yet enough data",
                "to confirm which seed is the right one.",
                "",
                "Anything counts: monuments, end cities, buried treasure,",
                "dungeons, or more of what you already have."),

        TOO_CLUSTERED("Your structures are too close together", true,
                "Several of your finds sit in the same region, so they",
                "repeat information rather than adding any.",
                "",
                "Travel a few thousand blocks and collect structures",
                "there instead."),

        TOO_MANY_CANDIDATES("Too many possible seeds to narrow down", true,
                "The search found the right seed but cannot tell it apart",
                "from the others yet.",
                "",
                "A few more structures of any kind will separate them."),

        NO_RESULT("Your collected data contradicts itself", true,
                "No single seed can produce all of these structures at once.",
                "That means at least one of them is not where the world",
                "originally generated it.",
                "",
                "Usually one structure was looted, moved or griefed.",
                "Run /cracker clear and collect fresh, untouched ones.",
                "",
                "It can also mean the server deliberately randomises",
                "structure placement. Run /cracker check to find out."),

        SERVER_PROTECTED("This server looks protected against cracking", true,
                "Your structures are individually consistent but disagree",
                "with each other, which is what a server-side seed",
                "randomiser looks like.",
                "",
                "Run /cracker check for the details of which types",
                "disagree.");

        private final String headline;
        private final boolean stalled;
        private final List<String> steps;

        Status(String headline, boolean stalled, String... steps) {
            this.headline = headline;
            this.stalled = stalled;
            this.steps = List.of(steps);
        }

        public String getHeadline() {
            return this.headline;
        }

        public List<String> getSteps() {
            return this.steps;
        }

        /** Kept for chat, which wants one line rather than a panel. */
        public String getMessage() {
            return this.headline;
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
            if (this.data instanceof Feature.Data<?> d) {
                return (d.chunkX * 31 + d.chunkZ) * 31 + Features.nameOf(d.feature).hashCode();
            } else if (this.data instanceof BiomeData) {
                return this.data.hashCode();
            }

            return super.hashCode();
        }
    }

}
