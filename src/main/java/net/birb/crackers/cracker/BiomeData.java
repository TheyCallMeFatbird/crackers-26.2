package net.birb.crackers.cracker;

import com.seedfinding.mcbiome.biome.Biome;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.version.MCVersion;
import net.birb.crackers.config.Config;

public class BiomeData {

    public final Biome biome;
    public final int x;
    public final int z;

    public BiomeData(Biome biome, int x, int z) {
        this.biome = biome;
        this.x = x;
        this.z = z;
    }

    public boolean test(BiomeSource source) {
        if (Config.get().getVersion().isNewerOrEqualTo(MCVersion.v1_15)) {
            return source.getBiomeForNoiseGen(this.x, 0, this.z) == this.biome;
        } else {
            return source.getBiome(this.x, 0, this.z) == this.biome;
        }
    }

    /**
     * Two samples are the same observation only if they are the same biome at
     * the same place. This used to compare the biome alone, so the enclosing
     * HashSet kept exactly one sample per biome type and threw away every other
     * position - and two plains samples at different coordinates are two
     * independent constraints on the seed, not one.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BiomeData data)) return false;
        return this.biome == data.biome && this.x == data.x && this.z == data.z;
    }

    @Override
    public int hashCode() {
        return (this.biome.getName().hashCode() * 31 + this.x) * 31 + this.z;
    }
}
