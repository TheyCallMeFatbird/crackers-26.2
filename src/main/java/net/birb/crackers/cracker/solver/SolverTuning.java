package net.birb.crackers.cracker.solver;

/** Numbers the solver is tuned by, in one place so they can be argued with. */
public final class SolverTuning {

    private SolverTuning() {
    }

    /**
     * Cap on how many low bits of a {@code nextInt} result the sieve will use.
     * <p>
     * The sieve enumerates {@code 2^(17 + shift)} candidates, so every extra
     * bit doubles its cost but halves the far more expensive upper scan. In
     * practice the bound is 24 for temples, igloos and witch huts (shift 3),
     * 20 for shipwrecks (shift 2) and 12 for trial chambers (shift 2), so 4 is
     * headroom rather than a real limit.
     */
    public static final int MAX_SIEVE_SHIFT = 4;

    /**
     * How many low-bit candidates may survive before the data set is declared
     * too clustered to be worth searching.
     * <p>
     * Each survivor costs a full upper scan, so this is the knob that decides
     * "a few seconds" versus "give up and tell the player". Honest data leaves
     * one to a handful; hundreds means several structures share a region and
     * are re-stating the same constraint.
     */
    public static final int MAX_SIEVE_SURVIVORS = 256;

    /**
     * Chance that the sieve wrongly discards the real seed, per structure.
     * <p>
     * {@code nextInt} rejects and redraws when its raw 31-bit sample lands in
     * the top {@code bound} values, which the sieve cannot detect from low bits
     * alone. That is roughly {@code 24 / 2^31} per draw, so about one in nine
     * million solves over a ten-draw data set. Every practical structure-seed
     * cracker accepts this, the previous implementation included; it is
     * recorded here so nobody has to rediscover it.
     */
    public static final double SIEVE_MISS_RATE = 1.1e-7;

    /** Seeds a worker scans between checks of the cancellation flag. */
    public static final int CANCEL_CHECK_INTERVAL = 1 << 20;
}
