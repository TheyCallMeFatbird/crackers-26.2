package net.birb.crackers.mixin;

import com.seedfinding.mcfeature.structure.OldStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.config.StructureSave;
import net.birb.crackers.cracker.DataAddedEvent;
import net.birb.crackers.cracker.HashedSeedData;
import net.birb.crackers.finder.FinderQueue;
import net.birb.crackers.finder.ReloadFinders;
import net.birb.crackers.util.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "TAIL"))
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        FinderQueue.get().onChunkData(this.level, new ChunkPos(packet.getX(), packet.getZ()));
    }

    @Unique
    private static boolean crackers$greeted = false;

    @Inject(method = "handleLogin", at = @At(value = "TAIL"))
    public void onGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        long hashed = packet.commonPlayerSpawnInfo().seed();
        newDimension(new HashedSeedData(hashed), false);

        // "how do i access the seed gui" was the single most common question on
        // the mod page, so say it once per session rather than never.
        if (!crackers$greeted) {
            crackers$greeted = true;
            Log.action("Crackers is running. Click here, or type /cracker, to open it.", "/cracker");
        }

        // If we already cracked this world, just recall the seed - no re-crack.
        Long savedSeed = StructureSave.loadSeed(hashed);
        if (savedSeed != null) {
            SeedCracker.reportSeed(savedSeed, "saved from previous session");
            SeedCracker.get().getDataStorage().getTimeMachine().worldSeeds.clear();
            SeedCracker.get().getDataStorage().getTimeMachine().worldSeeds.add(savedSeed);
            Log.printSeed("crackers.savedSeed", savedSeed);
            return;
        }

        var preloaded = StructureSave.loadStructures();
        if (!preloaded.isEmpty()) {
            int restored = 0;
            for (var data : preloaded) {
                DataAddedEvent event = (data.feature instanceof OldStructure || data.feature instanceof Shipwreck)
                        ? DataAddedEvent.POKE_LIFTING
                        : DataAddedEvent.POKE_STRUCTURES;
                if (SeedCracker.get().getDataStorage().addBaseData(data, event)) {
                    restored++;
                }
            }
            if (restored > 0) {
                Log.warn("data.restoreStructures", restored);
            }
        }
    }

    @Inject(method = "handleRespawn", at = @At(value = "TAIL"))
    public void onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), true);
    }

    @Unique
    private void newDimension(HashedSeedData hashedSeedData, boolean dimensionChange) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null) return;

        DimensionType dimension = current.dimensionType();
        ReloadFinders.reloadHeight(dimension.minY(), dimension.minY() + dimension.logicalHeight());

        if (SeedCracker.get().getDataStorage().addHashedSeedData(hashedSeedData, DataAddedEvent.POKE_BIOMES)
                && Config.get().active && dimensionChange) {
            Log.error(Log.translate("fetchedHashedSeed"));
            if (Config.get().debug) {
                Log.error("Hashed seed [" + hashedSeedData.getHashedSeed() + "]");
            }
        }
    }
}
