package net.birb.crackers.structures;

import com.seedfinding.mcbiome.biome.Biome;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mccore.version.VersionMap;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;

public class TrialChambers extends UniformStructure<TrialChambers> {

    public static final VersionMap<Config> CONFIGS = new VersionMap<Config>()
            .add(MCVersion.v1_21, new Config(34, 12, 94251327));

    public TrialChambers(MCVersion version) {
        this(CONFIGS.getAsOf(version), version);
    }

    public TrialChambers(RegionStructure.Config config, MCVersion version) {
        super(config, version);
    }

    public static String name() {
        return "trial_chambers";
    }

    /**
     * Must be overridden, and its absence was fatal.
     * <p>
     * {@code Structure#getName()} resolves through a static class-to-name map
     * built into the seedfinding library, which only contains the library's own
     * structure classes. This one is ours, so it returned {@code null} - and
     * every trial chamber found then threw a NullPointerException inside
     * {@code DataStorage.Entry#hashCode} before it could be stored, which the
     * chunk-scanning catch swallowed. Trial chambers were detected correctly
     * and discarded silently, every single time.
     */
    @Override
    public String getName() {
        return name();
    }

    @Override
    public Dimension getValidDimension() {
        return Dimension.OVERWORLD;
    }

    @Override
    public boolean isValidBiome(Biome biome) {
        // FIXME: Deep Dark doesn't exist
        return true;
    }
}