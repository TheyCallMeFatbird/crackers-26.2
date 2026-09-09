package net.birb.crackers.cracker.solver;

import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.BuriedTreasure;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.TriangularStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;

/**
 * One observed structure, compiled into primitives.
 * <p>
 * This is a hand-inlined equivalent of the seedfinding library's
 * {@code Feature.Data#testStart}. The library version goes through a virtual
 * {@code canStart}, re-derives the region seed from
 * {@code regionX * A + regionZ * B + salt} on every call, and drives a
 * {@code ChunkRand} object. In a loop that runs 2^28 times per candidate that
 * overhead is the whole cost, so here the region seed is precomputed, the LCG
 * is inlined, and the integer division inside {@code nextInt} is replaced by a
 * multiply-high.
 * <p>
 * Everything in here is exactly equivalent to the library, including the
 * rejection-sampling retry inside {@code nextInt} that almost never fires.
 * {@link SolverSelfTest} proves that against the library itself at startup, and
 * every hit is re-verified with the library before it is accepted, so this file
 * can only ever cost speed, never correctness.
 */
public final class StructureConstraint {

    /** The constants behind {@code java.util.Random}. */
    static final long MULTIPLIER = 0x5DEECE66DL;
    static final long ADDEND = 0xBL;
    static final long MASK_48 = (1L << 48) - 1;

    /** Two {@code nextInt(offset)} draws that must equal the observed offsets. */
    public static final int UNIFORM = 0;
    /** Four draws, averaged in pairs. */
    public static final int TRIANGULAR = 1;
    /** A single {@code nextFloat()} below a fixed chance. */
    public static final int TREASURE = 2;

    public final Feature.Data<?> source;
    final int kind;
    final long base;
    final int bound;
    final long magic;
    final int offsetX;
    final int offsetZ;
    final float chance;

    /**
     * How many low bits of the {@code nextInt} result this structure pins down,
     * or 0 if it cannot contribute to the sieve.
     * <p>
     * {@code nextInt(bound)} returns {@code bits % bound}. When {@code 2^t}
     * divides {@code bound}, {@code bits % bound} is congruent to {@code bits}
     * modulo {@code 2^t}, so the bottom {@code t} bits of the result are just
     * bits 17..17+t-1 of the RNG state - and those depend only on the bottom
     * {@code 17+t} bits of the seed. That is the entire basis of the sieve.
     */
    final int sieveShift;
    final int sieveX;
    final int sieveZ;

    /** RNG draws this test costs, and how often it lets a wrong seed through. */
    final int cost;
    final double passRate;

    private StructureConstraint(Feature.Data<?> source, int kind, long base, int bound,
                                int offsetX, int offsetZ, float chance,
                                int sieveShift, int cost, double passRate) {
        this.source = source;
        this.kind = kind;
        this.base = base;
        this.bound = bound;
        this.magic = bound > 0 ? Long.divideUnsigned(-1L, bound) + 1 : 0L;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        this.chance = chance;
        this.sieveShift = sieveShift;
        int mask = (1 << sieveShift) - 1;
        this.sieveX = offsetX & mask;
        this.sieveZ = offsetZ & mask;
        this.cost = cost;
        this.passRate = passRate;
    }

