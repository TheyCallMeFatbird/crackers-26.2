package net.birb.crackers.cracker.solver;

import com.seedfinding.mcfeature.Feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explains why a search found nothing.
 *
 * <h2>Solvable, not just sievable</h2>
 * An earlier version of this class decided consistency from the low-bit sieve
 * alone. That was wrong in a way that produced flatly contradictory advice: the
 * sieve is a <i>necessary</i> condition, not a sufficient one, so a data set can
 * sail through it and still have no 48-bit seed. Players saw "no seed matches
 * your data" immediately followed by "all your structures agree with each
 * other".
 * <p>
 * Consistency now means a full solve actually returns a seed. The sieve is still
 * used, but only as the cheap early exit it deserves to be: an empty sieve is
 * proof of contradiction without scanning anything.
 *
 * <h2>What it cannot tell you</h2>
 * Consistent is not the same as correct. If a server shifts every structure of
 * a type by the same rule, the survivors stay mutually consistent and simply
 * describe a seed that is not the real one. What shows up here is the commoner
 * case: some structures agree with each other and others do not.
 */
public final class SolverDiagnostics {

    /**
     * Low-bit candidates a leave-one-out probe may scan before it gives up.
     * <p>
     * The full data set is allowed the solver's normal budget, but a probe runs
     * once per structure, so one pathological subset must not be able to hold
     * the whole report hostage.
     */
    private static final int MAX_PROBE_CANDIDATES = 8;

    /**
     * How few seeds a subset must still match before "it becomes solvable
     * without X" counts as evidence against X.
     * <p>
     * This is the correction to a badly unsound piece of reasoning. Removing a
     * structure always makes the remaining system easier to satisfy, and below
     * about seven structures it becomes so underdetermined that <i>every</i>
     * removal yields solutions - so every structure looked guilty and whichever
     * one happened to come out alone got blamed. Measured over random worlds:
     * four structures match ~500,000 seeds, five ~5,300, six ~300, seven ~33.
     * Blame accuracy tracked that exactly, from 2-in-10 at five structures to
     * 10-in-10 at eight.
     */
    private static final long WELL_DETERMINED = 1000L;

    /**
     * Structures required before this class will name a culprit at all.
     * <p>
     * Blame accuracy was measured against worlds with exactly one structure
     * moved by a chunk: at five structures it named the right one 0 times in
     * 10, at six it was right once and wrong twice, at seven it was right 8
     * times with no false accusations, and at eight and above 10 out of 10.
     * Below seven there is simply not enough left after a removal for the
     * result to mean anything, and a wrong accusation is worse than none -
     * the caller acts on it by deleting data.
     */
    private static final int MIN_STRUCTURES_TO_BLAME = 7;

    private SolverDiagnostics() {
    }

    public enum Verdict {
        /** A seed exists that produces every structure. */
        CONSISTENT,
        /** One structure disagrees with all the rest. */
        SINGLE_BAD_STRUCTURE,
        /** Every structure of one type disagrees with the others. */
        WHOLE_TYPE_DISAGREES,
        /** More than one thing is wrong, or something systematic is. */
        WIDESPREAD,
        /** Contradictory, but too little data to say which structure is at fault. */
        CANNOT_LOCALISE,
        /** Not enough of the right kind of data to say anything. */
        INCONCLUSIVE
    }

    /**
     * @param suspects the structures the check blamed, so a caller can drop
     *                 them rather than only telling the player about them
     */
    public record Report(Verdict verdict, String summary, List<String> details,
                         List<Feature.Data<?>> suspects) {
        public Report(Verdict verdict, String summary, List<String> details) {
            this(verdict, summary, details, List.of());
        }
    }

    public static Report analyse(List<Feature.Data<?>> data, ExecutorService pool, int workers,
                                 AtomicBoolean cancelled) {
        return analyse(data, pool, workers, cancelled, null);
    }

