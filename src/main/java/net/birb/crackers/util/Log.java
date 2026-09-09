package net.birb.crackers.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.regex.Pattern;

/**
 * Everything Crackers says in chat.
 * <p>
 * One prefix, three weights: a {@link #headline} for something that just
 * happened, {@link #detail} for the explanation under it, and
 * {@link #printSeed} for a click-to-copy seed. Chat messages used to be a mix
 * of green "warn", red "error" and unprefixed raw strings with no relationship
 * to how important they were - a routine progress note and a hard failure
 * looked equally alarming.
 */
public class Log {

    /**
     * The tag on every chat line. Bold orange.
     * <p>
     * Change {@code ChatFormatting.GOLD} for a different colour and drop
     * {@code ChatFormatting.BOLD} to unbold it. This is the only place the tag
     * is defined, so editing it here changes every message the mod sends.
     */
    private static final Component PREFIX = Component.literal("[Crackers] ")
            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

    /** Something the player should notice. */
    public static void headline(String message) {
        send(PREFIX.copy().append(Component.literal(message).withStyle(ChatFormatting.WHITE)));
    }

    /** A supporting line under a headline. Indented, quiet. */
    public static void detail(String message) {
        send(Component.literal("  " + message).withStyle(ChatFormatting.GRAY));
    }

    /** Good news. */
    public static void success(String message) {
        send(PREFIX.copy().append(Component.literal(message).withStyle(ChatFormatting.GREEN)));
    }

    /** Something stopped and needs the player. */
    public static void problem(String message) {
        send(PREFIX.copy().append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
    }

    /** Only shown with debug on. */
    public static void debug(String message) {
        send(Component.literal(message).withStyle(ChatFormatting.DARK_GRAY));
    }

    public static void warn(String translateKey, Object... args) {
        send(PREFIX.copy().append(Component.literal(format(translateKey, args)).withStyle(ChatFormatting.GREEN)));
    }

    public static void error(String translateKey) {
        send(PREFIX.copy().append(Component.literal(translate(translateKey)).withStyle(ChatFormatting.RED)));
    }

    /** Tells the player, in plain words, that the solve stopped and why. */
    public static void reportStalled(String message) {
        problem(message);
    }

    /**
     * Formats a translated string, tolerating one that has no placeholders or a
     * stray percent sign. A missing key falls through to the key itself, so
     * without this an unformattable string would throw out of a solver thread.
     */
    private static String format(String translateKey, Object... args) {
        String message = translate(translateKey);
        if (args.length == 0) return message;
        try {
            return message.formatted(args);
        } catch (java.util.IllegalFormatException e) {
            return message;
        }
    }

    public static void printSeed(String translateKey, long seedValue) {
        String message = translate(translateKey);
        String[] data = message.split(Pattern.quote("${SEED}"));
        String seed = String.valueOf(seedValue);
        Component text = ComponentUtils.wrapInSquareBrackets(Component.literal(seed).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.CopyToClipboard(seed))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click")))
                .withInsertion(seed)));

        MutableComponent line = PREFIX.copy().append(Component.literal(data[0]).withStyle(ChatFormatting.WHITE)).append(text);
        if (data.length > 1) {
            line.append(Component.literal(data[1]).withStyle(ChatFormatting.WHITE));
        }
        send(line);
    }

    public static void printDungeonInfo(String message) {
        send(ComponentUtils.wrapInSquareBrackets(Component.literal(message).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.CopyToClipboard(message))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click")))
                .withInsertion(message))));
    }

    /** A clickable line that runs a command when clicked. */
    public static void action(String message, String command) {
        send(PREFIX.copy().append(Component.literal(message).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))))));
    }

    public static String translate(String translateKey) {
        return Language.getInstance().getOrDefault(translateKey);
    }

    private static void send(Component component) {
        Minecraft.getInstance().execute(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.gui == null) return;
            client.gui.chatListener().handleSystemMessage(component, false);
        });
    }
}
