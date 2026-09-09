package net.birb.crackers.cracker;

import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.UniformStructure;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.cracker.storage.DataStorage;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out what the player should actually go and do next.
 * <p>
 * This exists because of a real support question: "I tried it with up to 11
 * structures, and it didn't work". Eleven ocean monuments is eleven structures
 * and zero lifting bits, and the old UI had no way of saying so - it showed a
 * lifting bar stuck at zero next to a healthy structure count and left the
 * player to guess. Everything here is about turning the solver's internal
 * gates into a sentence someone can act on.
 */
public final class Advisor {

    /**
     * Position bits needed before a search is worth starting.
     * <p>
     * This was 40, about four and a half temples, which was too low to be
     * honest about. Measured over random worlds, structure seeds still matching:
     * <pre>
     *   4 structures (37 bits) ~ 250,000      8 structures (71 bits) ~ 60
     *   5 structures (45 bits) ~   6,800      9 structures (80 bits) ~ 20
     *   6 structures (54 bits) ~     440     10 structures (88 bits) ~  8
     *   7 structures (63 bits) ~      86
     * </pre>
     * At 40 bits the search "succeeds" and hands back thousands of candidates,
     * which then exhaust the candidate limit. 54 bits - about six structures -
     * is where it starts to mean something.
     * <p>
     * Note the floor: the count does not fall to one however many structures
     * are collected. Several structure seeds generate genuinely identical
     * structure layouts, confirmed against the seedfinding library, so the last
     * step always has to come from somewhere else - the server's hashed seed,
     * or decorators and biomes.
     */
    public static final double LIFTING_TARGET = 54.0D;

    private Advisor() {
    }

    public enum Mood {
        /** Nothing to do - either solved, or the game is handing us the seed. */
        DONE,
        /** Working normally, just needs more exploring. */
        WORKING,
        /** Actively searching right now. */
        SOLVING,
        /** Stopped and needs a decision from the player. */
        BLOCKED
    }

    /**
     * @param headline one short line, the thing to put next to a status dot
     * @param steps    concrete actions, already wrapped short enough for the panel
     */
    public record Advice(Mood mood, String headline, List<String> steps) {
    }

    public static Advice advise() {
        DataStorage storage = SeedCracker.get().getDataStorage();

        if (SeedCracker.foundSeed != null) {
            return new Advice(Mood.DONE, "World seed found", List.of());
        }
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            return new Advice(Mood.DONE, "Reading the seed from your own world",
                    List.of("You are hosting this world, so no cracking is needed.",
                            "If nothing appears, load into the world and reopen."));
        }
        if (!Config.get().active) {
            return new Advice(Mood.BLOCKED, "Collecting is paused",
                    List.of("Press \"Collecting: off\" below to switch it back on,",
                            "or run /cracker on."));
        }

        DataStorage.Status status = storage.getStatus();
        if (status == DataStorage.Status.SOLVING) {
            return new Advice(Mood.SOLVING, "Searching for the seed...",
                    List.of("This usually finishes in under a second.",
                            "The seed is printed in chat when it lands."));
        }
        if (status.isStalled()) {
            DataStorage.Diagnosis diagnosis = storage.getDiagnosis();
            if (diagnosis != null) {
                return new Advice(Mood.BLOCKED, diagnosis.summary(), diagnosis.details());
            }
            return new Advice(Mood.BLOCKED, status.getHeadline(), status.getSteps());
        }

        double base = storage.getBaseBits();
        double lifting = storage.getLiftingBits();
        boolean pillars = storage.hasPillarData();
        boolean hashed = storage.hasUsableHashedSeed();

        List<String> steps = new ArrayList<>();
        String headline;

        boolean liftingDone = pillars || lifting >= LIFTING_TARGET;
        boolean baseDone = base >= storage.getWantedBits();

        if (!liftingDone) {
            int needed = liftingStructuresStillNeeded(lifting);
            int have = countLiftable(storage);
            headline = needed == 1
                    ? "Find 1 more position structure"
                    : "Find " + needed + " more position structures";

            steps.add("These are the only ones that count towards the");
            steps.add("position bar: desert and jungle temples, igloos,");
            steps.add("witch huts, shipwrecks and trial chambers.");
            if (have == 0 && storage.getStructureCount() > 0) {
                steps.add("");
                steps.add("None of your " + storage.getStructureCount() + " finds so far are one of those :(");
                steps.add("Monuments, end cities and outposts do not count here.");
            }
            steps.add("");
            steps.add("Sailing along an ocean for shipwrecks is fastest.");
            steps.add("You can also visit the End Pillars, it");
            steps.add("replaces this whole bar on its own.");
            steps.add("");
            steps.add("6 structures is the realistic minimum, and more is better:");
            steps.add("each one cuts the remaining possibilities sharply.");
        } else if (!baseDone) {
            headline = "Find a few more structures of any kind";
            steps.add("The position bar is full, so the search can start");
            steps.add("as soon as there is enough data to confirm a seed.");
            steps.add("");
            steps.add("Anything counts now, including ocean monuments,");
            steps.add("end cities, buried treasure and dungeons.");
        } else if (!hashed) {
            headline = "This server hides its seed hash, which is a bit unfortunate.";
            steps.add("Structures alone can never finish the job. They narrow");
            steps.add("it to a handful of seeds that generate identical");
            steps.add("structures everywhere, and something else has to pick");
            steps.add("between them.");
            steps.add("");
            steps.add("Normally that is the server's hashed seed, but this one");
            steps.add("has not sent a usable value.");
            steps.add("");
            steps.add("So: collect dungeons and emerald ore as you explore.");
            steps.add("Their floor patterns depend on the full world seed and");
            steps.add("should separate the remaining candidates.");
        } else {
            headline = "Ready - starting the search";
            steps.add("Both bars are full. The search runs automatically.");
        }

        if (!liftingDone && !baseDone) {
            steps.add("");
            steps.add("Both bars need filling; the position bar is the one");
            steps.add("that actually blocks the search.");
        }

        return new Advice(Mood.WORKING, headline, steps);
    }

    /**
     * How many more liftable structures are needed, assuming an average one.
     * <p>
     * A temple, igloo or witch hut is worth log2(24 * 24) = 9.17 bits, a
     * shipwreck log2(20 * 20) = 8.64 and a trial chamber log2(22 * 22) = 8.91,
     * so "about nine bits each" is a fair estimate to plan around.
     */
    public static int liftingStructuresStillNeeded(double liftingBits) {
        double deficit = LIFTING_TARGET - liftingBits;
        if (deficit <= 0) return 0;
        return Math.max(1, (int) Math.ceil(deficit / 8.6D));
    }

    /** How many collected structures actually feed the position bar. */
    public static int countLiftable(DataStorage storage) {
        int count = 0;
        for (DataStorage.Entry<Feature.Data<?>> entry : storage.snapshotBaseData()) {
            Feature<?, ?> feature = entry.data.feature;
            if (feature instanceof UniformStructure && !(feature instanceof PillagerOutpost)) count++;
        }
        return count;
    }
}