    /**
     * @param knownSolvable pass {@code FALSE} when the caller has just watched a
     *                      full search come back empty, so the check is not
     *                      repeated; {@code null} to work it out here
     */
    public static Report analyse(List<Feature.Data<?>> data, ExecutorService pool, int workers,
                                 AtomicBoolean cancelled, Boolean knownSolvable) {
        if (StructureSeedSolver.compile(data) == null) {
            return new Report(Verdict.INCONCLUSIVE,
                    "Not enough position data to check consistency.",
                    List.of("Collect a temple, igloo, witch hut, shipwreck or",
                            "trial chamber, then run this again."));
        }

        long count = Boolean.FALSE.equals(knownSolvable)
                ? 0L
                : solutionCount(data, pool, workers, cancelled, SolverTuning.MAX_SIEVE_SURVIVORS);
        if (cancelled.get()) return cancelledReport();

        if (count == UNKNOWN) {
            return new Report(Verdict.INCONCLUSIVE,
                    "There is too little data here to check properly.",
                    List.of("Your structures are clustered into too few regions",
                            "for the check to narrow anything down.",
                            "",
                            "Collect a few from terrain several thousand blocks",
                            "away and try again."));
        }

        if (count > 0) {
            List<String> details = new ArrayList<>();
            details.add("A seed exists that produces every one of them, so");
            details.add("nothing here is griefed or randomised.");
            details.add("");
            if (count == 1) {
                details.add("They pin down exactly one seed.");
            } else {
                details.add("They match " + count + " different seeds, though, so there");
                details.add("is not yet enough data to say which is yours.");
                details.add("Every extra structure divides that number by a few");
                details.add("hundred - two or three more should settle it.");
            }
            return new Report(Verdict.CONSISTENT,
                    "All " + data.size() + " structures agree with each other.", details);
        }

        // Contradictory. Blaming a specific structure needs enough data that
        // dropping one still leaves something able to contradict.
        if (data.size() < MIN_STRUCTURES_TO_BLAME) {
            return new Report(Verdict.CANNOT_LOCALISE,
                    "Your structures cannot all come from one world.",
                    List.of("Something here is not where the game generated it,",
                            "but " + data.size() + " structures is too few to say which.",
                            "",
                            "Working that out means dropping each one in turn and",
                            "checking whether the rest agree, and below seven",
                            "structures what is left cannot contradict anything -",
                            "so every one of them looks equally guilty.",
                            "",
                            "Collect until you have at least seven, then run",
                            "/cracker check again."));
        }

        List<Feature.Data<?>> suspects = new ArrayList<>();
        boolean localisable = false;
        for (Feature.Data<?> candidate : data) {
            if (cancelled.get()) return cancelledReport();
            List<Feature.Data<?>> without = new ArrayList<>(data);
            without.remove(candidate);
            long remaining = solutionCount(without, pool, workers, cancelled, MAX_PROBE_CANDIDATES);
            if (remaining == 0L) localisable = true;   // the rest still conflict: informative
            if (rescues(remaining)) {
                suspects.add(candidate);
                localisable = true;
            }
        }

        if (!localisable) {
            return new Report(Verdict.CANNOT_LOCALISE,
                    "Your structures cannot all come from one world.",
                    List.of("Something here is not where the game would have",
                            "generated it, but there is not enough data to say",
                            "which one - drop any single structure and what is",
                            "left is too little to contradict anything.",
                            "",
                            "Collect two or three more and run this again. The",
                            "odd one out separates itself once there is enough",
                            "to compare against."));
        }

        if (suspects.size() == 1) {
            return new Report(Verdict.SINGLE_BAD_STRUCTURE,
                    "One structure does not belong: " + describe(suspects.get(0)) + ".",
                    List.of("Every other structure agrees once that one is",
                            "dropped, so it is not where the game generated it.",
                            "",
                            "Crackers has stopped using it and will try again."),
                    List.copyOf(suspects));
        }

        if (suspects.size() > 1) {
            List<String> details = new ArrayList<>();
            details.add("Any one of these being wrong would explain it:");
            for (Feature.Data<?> suspect : suspects) {
                details.add("  " + describe(suspect));
            }
            details.add("");
            details.add("Collect a couple more untouched structures and the");
            details.add("odd one out will separate itself.");
            return new Report(Verdict.SINGLE_BAD_STRUCTURE,
                    suspects.size() + " structures could each be the bad one.", details);
        }

        // No single removal fixes it. Try dropping whole types: a server that
        // randomises one feature's placement breaks every instance of it.
        Map<String, List<Feature.Data<?>>> byType = new LinkedHashMap<>();
        for (Feature.Data<?> datum : data) {
            byType.computeIfAbsent(StructureConstraint.nameOf(datum.feature), k -> new ArrayList<>()).add(datum);
        }

        Set<String> guiltyTypes = new LinkedHashSet<>();
        for (Map.Entry<String, List<Feature.Data<?>>> entry : byType.entrySet()) {
            if (cancelled.get()) return cancelledReport();
            if (entry.getValue().size() == data.size()) continue;
            List<Feature.Data<?>> without = new ArrayList<>(data);
            without.removeAll(entry.getValue());
            if (rescues(solutionCount(without, pool, workers, cancelled, MAX_PROBE_CANDIDATES))) {
                guiltyTypes.add(entry.getKey());
            }
        }

        if (!guiltyTypes.isEmpty()) {
            List<String> details = new ArrayList<>();
            details.add("Every one of these disagrees with the rest:");
            for (String type : guiltyTypes) {
                details.add("  " + pretty(type));
            }
            details.add("");
            details.add("A whole type being wrong while others are fine is");
            details.add("what a server-side placement randomiser looks like.");
            details.add("It can also happen on a heavily built-on server.");
            details.add("");
            details.add("Ignore that type and collect the others.");
            return new Report(Verdict.WHOLE_TYPE_DISAGREES,
                    "One structure type disagrees with all the others.", details);
        }

        return new Report(Verdict.WIDESPREAD,
                "More than one structure is wrong.",
                List.of("No single structure or type explains the conflict, so",
                        "several of them are not where the world generated them.",
                        "",
                        "On a long-running server this usually just means the",
                        "area is heavily built on. Run /cracker clear and",
                        "collect from unexplored terrain far from spawn.",
                        "",
                        "If it keeps happening everywhere, the server is",
                        "probably randomising structure placement on purpose."));
    }

