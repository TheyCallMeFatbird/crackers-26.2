package net.birb.crackers.finder;

import net.birb.crackers.config.Config;
import net.birb.crackers.finder.decorator.DesertWellFinder;
import net.birb.crackers.finder.decorator.DungeonFinder;
import net.birb.crackers.finder.decorator.EndGatewayFinder;
import net.birb.crackers.finder.decorator.EndPillarsFinder;
import net.birb.crackers.finder.decorator.WarpedFungusFinder;
import net.birb.crackers.finder.decorator.ore.EmeraldOreFinder;
import net.birb.crackers.finder.structure.*;
import net.birb.crackers.render.Cuboid;
import net.birb.crackers.util.FeatureToggle;
import net.birb.crackers.util.HeightContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public abstract class Finder {

    /**
     * Every position in a chunk column, ordered x then z then y.
     * <p>
     * Replaced wholesale by {@link ReloadFinders#reloadHeight}, never mutated in
     * place: it used to be cleared and refilled on the client thread while
     * finder threads were iterating it, which is a ConcurrentModificationException
     * waiting for someone to walk through a nether portal. {@link JigsawFinder}
     * also indexes into it positionally, so the ordering is load-bearing.
     */
    public static volatile List<BlockPos> CHUNK_POSITIONS = List.of();
    protected static volatile HeightContext heightContext = new HeightContext(0, 256);

    protected final List<Cuboid> cuboids = new ArrayList<>();
    protected Level world;
    protected ChunkPos chunkPos;

    public Finder(Level world, ChunkPos chunkPos) {
        this.world = world;
        this.chunkPos = chunkPos;
    }

    public static List<BlockPos> buildSearchPositions(List<BlockPos> base, Predicate<BlockPos> removeIf) {
        List<BlockPos> newList = new ArrayList<>();

        for (BlockPos pos : base) {
            if (!removeIf.test(pos)) {
                newList.add(pos);
            }
        }

        return Collections.unmodifiableList(newList);
    }

    public abstract List<BlockPos> findInChunk();

    /** Whether this finder actually found anything worth keeping. */
    public boolean isUseless() {
        return this.cuboids.isEmpty();
    }

    public abstract boolean isValidDimension(DimensionType dimension);

    public boolean isOverworld(DimensionType dimension) {
        return dimension.skybox() == DimensionType.Skybox.OVERWORLD;
    }

    public boolean isNether(DimensionType dimension) {
        return dimension.skybox() == DimensionType.Skybox.NONE;
    }

    public boolean isEnd(DimensionType dimension) {
        return dimension.skybox() == DimensionType.Skybox.END;
    }

    public enum Category {
        STRUCTURES,
        DECORATORS,
        BIOMES,
    }

    public enum Type {
        BURIED_TREASURE(BuriedTreasureFinder::create, Category.STRUCTURES, c -> c.buriedTreasure, "finder.buriedTreasures"),
        DESERT_TEMPLE(DesertPyramidFinder::create, Category.STRUCTURES, c -> c.desertTemple, "finder.desertTemples"),
        END_CITY(EndCityFinder::create, Category.STRUCTURES, c -> c.endCity, "finder.endCities"),
        JUNGLE_TEMPLE(JunglePyramidFinder::create, Category.STRUCTURES, c -> c.jungleTemple, "finder.jungleTemples"),
        MONUMENT(MonumentFinder::create, Category.STRUCTURES, c -> c.monument, "finder.monuments"),
        SWAMP_HUT(SwampHutFinder::create, Category.STRUCTURES, c -> c.swampHut, "finder.swampHuts"),
        SHIPWRECK(ShipwreckFinder::create, Category.STRUCTURES, c -> c.shipwreck, "finder.shipwrecks"),
        PILLAGER_OUTPOST(OutpostFinder::create, Category.STRUCTURES, c -> c.outpost, "finder.outposts"),
        IGLOO(IglooFinder::create, Category.STRUCTURES, c -> c.igloo, "finder.igloo"),
        TRIAL_CHAMBERS(TrialChambersFinder::create, Category.STRUCTURES, c -> c.trialChambers, "finder.trialChambers"),

        END_PILLARS(EndPillarsFinder::create, Category.DECORATORS, c -> c.endPillars, "finder.endPillars"),
        END_GATEWAY(EndGatewayFinder::create, Category.DECORATORS, c -> c.endGateway, "finder.endGateways"),
        DUNGEON(DungeonFinder::create, Category.DECORATORS, c -> c.dungeon, "finder.dungeons"),
        EMERALD_ORE(EmeraldOreFinder::create, Category.DECORATORS, c -> c.emeraldOre, "finder.emeraldOres"),
        DESERT_WELL(DesertWellFinder::create, Category.DECORATORS, c -> c.desertWell, "finder.desertWells"),
        WARPED_FUNGUS(WarpedFungusFinder::create, Category.DECORATORS, c -> c.warpedFungus, "finder.warpedFungus"),

        BIOME(BiomeFinder::create, Category.BIOMES, c -> c.biome, "finder.biomes");

        public final FinderBuilder finderBuilder;
        public final String nameKey;
        private final Function<Config, FeatureToggle> toggle;

        /**
         * Cleared for the session when the matching feature fails to construct.
         * Kept apart from the config toggle so a transient failure is never
         * written to the player's config file.
         */
        public volatile boolean available = true;

        Type(FinderBuilder finderBuilder, Category category, Function<Config, FeatureToggle> toggle, String nameKey) {
            this.finderBuilder = finderBuilder;
            this.toggle = toggle;
            this.nameKey = nameKey;
        }

        /**
         * Resolved against the live config every time rather than captured at
         * class-initialisation. {@code Config.load} replaces the whole singleton,
         * so a toggle captured in an enum constant could easily end up pointing
         * at the defaults instead of the file the player actually has.
         */
        public FeatureToggle toggle() {
            return this.toggle.apply(Config.get());
        }

        public boolean isEnabled() {
            return this.available && this.toggle().get();
        }
    }
}
