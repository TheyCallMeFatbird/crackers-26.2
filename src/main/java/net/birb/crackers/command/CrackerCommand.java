package net.birb.crackers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.config.StructureSave;
import net.birb.crackers.cracker.storage.DataStorage;
import net.birb.crackers.gui.CrackerScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Registers the {@code /cracker} client command.
 * <p>
 * Running {@code /cracker} on its own opens the GUI. Sub-commands provide quick
 * access to the most common actions without opening the screen.
 */
public final class CrackerCommand {

    private CrackerCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("cracker")
                        .executes(ctx -> openGui());

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("gui")
                .executes(ctx -> openGui()));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                .executes(ctx -> setActive(true)));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                .executes(ctx -> setActive(false)));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
                .executes(ctx -> {
                    StructureSave.deleteSave();
                    SeedCracker.get().reset();
                    feedback("Cleared all collected data (including the on-disk save).", ChatFormatting.YELLOW);
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bits")
                .executes(ctx -> printBits()));

        dispatcher.register(root);
    }

    private static int openGui() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreenAndShow(new CrackerScreen(mc.gui.screen())));
        return 1;
    }

    private static int setActive(boolean active) {
        Config.get().active = active;
        Config.save();
        feedback("Cracker is now " + (active ? "ON" : "OFF") + ".", active ? ChatFormatting.GREEN : ChatFormatting.RED);
        return 1;
    }

    private static int printBits() {
        DataStorage s = SeedCracker.get().getDataStorage();
        feedback(String.format("Collected %d / %d structure bits (%d data points).",
                (int) s.getBaseBits(), (int) s.getWantedBits(), s.getStructureCount()), ChatFormatting.AQUA);
        feedback(String.format("Lifting bits: %d / 40. End pillars: %s.",
                (int) s.getLiftingBits(), s.hasPillarData() ? "captured" : "not captured"), ChatFormatting.AQUA);
        return 1;
    }

    private static void feedback(String message, ChatFormatting color) {
        try {
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(
                    Component.literal("[Crackers] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .append(Component.literal(message).withStyle(color)), false);
        } catch (Exception ignored) {
        }
    }
}
