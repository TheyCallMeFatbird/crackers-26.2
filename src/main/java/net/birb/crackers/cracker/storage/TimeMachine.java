package net.birb.crackers.cracker.storage;

import com.seedfinding.mcbiome.source.OverworldBiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.PillarSeed;
import com.seedfinding.mccore.rand.seed.StructureSeed;
import com.seedfinding.mccore.rand.seed.WorldSeed;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcseed.lcg.LCG;
import net.birb.crackers.Features;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.cracker.BiomeData;
import net.birb.crackers.cracker.decorator.Decorator;
import net.birb.crackers.cracker.solver.SolverDiagnostics;
import net.birb.crackers.cracker.solver.SolverSelfTest;
import net.birb.crackers.cracker.solver.StructureSeedSolver;
import net.birb.crackers.util.Log;
import net.birb.crackers.util.Pools;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TimeMachine {
    private static final Logger logger = LoggerFactory.getLogger("timeMachine");

    private final LCG inverseLCG = LCG.JAVA.combine(-2);

    /**
     * Read by every solver worker in its innermost loop and written by the
     * client thread when you leave a world. Without {@code volatile} the JIT is
     * free to hoist the read out of the loop, and a cancelled search happily
     * keeps burning every core until the game closes.
     */
    public volatile boolean isRunning = false;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public List<Integer> pillarSeeds = null;
    /** Written from several workers at once, so it cannot be a plain HashSet. */
    public Set<Long> structureSeeds = ConcurrentHashMap.newKeySet();
    public Set<Long> worldSeeds = ConcurrentHashMap.newKeySet();
    protected DataStorage dataStorage;
    /** Total bits present when the last lift failed, so it is not retried on every new find. */
    private double lastFailedAtBits = -1.0D;

    public TimeMachine(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    /** Stops any running search. Not reversible - the storage makes a new one. */
    public void terminate() {
        this.cancelled.set(true);
    }

    public boolean isTerminated() {
        return this.cancelled.get();
    }

    public void poke(Phase phase) {
        if (SeedCracker.foundSeed != null || this.worldSeeds.size() == 1) return;
        this.isRunning = true;

        while (phase != null && !this.cancelled.get()) {
            if (phase != Phase.BIOMES && pokeStructureReduce()) {
                phase = Phase.BIOMES;
                continue;

            } else if (phase == Phase.STRUCTURES) {
                if (!pokeStructures()) break;

            } else if (phase == Phase.LIFTING) {
                if (!pokeStructures() && !pokeLifting()) break;

            } else if (phase == Phase.PILLARS) {
                if (!this.pokePillars()) break;

            } else if (phase == Phase.BIOMES) {
                if (!this.pokeBiomes()) break;
            }

            phase = phase.nextPhase();
        }
        if (this.worldSeeds.size() == 1 && !this.cancelled.get()) {
            long seed = this.worldSeeds.iterator().next();
            this.dataStorage.setStatus(DataStorage.Status.SOLVED);
            SeedCracker.reportSeed(seed, describeConfidence(seed));
        }
    }

    /**
     * How much to trust the answer.
     * <p>
     * When the server sent a hashed seed we can hash our candidate and compare,
     * which is a complete proof: only the real world seed hashes to that value
     * and also has these structures. Without one, the answer rests on the
     * collected structures alone and deserves to be labelled as such - players
     * were reporting "it gave me the wrong seed" with no way to tell the two
     * cases apart.
     */
    private String describeConfidence(long worldSeed) {
        if (this.dataStorage.hasUsableHashedSeed()
                && WorldSeed.toHash(worldSeed) == this.dataStorage.hashedSeedData.getHashedSeed()) {
            return "confirmed against the server's hashed seed";
        }
        return "structure data only - not independently confirmed";
    }

    protected boolean pokePillars() {
        if (this.pillarSeeds != null || this.dataStorage.getPillarData() == null) return false;
        this.pillarSeeds = new ArrayList<>();

        Log.debug("====================================");
        Log.warn("tmachine.lookingForPillarSeed");

        for (int pillarSeed = 0; pillarSeed < 1 << 16 && !this.cancelled.get(); pillarSeed++) {
            if (this.dataStorage.getPillarData().test(pillarSeed)) {
                Log.printSeed("tmachine.foundPillarSeed", pillarSeed);
                this.pillarSeeds.add(pillarSeed);
            }
        }

        if (!this.pillarSeeds.isEmpty()) {
            Log.warn("tmachine.pillarSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }

        return true;
    }

    /**
     * Recovers the structure seed from structure positions alone.
     *
     * @see StructureSeedSolver
     */
    protected boolean pokeLifting() {
        if (!this.structureSeeds.isEmpty()
                || this.dataStorage.getLiftingBits() < net.birb.crackers.cracker.Advisor.LIFTING_TARGET) return false;

        // pokeStructures has always required this and pokeLifting never did, so
        // the lift would start with far too little data to pin down one seed.
        if (this.dataStorage.getBaseBits() < this.dataStorage.getWantedBits()) {
            this.dataStorage.setStatus(DataStorage.Status.NEED_MORE_STRUCTURES);
            return false;
        }

        double bitsNow = this.dataStorage.getBaseBits() + this.dataStorage.getLiftingBits();
        if (this.lastFailedAtBits >= 0 && bitsNow < this.lastFailedAtBits + 8.0D) return false;

        List<Feature.Data<?>> cache = this.dataStorage.solverInput();
        if (cache.isEmpty()) {
            this.dataStorage.setStatus(DataStorage.Status.NEED_MORE_STRUCTURES);
            return false;
        }

        Set<Long> result = SolverSelfTest.fastPathUsable(Features.STRUCTURE_TYPES, SeedCracker.LOGGER::error)
                ? liftFast(cache)
                : liftWithLibrary(cache);

        // A cancelled scan has only covered part of the range, so keeping its
        // result would permanently hide the real seed behind a non-empty set.
        if (result == null || this.cancelled.get()) return false;

        this.structureSeeds = result;

        if (!this.structureSeeds.isEmpty()) {
            this.lastFailedAtBits = -1.0D;
            Log.warn("tmachine.structureSeedSearchFinished");
        } else {
            this.lastFailedAtBits = bitsNow;
            explainFailure(cache);
        }

        return !this.structureSeeds.isEmpty();
    }

    /**
     * Turns "finished search with no results" into something actionable.
     * <p>
     * An empty result is almost never a solver problem; it means the collected
     * structures cannot all come from one world. The diagnostics pass proves
     * that and, usually, names the single structure responsible.
     */
    private void explainFailure(List<Feature.Data<?>> cache) {
        // Boolean.FALSE: the search we just ran already proved it unsolvable,
        // so the diagnostics must not spend time re-deciding that.
        SolverDiagnostics.Report report = SolverDiagnostics.analyse(
                cache, Pools.SOLVER, Pools.solverThreads(), this.cancelled, Boolean.FALSE);
        if (this.cancelled.get()) return;

        this.dataStorage.setStatus(switch (report.verdict()) {
            case WHOLE_TYPE_DISAGREES -> DataStorage.Status.SERVER_PROTECTED;
            case INCONCLUSIVE -> DataStorage.Status.TOO_CLUSTERED;
            default -> DataStorage.Status.NO_RESULT;
        });

        this.dataStorage.setDiagnosis(new DataStorage.Diagnosis(report.summary(), report.details()));

        Log.headline("No seed matches your data");
        Log.detail(report.summary());
        for (String line : report.details()) {
            if (!line.isBlank()) Log.detail(line);
        }
        logger.info("diagnostics: {} - {}", report.verdict(), report.summary());

        // When exactly one structure is provably the odd one out, drop it and
        // let the next find retry. Naming the culprit and leaving it in the
        // data set was a dead end for the player: the only lever they had was
        // /cracker clear, which re-collects the same bad structure the moment
        // its chunk is scanned again.
        if (report.suspects().size() == 1) {
            Feature.Data<?> bad = report.suspects().get(0);
            this.dataStorage.forget(bad);
            this.lastFailedAtBits = -1.0D;
            Log.detail("Ignoring it from now on. Run /cracker clear to undo.");
            // Retry on the next tick rather than waiting for the player to
            // stumble across another structure: the remaining data may well
            // solve now, and sitting on an error screen when the answer is
            // already available is the worst of both.
            this.dataStorage.schedule(net.birb.crackers.cracker.DataAddedEvent.POKE_LIFTING::onDataAdded);
        } else {
            Log.detail("Run /cracker check any time for this report.");
        }
    }

    /** @return the surviving structure seeds, or {@code null} if the data was unusable */
    private Set<Long> liftFast(List<Feature.Data<?>> cache) {
        StructureSeedSolver solver = StructureSeedSolver.compile(cache);
        if (solver == null) {
            this.dataStorage.setStatus(DataStorage.Status.NEED_MORE_STRUCTURES);
            return null;
        }

        long[] lows = solver.sieve(this.cancelled);
        if (lows == null) {
            // Either cancelled, or so many low-bit candidates survived that the
            // structures must be clustered into a handful of regions.
            if (!this.cancelled.get()) {
                this.dataStorage.setStatus(DataStorage.Status.TOO_CLUSTERED);
                this.lastFailedAtBits = this.dataStorage.getBaseBits() + this.dataStorage.getLiftingBits();
            }
            return null;
        }
        if (lows.length == 0) {
            this.dataStorage.setStatus(DataStorage.Status.NO_RESULT);
            return ConcurrentHashMap.newKeySet();
        }

        Log.warn("tmachine.startLifting", solver.constraintCount());
        this.dataStorage.setDiagnosis(null);
        this.dataStorage.setStatus(DataStorage.Status.SOLVING);
        logger.info("lifting {} constraints, sieve {} bits -> {} candidate(s), {} x 2^{} to scan",
                solver.constraintCount(), solver.sieveWidth(), lows.length, lows.length,
                Long.numberOfTrailingZeros(solver.upperRange()));

        long total = lows.length * solver.upperRange();
        ProgressListener progress = new ProgressListener();
        AtomicLong done = new AtomicLong();
        long start = System.nanoTime();

        Set<Long> raw = solver.search(lows, Pools.SOLVER, Pools.solverThreads(), this.cancelled,
                scanned -> progress.setFraction((double) done.addAndGet(scanned) / total));

        if (this.cancelled.get()) return null;

        // Never trust our own arithmetic: re-check every hit with the library.
        // Against the modelled set only - checking against structures the
        // search never constrained would reject perfectly good seeds.
        ChunkRand rand = new ChunkRand();
        Set<Long> verified = ConcurrentHashMap.newKeySet();
        for (long seed : raw) {
            if (StructureSeedSolver.verifyWithLibrary(seed, solver.modelledData(), rand)) verified.add(seed);
        }
        if (!solver.ignoredData().isEmpty()) {
            logger.warn("solver could not model {} collected structure(s); they were not used: {}",
                    solver.ignoredData().size(), solver.ignoredData());
        }
        if (verified.isEmpty() && !raw.isEmpty()) {
            // Our arithmetic and the library disagree. That is our problem, not
            // a fact about the player's world, and it must not be reported as
            // one. Take the raw hits and say so loudly.
            logger.error("library rejected all {} candidate(s) the fast solver found - using them anyway", raw.size());
            Log.problem("Internal check disagreed with the solver - please report this.");
            return raw;
        }
        if (verified.size() != raw.size()) {
            logger.warn("solver produced {} candidate(s) the library rejected", raw.size() - verified.size());
        }
        logger.info("lift finished in {} ms with {} structure seed(s)",
                (System.nanoTime() - start) / 1_000_000L, verified.size());
        return verified;
    }

    /**
     * The pre-1.1.6 lift, kept as a fallback for the case where
     * {@link SolverSelfTest} finds that the fast path no longer agrees with the
     * seedfinding library. Slower, but it only ever calls into the library.
     */
    private Set<Long> liftWithLibrary(List<Feature.Data<?>> cache) {
        Log.warn("tmachine.startLifting", cache.size());
        this.dataStorage.setStatus(DataStorage.Status.SOLVING);

        List<com.seedfinding.mcfeature.structure.UniformStructure.Data<?>> uniform = new ArrayList<>();
        for (Feature.Data<?> datum : cache) {
            if (DataStorage.isLiftable(datum.feature)
                    && datum instanceof com.seedfinding.mcfeature.structure.UniformStructure.Data<?> d) {
                uniform.add(d);
            }
        }
        if (uniform.isEmpty()) return null;

        MCVersion version = Config.get().getVersion();
        long[] lower = java.util.stream.LongStream.range(0, 1L << 19).filter(low -> {
            ChunkRand rand = new ChunkRand();
            for (com.seedfinding.mcfeature.structure.UniformStructure.Data<?> data : uniform) {
                int offset = ((com.seedfinding.mcfeature.structure.UniformStructure<?>) data.feature).getOffset();
                rand.setRegionSeed(low, data.regionX, data.regionZ, data.feature.getSalt(), version);
                if (rand.nextInt(offset) % 4 != data.offsetX % 4
                        || rand.nextInt(offset) % 4 != data.offsetZ % 4) {
                    return false;
                }
            }
            return true;
        }).toArray();

        if (lower.length > net.birb.crackers.cracker.solver.SolverTuning.MAX_SIEVE_SURVIVORS) {
            this.dataStorage.setStatus(DataStorage.Status.TOO_CLUSTERED);
            return null;
        }

        Set<Long> found = ConcurrentHashMap.newKeySet();
        int workers = Pools.solverThreads();
        Feature.Data<?>[] tests = cache.toArray(new Feature.Data<?>[0]);

        for (long low : lower) {
            if (this.cancelled.get()) return null;
            CountDownLatch latch = new CountDownLatch(workers);
            long span = (1L << 29) / workers;
            for (int worker = 0; worker < workers; worker++) {
                long from = worker * span;
                long to = worker == workers - 1 ? 1L << 29 : from + span;
                Pools.SOLVER.execute(() -> {
                    try {
                        ChunkRand rand = new ChunkRand();
                        for (long upper = from; upper < to && !this.cancelled.get(); upper++) {
                            long seed = (upper << 19) | low;
                            for (Feature.Data<?> test : tests) {
                                if (!test.testStart(seed, rand)) return;
                            }
                            found.add(seed);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.cancelled.set(true);
                return null;
            }
        }
        return found;
    }

    /** Recovers structure seeds from a known pillar seed by walking the LCG backwards. */
    protected boolean pokeStructures() {
        if (this.pillarSeeds == null || !this.structureSeeds.isEmpty() ||
                this.dataStorage.getBaseBits() < this.dataStorage.getWantedBits()) return false;

        List<Feature.Data<?>> cache = this.dataStorage.solverInput();
        int workers = Pools.solverThreads();

        for (int pillarSeed : this.pillarSeeds) {
            if (this.cancelled.get()) return false;
            Log.debug("====================================");
            Log.warn("tmachine.lookingForStructureSeeds", pillarSeed);

            ProgressListener progress = new ProgressListener();
            AtomicLong done = new AtomicLong();
            CountDownLatch latch = new CountDownLatch(workers);
            long span = (1L << 32) / workers;
            Feature.Data<?>[] tests = cache.toArray(new Feature.Data<?>[0]);
            int fixedPillar = pillarSeed;

            for (int worker = 0; worker < workers; worker++) {
                long from = worker * span;
                long to = worker == workers - 1 ? 1L << 32 : from + span;
                // Submitted to SOLVER while this thread waits on COORDINATOR.
                // They used to be the same five-thread pool, so a coordinator
                // plus its workers filled it exactly and anything else queued
                // behind them deadlocked.
                Pools.SOLVER.execute(() -> {
                    try {
                        ChunkRand rand = new ChunkRand();
                        for (long partial = from; partial < to && !this.cancelled.get(); partial++) {
                            if ((partial & ((1 << 24) - 1)) == 0) {
                                progress.setFraction((double) done.addAndGet(1 << 24) / (1L << 32));
                            }
                            long seed = timeMachine(partial, fixedPillar);
                            boolean matches = true;
                            for (Feature.Data<?> test : tests) {
                                if (!test.testStart(seed, rand)) {
                                    matches = false;
                                    break;
                                }
                            }
                            if (matches) {
                                this.structureSeeds.add(seed);
                                Log.printSeed("foundStructureSeed", seed);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.cancelled.set(true);
                return false;
            }
            if (this.cancelled.get()) return false;
        }

        if (!this.structureSeeds.isEmpty()) {
            Log.warn("tmachine.structureSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }

        return true;
    }

    protected boolean pokeBiomes() {
        if (this.structureSeeds.isEmpty() || this.worldSeeds.size() == 1) return false;
        if (this.structureSeeds.size() > 1000) {
            this.dataStorage.setStatus(DataStorage.Status.TOO_MANY_CANDIDATES);
            return false;
        }

        Log.debug("====================================");

        this.worldSeeds.clear();
        this.dataStorage.baseSeedData.dump();
        if (Config.get().getVersion().isNewerOrEqualTo(MCVersion.v1_18) && this.dataStorage.getDecoratorBits() > 32F) {
            Log.warn("tmachine.decoratorWorldSeedSearch");
            WorldgenRandom rand = new WorldgenRandom(new XoroshiroRandomSource(0));

            List<DataStorage.Entry<Feature.Data<?>>> snapshot = this.dataStorage.snapshotBaseData();
            for (long structureSeed : this.structureSeeds) {
                for (long upperBits = 0; upperBits < 1 << 16 && !this.cancelled.get(); upperBits++) {
                    long worldSeed = (upperBits << 48) | structureSeed;

                    boolean matches = true;
                    for (DataStorage.Entry<Feature.Data<?>> e : snapshot) {
                        if (e.data.feature instanceof Decorator && !((Decorator.Data<?>) e.data).testStart(worldSeed, rand)) {
                            matches = false;
                            break;
                        }
                    }

                    if (matches) reportWorldSeed(worldSeed);
                }
                if (this.cancelled.get()) return false;
            }
            if (!this.worldSeeds.isEmpty()) {
                Log.warn("tmachine.worldSeedSearchFinished");
                return true;
            }
            Log.warn("finishedSearchNoResult");
        }

        if (this.dataStorage.hashedSeedData != null && this.dataStorage.hashedSeedData.getHashedSeed() != 0) {
            Log.warn("tmachine.hashedSeedWorldSeedSearch");
            for (long structureSeed : this.structureSeeds) {
                WorldSeed.fromHash(structureSeed, this.dataStorage.hashedSeedData.getHashedSeed()).forEach(worldSeed -> {
                    this.worldSeeds.add(worldSeed);
                    Log.printSeed("tmachine.foundWorldSeed", worldSeed);
                });

                if (this.cancelled.get()) return false;
            }

            if (!this.worldSeeds.isEmpty()) {
                Log.warn("tmachine.worldSeedSearchFinished");
                return true;
            }

            // The structures agree on a structure seed, but no world seed both
            // hashes to what the server sent and has that structure seed. The
            // two cannot both be honest, and the structures are self-consistent,
            // so the hashed seed is the one that does not fit.
            this.dataStorage.hashedSeedData = null;
            Log.headline("This server's hashed seed does not match its own terrain");
            Log.detail("Your structures agree with each other, but no world seed");
            Log.detail("produces both them and the hashed seed the server sent.");
            Log.detail("That is a deliberate anti-cracking measure, not a bug.");
            Log.detail("Falling back to world features for the last 16 bits -");
            Log.detail("collect dungeons and emerald ore, or enable biomes.");
        }

        this.dataStorage.biomeSeedData.dump();
        if (this.dataStorage.notEnoughBiomeData()) {
            Log.error("tmachine.moreBiomesNeeded");
            return false;
        }

        Log.warn("tmachine.biomeWorldSeedSearch", this.dataStorage.biomeSeedData.size());
        Log.warn("tmachine.fuzzyBiomeSearch");
        MCVersion version = Config.get().getVersion();
        List<DataStorage.Entry<BiomeData>> biomes = this.dataStorage.snapshotBiomeData();

        for (long structureSeed : this.structureSeeds) {
            for (long worldSeed : StructureSeed.toRandomWorldSeeds(structureSeed)) {
                if (matchesBiomes(new OverworldBiomeSource(version, worldSeed), biomes)) {
                    this.worldSeeds.add(worldSeed);
                    Log.printSeed("tmachine.foundWorldSeed", worldSeed);
                }
                if (this.cancelled.get()) return false;
            }
        }

        if (!this.worldSeeds.isEmpty()) return true;
        if (this.structureSeeds.size() > 10) return false;

        Log.warn("tmachine.deepBiomeSearch");
        for (long structureSeed : this.structureSeeds) {
            for (long upperBits = 0; upperBits < 1 << 16 && !this.cancelled.get(); upperBits++) {
                long worldSeed = (upperBits << 48) | structureSeed;
                if (matchesBiomes(new OverworldBiomeSource(version, worldSeed), biomes)) {
                    reportWorldSeed(worldSeed);
                }
            }
            if (this.cancelled.get()) return false;
        }

        dispSearchEnd();

        if (!this.worldSeeds.isEmpty()) return true;

        Log.error("tmachine.deleteBiomeInformation");
        this.dataStorage.clearBiomeData();

        Log.warn("tmachine.randomSeedSearch");
        for (long structureSeed : this.structureSeeds) {
            StructureSeed.toRandomWorldSeeds(structureSeed).forEach(s ->
                    Log.printSeed("tmachine.foundWorldSeed", s));
        }

        return true;
    }

    private boolean matchesBiomes(OverworldBiomeSource source, List<DataStorage.Entry<BiomeData>> biomes) {
        for (DataStorage.Entry<BiomeData> e : biomes) {
            if (!e.data.test(source)) return false;
        }
        return true;
    }

    /** Adds a world seed, printing the first few and logging the rest. */
    private void reportWorldSeed(long worldSeed) {
        this.worldSeeds.add(worldSeed);
        int size = this.worldSeeds.size();
        if (size < 10) {
            Log.printSeed("tmachine.foundWorldSeed", worldSeed);
            if (size == 9) Log.warn("tmachine.printSeedsInConsole");
        } else {
            logger.info("Found world seed {}", worldSeed);
        }
    }

    protected boolean pokeStructureReduce() {
        if (this.cancelled.get()) return false;
        if (!this.worldSeeds.isEmpty() || this.structureSeeds.size() < 2) return false;
        if (Config.get().getVersion().isOlderThan(MCVersion.v1_13)) return false;

        Set<Long> result = ConcurrentHashMap.newKeySet();
        Log.debug("====================================");
        Log.warn("tmachine.reduceSeeds", this.structureSeeds.size());

        if (this.pillarSeeds != null) {
            this.structureSeeds.forEach(seed -> {
                if (this.pillarSeeds.contains((int) PillarSeed.fromStructureSeed(seed))) {
                    result.add(seed);
                }
            });
        }

        if (result.size() != 1) {
            this.dataStorage.baseSeedData.dump();
            this.dataStorage.dropDataFromOtherVersions();
            List<Feature.Data<?>> cache = this.dataStorage.solverInput();
            ChunkRand rand = new ChunkRand();

            for (Long seed : this.structureSeeds) {
                boolean matches = true;
                for (Feature.Data<?> datum : cache) {
                    if (!datum.testStart(seed, rand)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) result.add(seed);
            }
        }

        if (!result.isEmpty() && this.structureSeeds.size() > result.size()) {
            if (result.size() < 10) {
                result.forEach(seed -> Log.printSeed("foundStructureSeed", seed));
            } else {
                Log.warn("tmachine.succeedReducing", result.size());
            }

            this.structureSeeds = result;
            return true;
        }
        Log.warn("tmachine.failedReducing");
        return false;
    }

    private void dispSearchEnd() {
        if (!this.worldSeeds.isEmpty()) {
            Log.warn("tmachine.worldSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }
    }

    public long timeMachine(long partialWorldSeed, int pillarSeed) {
        long currentSeed = 0L;
        currentSeed |= (partialWorldSeed & 0xFFFF0000L) << 16;
        currentSeed |= (long) pillarSeed << 16;
        currentSeed |= partialWorldSeed & 0xFFFFL;

        currentSeed = this.inverseLCG.nextSeed(currentSeed);
        currentSeed ^= LCG.JAVA.multiplier;
        return currentSeed;
    }

    public enum Phase {
        BIOMES(null), STRUCTURE_REDUCE(BIOMES), STRUCTURES(BIOMES), LIFTING(STRUCTURE_REDUCE), PILLARS(STRUCTURES);

        private final Phase nextPhase;

        Phase(Phase nextPhase) {
            this.nextPhase = nextPhase;
        }

        public Phase nextPhase() {
            return this.nextPhase;
        }
    }

}