    /** A feature's name, never null - the library returns null for classes it did not define. */
    public static String nameOf(Feature<?, ?> feature) {
        if (feature == null) return "unknown";
        String name = feature.getName();
        return name != null ? name : feature.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Compiles one collected data point, or returns {@code null} if this
     * structure type is not one the fast path models - pillager outposts, whose
     * {@code canStart} also does a weak-seed draw and a nearby-village search,
     * are the only such type and were already excluded from the old solver too.
     */
    public static StructureConstraint of(Feature.Data<?> data) {
        if (!(data instanceof RegionStructure.Data<?> d)) return null;

        // Modelled as a plain uniform draw, which an outpost is not: its
        // canStart also consults a weak seed, a nextInt(5) and nearby villages.
        // Accepting one here would make the fast path admit seeds the library
        // then rejects, and the player would be told their data contradicts
        // itself. Callers already exclude outposts; this makes it impossible.
        if (d.feature instanceof com.seedfinding.mcfeature.structure.PillagerOutpost) return null;

        if (d.feature instanceof UniformStructure<?> uniform) {
            int bound = uniform.getOffset();
            if (bound <= 0) return null;
            // Only the powers of two that divide the bound survive the modulo.
            int shift = Math.min(Integer.numberOfTrailingZeros(bound), SolverTuning.MAX_SIEVE_SHIFT);
            return new StructureConstraint(d, UNIFORM, d.baseRegionSeed, bound,
                    d.offsetX, d.offsetZ, 0.0F, shift, 2, 1.0D / ((double) bound * bound));
        }

        if (d.feature instanceof TriangularStructure<?> triangular) {
            int peak = triangular.getPeak();
            if (peak <= 0) return null;
            // (a + b) / 2 destroys the congruence, so triangular structures
            // verify but never sieve.
            return new StructureConstraint(d, TRIANGULAR, d.baseRegionSeed, peak,
                    d.offsetX, d.offsetZ, 0.0F, 0, 4, 4.0D / ((double) peak * peak));
        }

        if (d.feature instanceof BuriedTreasure treasure) {
            float chance = treasure.getChance();
            return new StructureConstraint(d, TREASURE, d.baseRegionSeed, 0,
                    0, 0, chance, 0, 1, chance);
        }

        return null;
    }

    /** Exact test, equivalent to {@code source.testStart(structureSeed, rand)}. */
    public boolean test(long structureSeed) {
        long state = (this.base + structureSeed) ^ MULTIPLIER;

        switch (this.kind) {
            case UNIFORM: {
                long r = draw(state);
                if ((int) (r >>> 48) != this.offsetX) return false;
                r = draw(r & MASK_48);
                return (int) (r >>> 48) == this.offsetZ;
            }
            case TRIANGULAR: {
                long r = draw(state);
                int a = (int) (r >>> 48);
                r = draw(r & MASK_48);
                int b = (int) (r >>> 48);
                if ((a + b) / 2 != this.offsetX) return false;
                r = draw(r & MASK_48);
                a = (int) (r >>> 48);
                r = draw(r & MASK_48);
                b = (int) (r >>> 48);
                return (a + b) / 2 == this.offsetZ;
            }
            default: {
                long next = (state * MULTIPLIER + ADDEND) & MASK_48;
                return (int) (next >>> 24) / ((float) (1 << 24)) < this.chance;
            }
        }
    }

    /**
     * One {@code nextInt(bound)}, returning {@code (value << 48) | newState}.
     * <p>
     * The loop is the library rejection sampling. It fires for at most
     * {@code bound} of the 2^31 possible draws, but it is here because "almost
     * never" is not "never" and this test gates the whole search.
     */
    private long draw(long state) {
        int bits;
        int value;
        do {
            state = (state * MULTIPLIER + ADDEND) & MASK_48;
            bits = (int) (state >>> 17);
            value = bits - (int) Math.multiplyHigh(bits, this.magic) * this.bound;
        } while (bits - value + (this.bound - 1) < 0);
        return ((long) value << 48) | state;
    }

    /**
     * Sieve test against a candidate for the low {@code 17 + sieveShift} bits.
     * <p>
     * Carries only travel upward, so the low bits of {@code base + seed} are
     * fixed by the low bits of {@code seed}; xor is bitwise; and multiply-add
     * modulo 2^48 preserves "low k bits determine low k bits". The retry branch
     * is deliberately absent - it cannot be evaluated from low bits alone,
     * which is the one and only approximation in this solver. See
     * {@link SolverTuning#SIEVE_MISS_RATE}.
     */
    boolean sieve(long low) {
        int mask = (1 << this.sieveShift) - 1;
        long state = ((this.base + low) ^ MULTIPLIER) & MASK_48;
        state = (state * MULTIPLIER + ADDEND) & MASK_48;
        if ((((int) (state >>> 17)) & mask) != this.sieveX) return false;
        state = (state * MULTIPLIER + ADDEND) & MASK_48;
        return (((int) (state >>> 17)) & mask) == this.sieveZ;
    }

    @Override
    public String toString() {
        return nameOf(this.source.feature) + "@" + this.source.chunkX + "," + this.source.chunkZ;
    }
}
