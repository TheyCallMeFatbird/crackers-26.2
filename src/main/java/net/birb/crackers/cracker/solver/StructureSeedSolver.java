package net.birb.crackers.cracker.solver;

import com.seedfinding.mcfeature.Feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Recovers the 48-bit structure seed from observed structure positions.
 *
 * <h2>How it works</h2>
 * A structure at a known chunk fixes two {@code nextInt(bound)} results of a
 * {@code java.util.Random} seeded with {@code baseRegionSeed + structureSeed},
 * where {@code baseRegionSeed} is known. So every structure is a constraint on
 * one unknown 48-bit number, and the job is to find the number satisfying all
 * of them.
 *
 * <h3>Stage 1 - adaptive-radix congruence sieve</h3>
 * {@code nextInt(bound)} returns {@code bits % bound} where {@code bits} is
 * bits 17..47 of the RNG state. When {@code 2^t} divides {@code bound}, the
 * bottom {@code t} bits of the result are literally bits 17..17+t-1 of the
 * state, and those depend only on the bottom {@code 17+t} bits of the seed.
 * So enumerating the bottom {@code 17+T} bits of the structure seed, where
 * {@code T} is the deepest any collected structure supports, tests every
 * structure at once for a few million cheap operations.
 * <p>
 * The old solver hardcoded {@code T = 2} ("% 4", 19 bits) because that is the
 * deepest <i>every</i> structure type supports. Choosing {@code T} per data set
 * and letting each structure contribute at its own depth reaches {@code T = 3}
 * whenever a temple, igloo or witch hut is present, which is the common case -
 * and that halves stage 2.
 *
 * <h3>Stage 2 - incremental upper scan</h3>
 * With the bottom {@code 17+T} bits fixed, the remaining {@code 31-T} bits are
 * scanned. Written naively that is a multiply-add per seed per structure. But
 * consecutive candidates differ by exactly {@code 2^(17+T)} in the seed, and
 * the LCG is affine, so consecutive RNG states differ by the <i>constant</i>
 * {@code multiplier * 2^(17+T)}. Iterating in the driver structure's own xor
 * coordinates turns the whole inner loop into one add, one shift, one
 * multiply-high and a compare, with no division and no 64-bit multiply.
 * <p>
 * Only the single most selective structure drives that loop. The rest are
 * checked by {@link StructureConstraint#test} on the roughly one-in-{@code
 * bound} candidates that survive, which costs nothing in aggregate.
 *
 * <h3>Stage 3 - verification</h3>
 * Every surviving seed is re-checked with the seedfinding library itself
 * before it is returned, so no amount of arithmetic trickery above can produce
 * a wrong answer. It can only ever be slower or, at the documented
 * {@link SolverTuning#SIEVE_MISS_RATE}, miss.
 */
public final class StructureSeedSolver {

    private static final long MASK_48 = StructureConstraint.MASK_48;
    private static final long MULTIPLIER = StructureConstraint.MULTIPLIER;
    private static final long ADDEND = StructureConstraint.ADDEND;

    private final StructureConstraint[] constraints;
    private final StructureConstraint[] sieveable;
    private final StructureConstraint driver;
    private final int sieveShift;
    private final List<Feature.Data<?>> modelled;
    private final List<Feature.Data<?>> ignored;

    private StructureSeedSolver(StructureConstraint[] constraints,
                                StructureConstraint[] sieveable,
                                StructureConstraint driver,
                                int sieveShift,
                                List<Feature.Data<?>> modelled,
                                List<Feature.Data<?>> ignored) {
        this.constraints = constraints;
        this.sieveable = sieveable;
        this.driver = driver;
        this.sieveShift = sieveShift;
        this.modelled = modelled;
        this.ignored = ignored;
    }

    /**
     * Exactly the data this solver constrained.
     * <p>
     * Verification must run against this and nothing else. Checking a candidate
     * against data the search never considered turns every ignored structure
     * into an invisible extra condition that can reject every hit - which
     * surfaces to the player as "your data contradicts itself" when in fact the
     * solver simply never looked at that structure.
     */
    public List<Feature.Data<?>> modelledData() {
        return this.modelled;
    }

    /** Data the fast path cannot model, and therefore did not constrain. */
    public List<Feature.Data<?>> ignoredData() {
        return this.ignored;
    }

    /**
     * Compiles a data set. Returns {@code null} when nothing in it can drive a
     * search, which is the caller's cue to keep collecting.
     */
    public static StructureSeedSolver compile(List<Feature.Data<?>> data) {
        List<StructureConstraint> compiled = new ArrayList<>(data.size());
        List<Feature.Data<?>> modelled = new ArrayList<>(data.size());
        List<Feature.Data<?>> ignored = new ArrayList<>();
        for (Feature.Data<?> datum : data) {
            StructureConstraint constraint = StructureConstraint.of(datum);
            if (constraint != null) {
                compiled.add(constraint);
                modelled.add(datum);
            } else {
                ignored.add(datum);
            }
        }
        if (compiled.isEmpty()) return null;

        // Cheapest test first: with every pass rate far below one, the expected
        // cost of the chain is dominated by whatever runs on every candidate.
        compiled.sort(Comparator.<StructureConstraint>comparingInt(c -> c.cost)
                .thenComparingDouble(c -> c.passRate));

        List<StructureConstraint> sieveable = new ArrayList<>();
        int shift = 0;
        for (StructureConstraint constraint : compiled) {
            if (constraint.sieveShift > 0) {
                sieveable.add(constraint);
                shift = Math.max(shift, constraint.sieveShift);
            }
        }
        if (shift == 0) return null;

        // The driver runs on every single candidate, so pick the one with the
        // most selectivity LEFT once the sieve has had its share.
        //
        // This is easy to get wrong. The obvious choice is the largest bound,
        // but the sieve has already pinned that structure's result modulo
        // 2^sieveShift, so only bound >> sieveShift residues remain reachable.
        // A desert temple (bound 24, shift 3) therefore rejects only two
        // candidates in three, while a shipwreck (bound 20, shift 2) rejects
        // four in five despite the smaller bound.
        StructureConstraint driver = null;
        for (StructureConstraint constraint : compiled) {
            if (constraint.kind != StructureConstraint.UNIFORM) continue;
            if (driver == null || residual(constraint) > residual(driver)) driver = constraint;
        }
        if (driver == null) return null;

        return new StructureSeedSolver(compiled.toArray(new StructureConstraint[0]),
                sieveable.toArray(new StructureConstraint[0]), driver, shift,
                List.copyOf(modelled), List.copyOf(ignored));
    }

    /** Distinct results the sieve still leaves open for one draw of this structure. */
    private static int residual(StructureConstraint constraint) {
        return constraint.bound >> constraint.sieveShift;
    }

    /** Bits of the structure seed the sieve pins down. */
    public int sieveWidth() {
        return 17 + this.sieveShift;
    }

    /** Number of seeds the upper scan visits per surviving candidate. */
    public long upperRange() {
        return 1L << (48 - sieveWidth());
    }

    public int constraintCount() {
        return this.constraints.length;
    }

    /**
     * Stage 1. Returns the low-bit candidates, or {@code null} if there were so
     * many that the data set is not worth scanning.
     */
    public long[] sieve(AtomicBoolean cancelled) {
        long limit = 1L << sieveWidth();
        long[] survivors = new long[SolverTuning.MAX_SIEVE_SURVIVORS + 1];
        int found = 0;

        for (long low = 0; low < limit; low++) {
            if ((low & (SolverTuning.CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) return null;

            boolean ok = true;
            for (StructureConstraint constraint : this.sieveable) {
                if (!constraint.sieve(low)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;

            if (found == survivors.length) return null;
            survivors[found++] = low;
        }

        if (found > SolverTuning.MAX_SIEVE_SURVIVORS) return null;
        long[] exact = new long[found];
        System.arraycopy(survivors, 0, exact, 0, found);
        return exact;
    }

    /**
     * Stage 2 and 3. Scans the upper bits of every candidate in parallel and
     * returns the structure seeds that satisfy every constraint.
     *
     * @param onProgress called with a count of scanned seeds, from worker
     *                   threads, so the UI can show something moving
     */
    public Set<Long> search(long[] lowCandidates, ExecutorService pool, int workers,
                            AtomicBoolean cancelled, LongConsumer onProgress) {
        Set<Long> found = ConcurrentHashMap.newKeySet();
        long range = upperRange();

        for (long low : lowCandidates) {
            if (cancelled.get()) return found;
            scanOne(low, range, pool, workers, cancelled, found, onProgress);
        }
        return found;
    }

    private void scanOne(long low, long range, ExecutorService pool, int workers,
                         AtomicBoolean cancelled, Set<Long> found, LongConsumer onProgress) {
        int shift = sieveWidth();
        long delta = (MULTIPLIER << shift) & MASK_48;
        long highMask = range - 1;

        // state0 for a candidate is (driverBase + seed) ^ multiplier. Its low
        // bits are constant across the scan, and its high bits run over every
        // value exactly once - so iterate those directly and recover the seed.
        long b = (this.driver.base + low) & MASK_48;
        long lowState = (b & ((1L << shift) - 1)) ^ (MULTIPLIER & ((1L << shift) - 1));
        long baseHigh = (b >>> shift) & highMask;
        long multHigh = (MULTIPLIER >>> shift) & highMask;
        long firstState = ((lowState * MULTIPLIER) + ADDEND) & MASK_48;
        // The state of the second draw advances by a constant too, so both
        // draws come out of the loop for the price of two additions.
        long secondState = ((firstState * MULTIPLIER) + ADDEND) & MASK_48;
        long delta2 = (delta * MULTIPLIER) & MASK_48;

        AtomicLong cursor = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(workers);
        // Blocks double as the cancellation granularity now that the inner
        // loop has no flag check of its own: a few million seeds is a handful of
        // milliseconds, which is a fine reaction time for "leave the world".
        long block = Math.max(1L << 16, range / (workers * 16L));

        for (int worker = 0; worker < workers; worker++) {
            pool.execute(() -> {
                try {
                    for (; ; ) {
                        long start = cursor.getAndAdd(block);
                        if (start >= range || cancelled.get()) return;
                        long end = Math.min(start + block, range);
                        scanBlock(low, start, end, shift, delta, delta2, highMask, baseHigh, multHigh,
                                firstState, secondState, found);
                        onProgress.accept(end - start);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    cancelled.set(true);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
        }

        Throwable thrown = failure.get();
        if (thrown != null) throw new IllegalStateException("solver worker failed", thrown);
    }

    /**
     * The hot loop: two additions, a shift, a multiply-high, a multiply and a
     * compare per candidate seed, with no division and no 64-bit multiply on
     * the common path.
     * <p>
     * Both of the driver's draws are checked here rather than only the first.
     * That matters more than it looks: the sieve has already fixed the low
     * {@code sieveShift} bits of every draw, so a single draw only rejects
     * {@code 1 - 2^shift/bound} of candidates - one in three for a temple.
     * Checking the pair takes the survivor rate to about one in twenty-five,
     * which is what keeps the expensive full chain off the hot path.
     */
    private void scanBlock(long low, long from, long to, int shift, long delta, long delta2,
                           long highMask, long baseHigh, long multHigh,
                           long firstState, long secondState,
                           Set<Long> found) {
        final int bound = this.driver.bound;
        final long magic = this.driver.magic;
        final int wantX = this.driver.offsetX;
        final int wantZ = this.driver.offsetZ;
        final int limit = bound - 1;

        long state1 = (firstState + from * delta) & MASK_48;
        long state2 = (secondState + from * delta2) & MASK_48;

        for (long u = from; u < to; u++) {
            int bits = (int) (state1 >>> 17);
            int value = bits - (int) Math.multiplyHigh(bits, magic) * bound;

            // Evaluating the second draw only when the first one matches beats
            // evaluating both unconditionally, measurably (171M vs 101M seeds
            // per second per thread). The branch is taken one time in five and
            // does mispredict, but skipping four fifths of the second draw
            // still wins.
            if (value == wantX) {
                int bits2 = (int) (state2 >>> 17);
                int value2 = bits2 - (int) Math.multiplyHigh(bits2, magic) * bound;
                if (value2 == wantZ || bits2 - value2 + limit < 0 || bits - value + limit < 0) {
                    check(low, u, shift, highMask, baseHigh, multHigh, found);
                }
            } else if (bits - value + limit < 0) {
                // Library rejection sampling fired, so the cached second state
                // is not the one the library would have used. Astronomically
                // rare; hand it to the exact test rather than reasoning about it.
                check(low, u, shift, highMask, baseHigh, multHigh, found);
            }

            state1 = (state1 + delta) & MASK_48;
            state2 = (state2 + delta2) & MASK_48;
        }
    }

    /** Off the hot path: rebuild the seed and run the full constraint chain. */
    private void check(long low, long u, int shift, long highMask,
                       long baseHigh, long multHigh, Set<Long> found) {
        long high = ((u ^ multHigh) - baseHigh) & highMask;
        long seed = low | (high << shift);
        if (matches(seed)) found.add(seed);
    }

    /** Full constraint chain, cheapest test first. */
    private boolean matches(long seed) {
        for (StructureConstraint constraint : this.constraints) {
            if (!constraint.test(seed)) return false;
        }
        return true;
    }

    /**
     * Stage 3, exposed so callers verify with the library rather than trusting
     * this class. Uses the seedfinding implementation, not the inlined one.
     */
    public static boolean verifyWithLibrary(long seed, List<Feature.Data<?>> data,
                                            com.seedfinding.mccore.rand.ChunkRand rand) {
        for (Feature.Data<?> datum : data) {
            if (!datum.testStart(seed, rand)) return false;
        }
        return true;
    }
}
