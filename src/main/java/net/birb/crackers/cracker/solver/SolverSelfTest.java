package net.birb.crackers.cracker.solver;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Proves the hand-inlined solver agrees with the seedfinding library.
 * <p>
 * {@link StructureConstraint} reimplements {@code canStart} with an inlined
 * LCG, a multiply-high in place of the integer division, and a low-bit sieve
 * derived on paper. All three are the kind of thing that is right until it
 * quietly is not, and a solver that is quietly wrong is worse than a slow one -
 * it would report "no result" forever on a perfectly good world.
 * <p>
 * So on first use every structure type is round-tripped: place it with the
 * library, compile it, then compare the fast test against the library test on
 * the true seed and on a pile of wrong ones. If anything disagrees the fast
 * path is switched off for the session and the library path is used instead.
 * <p>
 * This class deliberately depends on nothing but the seedfinding libraries, so
 * it can be run outside Minecraft.
 */
public final class SolverSelfTest {

    private static volatile Boolean cached;

    private SolverSelfTest() {
    }

    /** Runs the verification once per session and caches the verdict. */
    public static boolean fastPathUsable(List<RegionStructure<?, ?>> structures, Consumer<String> onProblem) {
        Boolean known = cached;
        if (known != null) return known;

        synchronized (SolverSelfTest.class) {
            if (cached != null) return cached;
            String problem;
            try {
                problem = verify(structures);
            } catch (Throwable t) {
                problem = "self-test threw: " + t;
            }
            if (problem != null) {
                onProblem.accept("Crackers fast solver disabled, falling back to the library path: " + problem);
            }
            cached = problem == null;
            return cached;
        }
    }

    /**
     * @return a description of the first disagreement found, or {@code null} if
     *         the fast path matched the library everywhere it was checked
     */
    public static String verify(List<RegionStructure<?, ?>> structures) {
        Random random = new Random(0xC0FFEEL);
        ChunkRand rand = new ChunkRand();
        int checked = 0;

        for (RegionStructure<?, ?> structure : structures) {
            if (structure == null) continue;

            for (int trial = 0; trial < 64; trial++) {
                long structureSeed = random.nextLong() & StructureConstraint.MASK_48;
                int regionX = random.nextInt(2048) - 1024;
                int regionZ = random.nextInt(2048) - 1024;

                CPos position;
                try {
                    position = structure.getInRegion(structureSeed, regionX, regionZ, rand);
                } catch (Throwable t) {
                    break; // structure type this build cannot place
                }
                if (position == null) continue;

                Feature.Data<?> data = structure.at(position.getX(), position.getZ());
                StructureConstraint constraint = StructureConstraint.of(data);
                if (constraint == null) break; // not modelled by the fast path

                if (!data.testStart(structureSeed, rand)) continue;

                if (!constraint.test(structureSeed)) {
                    return "false negative on " + structure.getName() + " seed " + structureSeed;
                }
                if (constraint.sieveShift > 0
                        && !constraint.sieve(structureSeed & ((1L << (17 + constraint.sieveShift)) - 1))) {
                    return "sieve rejected the true seed on " + structure.getName() + " seed " + structureSeed;
                }

                for (int wrong = 0; wrong < 256; wrong++) {
                    long other = random.nextLong() & StructureConstraint.MASK_48;
                    if (data.testStart(other, rand) != constraint.test(other)) {
                        return "disagreement on " + structure.getName() + " seed " + other;
                    }
                }
                checked++;
            }
        }

        return checked == 0 ? "no placeable structures to verify against" : null;
    }
}
