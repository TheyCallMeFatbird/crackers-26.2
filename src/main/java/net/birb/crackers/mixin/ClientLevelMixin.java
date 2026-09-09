package net.birb.crackers.mixin;

import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.StructureSave;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin extends Level {

    protected ClientLevelMixin(WritableLevelData properties, ResourceKey<Level> registryRef, RegistryAccess registryManager, Holder<DimensionType> dimensionEntry, boolean isClient, boolean debugWorld, long biomeAccess, int maxChainedNeighborUpdates) {
        super(properties, registryRef, registryManager, dimensionEntry, isClient, debugWorld, biomeAccess, maxChainedNeighborUpdates);
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void disconnect(Component reason, CallbackInfo ci) {
        try {
            StructureSave.saveStructures(SeedCracker.get().getDataStorage().baseSeedData);
        } catch (Throwable t) {
            SeedCracker.LOGGER.error("Crackers could not save progress on disconnect", t);
        }
        SeedCracker.get().reset();
    }

    /**
     * Makes an ungenerated position report the void biome instead of plains.
     * <p>
     * Vanilla returns plains from here as a placeholder for anything the client
     * has no chunk for. The biome finder cannot tell that apart from a real
     * plains reading, so without this it would feed the solver observations of
     * a biome that was never actually generated. The void biome is the agreed
     * "ignore this sample" marker, and the finder skips it.
     */
    @Inject(method = "getUncachedNoiseBiome", at = @At("HEAD"), cancellable = true)
    private void getGeneratorStoredBiome(int x, int y, int z, CallbackInfoReturnable<Holder<Biome>> ci) {
        registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.THE_VOID).ifPresent(ci::setReturnValue);
    }
}
