package net.birb.crackers;

import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.decorator.DesertWell;
import com.seedfinding.mcfeature.decorator.EndGateway;
import com.seedfinding.mcfeature.structure.BuriedTreasure;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.EndCity;
import com.seedfinding.mcfeature.structure.Igloo;
import com.seedfinding.mcfeature.structure.JunglePyramid;
import com.seedfinding.mcfeature.structure.Monument;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.SwampHut;
import net.birb.crackers.cracker.decorator.DeepDungeon;
import net.birb.crackers.cracker.decorator.Dungeon;
import net.birb.crackers.cracker.decorator.EmeraldOre;
import net.birb.crackers.cracker.decorator.WarpedFungus;
import net.birb.crackers.finder.Finder;
import net.birb.crackers.structures.TrialChambers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Features {

    /**
     * A feature's name, never null.
     * <p>
     * The library resolves names through a map of its own classes, so anything
     * added here returns null and poisons every map key, hash and log line it
     * reaches. Use this rather than {@code feature.getName()} anywhere a null
     * would matter.
     */
    /**
     * The name to show a player, taken from the language file when there is an
     * entry for it.
     * <p>
     * Add or edit {@code crackers.structure.<id>} in
     * {@code assets/crackers/lang/en_us.json} to rename anything - "Witch Hut"
     * instead of "Swamp Hut", say. Without an entry the internal id is tidied
     * up instead, so a new structure type still reads sensibly.
     */
    public static String displayName(String id) {
        String key = "crackers.structure." + id;
        String translated = net.birb.crackers.util.Log.translate(key);
        if (!translated.equals(key)) return translated;

        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public static String nameOf(Feature<?, ?> feature) {
        if (feature == null) return "unknown";
        String name = feature.getName();
        return name != null ? name : feature.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
    public static final ArrayList<RegionStructure<?, ?>> STRUCTURE_TYPES = new ArrayList<>();

    public static BuriedTreasure BURIED_TREASURE;
    public static DesertPyramid DESERT_PYRAMID;
    public static EndCity END_CITY;
    public static JunglePyramid JUNGLE_PYRAMID;
    public static Monument MONUMENT;
    public static Shipwreck SHIPWRECK;
    public static SwampHut SWAMP_HUT;
    public static PillagerOutpost PILLAGER_OUTPOST;
    public static Igloo IGLOO;
    public static TrialChambers TRIAL_CHAMBERS;

    public static EndGateway END_GATEWAY;
    public static DesertWell DESERT_WELL;
    public static EmeraldOre EMERALD_ORE;
    public static Dungeon DUNGEON;
    public static DeepDungeon DEEP_DUNGEON;
    public static WarpedFungus WARPED_FUNGUS;

    public static void init(MCVersion version) {
        STRUCTURE_TYPES.clear();

        BURIED_TREASURE = safe(STRUCTURE_TYPES, Finder.Type.BURIED_TREASURE, () -> new BuriedTreasure(version));
        DESERT_PYRAMID = safe(STRUCTURE_TYPES, Finder.Type.DESERT_TEMPLE, () -> new DesertPyramid(version));
        END_CITY = safe(STRUCTURE_TYPES, Finder.Type.END_CITY, () -> new EndCity(version));
        JUNGLE_PYRAMID = safe(STRUCTURE_TYPES, Finder.Type.JUNGLE_TEMPLE, () -> new JunglePyramid(version));
        MONUMENT = safe(STRUCTURE_TYPES, Finder.Type.MONUMENT, () -> new Monument(version));
        SHIPWRECK = safe(STRUCTURE_TYPES, Finder.Type.SHIPWRECK, () -> new Shipwreck(version));
        SWAMP_HUT = safe(STRUCTURE_TYPES, Finder.Type.SWAMP_HUT, () -> new SwampHut(version));
        PILLAGER_OUTPOST = safe(STRUCTURE_TYPES, Finder.Type.PILLAGER_OUTPOST, () -> new PillagerOutpost(version));
        IGLOO = safe(STRUCTURE_TYPES, Finder.Type.IGLOO, () -> new Igloo(version));
        TRIAL_CHAMBERS = safe(STRUCTURE_TYPES, Finder.Type.TRIAL_CHAMBERS, () -> new TrialChambers(version));

        END_GATEWAY = safe(Finder.Type.END_GATEWAY, () -> new EndGateway(version));
        DESERT_WELL = safe(Finder.Type.DESERT_WELL, () -> new DesertWell(version));
        EMERALD_ORE = safe(Finder.Type.EMERALD_ORE, () -> new EmeraldOre(version));
        DUNGEON = safe(Finder.Type.DUNGEON, () -> new Dungeon(version));
        DEEP_DUNGEON = safe(Finder.Type.DUNGEON, () -> new DeepDungeon(version));
        WARPED_FUNGUS = safe(Finder.Type.WARPED_FUNGUS, () -> new WarpedFungus(version));

        STRUCTURE_TYPES.trimToSize();
    }

    private static <F extends Feature<?, ?>> F safe(Finder.Type finderType, Supplier<F> lambda) {
        try {
            return lambda.get();
        } catch (Throwable t) {
            SeedCracker.LOGGER.error("Exception thrown loading feature", t);
            // Disable for this session only. This used to call
            // finderType.enabled.set(false), which mutates the live config
            // object, so the next Config.save() - any GUI toggle - wrote the
            // failure to disk permanently.
            finderType.available = false;
            return null;
        }
    }

    private static <F extends RegionStructure<?, ?>> F safe(List<RegionStructure<?, ?>> list, Finder.Type finderType, Supplier<F> lambda) {
        F initializedFeature = safe(finderType, lambda);
        if (initializedFeature != null) list.add(initializedFeature);
        return initializedFeature;
    }

}