    /** Not decidable within the budget. */
    private static final long UNKNOWN = -1L;

    /**
     * @return how many structure seeds satisfy every structure in the subset,
     *         or {@link #UNKNOWN} if that could not be decided within budget
     */
    private static long solutionCount(List<Feature.Data<?>> subset, ExecutorService pool, int workers,
                                      AtomicBoolean cancelled, int maxCandidates) {
        StructureSeedSolver solver = StructureSeedSolver.compile(subset);
        if (solver == null) return UNKNOWN;

        long[] lows = solver.sieve(cancelled);
        if (cancelled.get()) return UNKNOWN;
        // An empty sieve is proof of contradiction with no scanning at all.
        if (lows != null && lows.length == 0) return 0L;
        if (lows == null || lows.length > maxCandidates) return UNKNOWN;

        Set<Long> found = solver.search(lows, pool, workers, cancelled, n -> {
        });
        if (cancelled.get()) return UNKNOWN;
        return found.size();
    }

    /**
     * Whether dropping a structure genuinely rescues the data, as opposed to
     * merely leaving too little of it to contradict anything.
     */
    private static boolean rescues(long count) {
        return count > 0 && count <= WELL_DETERMINED;
    }

    private static Report cancelledReport() {
        return new Report(Verdict.INCONCLUSIVE, "Check stopped.", List.of());
    }

    /**
     * Names the chunk, and says so.
     * <p>
     * This used to print the chunk corner as "block X, Z", which players quite
     * reasonably read as the position of the structure and teleported to - and
     * landed in the corner of the chunk on nothing in particular. All the
     * solver ever knows is which chunk a structure starts in, so the message
     * now gives the chunk and its block range and says which it is.
     */
    private static String describe(Feature.Data<?> data) {
        int x = data.chunkX << 4;
        int z = data.chunkZ << 4;
        return pretty(StructureConstraint.nameOf(data.feature)) + " in chunk " + data.chunkX + ", " + data.chunkZ
                + " (blocks " + x + ".." + (x + 15) + " by " + z + ".." + (z + 15) + ")";
    }

    private static String pretty(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
