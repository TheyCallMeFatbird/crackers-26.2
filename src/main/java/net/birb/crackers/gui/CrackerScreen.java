package net.birb.crackers.gui;

import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.config.StructureSave;
import net.birb.crackers.cracker.Advisor;
import net.birb.crackers.cracker.storage.DataStorage;
import net.birb.crackers.finder.ReloadFinders;
import net.birb.crackers.util.Log;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code /cracker} screen.
 *
 * <h2>Layout</h2>
 * One card: a title bar, a scrollable body, and two rows of buttons pinned to
 * the bottom. The body is clipped to its own region and can be scrolled, which
 * is the only way to be safe here - the advice text is variable length and the
 * card can only ever be as tall as the window. Sizing the card to its content
 * and hoping was not enough: on a short window the card hit the height cap and
 * the text ran straight through the buttons with no way to reach it.
 *
 * <h2>One layout pass</h2>
 * {@link #layoutBody} both measures and draws, depending on whether it is
 * handed a graphics context. Keeping a separate measuring routine in step with
 * the drawing routine by hand is how the overflow got shipped in the first
 * place.
 */
public class CrackerScreen extends Screen {

    // Palette. 0xAARRGGBB.
    private static final int SCRIM = 0xB0000000;
    private static final int CARD = 0xFF15151F;
    private static final int CARD_EDGE = 0xFF33334A;
    private static final int TITLE_BAR = 0xFF1D1D2B;
    private static final int ACCENT = 0xFF8B5CF6;
    private static final int RULE = 0xFF2A2A3C;
    private static final int WELL = 0xFF10101A;

    private static final int TEXT = 0xFFE8E8F0;
    private static final int MUTED = 0xFF8A8AA0;
    private static final int LABEL = 0xFF6F6F88;
    private static final int GOOD = 0xFF34D399;
    private static final int BUSY = 0xFF60A5FA;
    private static final int WARN = 0xFFFBBF24;
    private static final int DANGER = 0xFFF87171;

    private static final int TRACK = 0xFF262636;
    private static final int THUMB = 0xFF4A4A66;

    private static final int LINE = 10;
    private static final int BAR_H = 6;
    private static final int BTN_H = 20;
    private static final int PAD = 12;
    private static final int TITLE_H = 26;
    private static final int SCROLLBAR_W = 3;

    private final Screen parent;

    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;
    private int bodyTop;
    private int bodyBottom;

    private int scroll;
    private int contentHeight;

    private Button copyButton;

    private Advisor.Advice advice = new Advisor.Advice(Advisor.Mood.WORKING, "", List.of());
    private List<String> foundLines = List.of();
    private int lastSignature = Integer.MIN_VALUE;

    /** Reset wipes the on-disk save too, so it asks first. */
    private boolean confirmingReset;

    public CrackerScreen(Screen parent) {
        super(Component.literal("Crackers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // init() runs again on every rebuild; the old widgets are gone by then.
        this.copyButton = null;
        this.cardW = Math.min(360, this.width - 32);
        this.cardX = (this.width - this.cardW) / 2;
        refreshContent();

        int buttonBlock = BTN_H * 2 + 6;
        int preferred = TITLE_H + PAD + layoutBody(null, 0) + PAD + buttonBlock + PAD;
        this.cardH = Math.min(preferred, this.height - 24);
        this.cardY = (this.height - this.cardH) / 2;

        this.bodyTop = this.cardY + TITLE_H + PAD;
        this.bodyBottom = this.cardY + this.cardH - PAD - buttonBlock - PAD;
        this.contentHeight = layoutBody(null, 0);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));

        int gap = 6;
        int btnW = (this.cardW - PAD * 2 - gap) / 2;
        int rowTwo = this.cardY + this.cardH - PAD - BTN_H;
        int rowOne = rowTwo - BTN_H - gap;
        int leftX = this.cardX + PAD;
        int rightX = leftX + btnW + gap;

        if (this.confirmingReset) {
            this.addRenderableWidget(Button.builder(Component.literal("Yes, erase it"), b -> {
                StructureSave.deleteSave();
                SeedCracker.get().reset();
                // Nothing re-sends chunks the client already has, so without
                // this the mod looks dead until you walk somewhere new.
                ReloadFinders.rescanLoadedChunks();
                Log.problem("Cleared everything collected here, including the saved file.");
                this.confirmingReset = false;
                this.scroll = 0;
                this.rebuildWidgets();
            }).bounds(leftX, rowOne, btnW, BTN_H).build());

            this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                this.confirmingReset = false;
                this.rebuildWidgets();
            }).bounds(rightX, rowOne, btnW, BTN_H).build());

            this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                    .bounds(leftX, rowTwo, this.cardW - PAD * 2, BTN_H).build());
            return;
        }

        this.copyButton = Button.builder(Component.literal("Copy seed"), b -> copySeed())
                .bounds(leftX, rowOne, btnW, BTN_H).build();
        this.copyButton.active = SeedCracker.foundSeed != null;
        this.addRenderableWidget(this.copyButton);

        this.addRenderableWidget(Button.builder(toggleLabel(), b -> {
            Config.get().active = !Config.get().active;
            Config.save();
            if (Config.get().active) ReloadFinders.rescanLoadedChunks();
            this.rebuildWidgets();
        }).bounds(rightX, rowOne, btnW, BTN_H).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reset data"), b -> {
            this.confirmingReset = true;
            this.rebuildWidgets();
        }).bounds(leftX, rowTwo, btnW, BTN_H).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                .bounds(rightX, rowTwo, btnW, BTN_H).build());
    }

    /** Re-lays the card out when the numbers behind it move. */
    @Override
    public void tick() {
        if (signature() != this.lastSignature) this.rebuildWidgets();
    }

    private int signature() {
        DataStorage storage = SeedCracker.get().getDataStorage();
        int sig = storage.getStructureCount();
        sig = sig * 31 + (int) storage.getLiftingBits();
        sig = sig * 31 + (int) storage.getBaseBits();
        sig = sig * 31 + storage.getStatus().ordinal();
        sig = sig * 31 + (SeedCracker.foundSeed == null ? 0 : 1);
        sig = sig * 31 + (Config.get().active ? 1 : 0);
        DataStorage.Diagnosis diagnosis = storage.getDiagnosis();
        sig = sig * 31 + (diagnosis == null ? 0 : diagnosis.summary().hashCode());
        return sig;
    }

    private void refreshContent() {
        this.lastSignature = signature();
        this.advice = Advisor.advise();
        // Wrap to the card we are actually drawing, not a guessed width.
        this.foundLines = breakdownLines(SeedCracker.get().getDataStorage(), this.cardW - PAD * 2 - SCROLLBAR_W - 2);
    }

    private int bodyHeight() {
        return Math.max(LINE, this.bodyBottom - this.bodyTop);
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - bodyHeight());
    }

    private Component toggleLabel() {
        return Component.literal(Config.get().active ? "Collecting: on" : "Collecting: off");
    }

    private void copySeed() {
        Long seed = SeedCracker.foundSeed;
        if (seed != null) {
            Minecraft.getInstance().keyboardHandler.setClipboard(String.valueOf(seed));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0) {
            this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll - (int) (scrollY * LINE * 2)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, SCRIM);

        if (this.copyButton != null) this.copyButton.active = SeedCracker.foundSeed != null;

        g.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, CARD_EDGE);
        g.fill(cardX, cardY, cardX + cardW, cardY + cardH, CARD);
        g.fill(cardX, cardY, cardX + cardW, cardY + TITLE_H, TITLE_BAR);
        g.fill(cardX, cardY, cardX + cardW, cardY + 2, ACCENT);

        g.text(this.font, Component.literal("Crackers").withStyle(ChatFormatting.BOLD),
                cardX + PAD, cardY + 9, TEXT);
        String version = "Minecraft 26.2";
        g.text(this.font, Component.literal(version),
                cardX + cardW - PAD - this.font.width(version), cardY + 9, LABEL);

        g.enableScissor(cardX, this.bodyTop, cardX + cardW, this.bodyBottom);
        layoutBody(g, this.bodyTop - this.scroll);
        g.disableScissor();

        int overflow = maxScroll();
        if (overflow > 0) {
            int trackX = cardX + cardW - PAD + 4;
            int trackH = bodyHeight();
            int thumbH = Math.max(12, trackH * trackH / Math.max(1, this.contentHeight));
            int thumbY = this.bodyTop + (trackH - thumbH) * this.scroll / overflow;
            g.fill(trackX, this.bodyTop, trackX + SCROLLBAR_W, this.bodyBottom, TRACK);
            g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, THUMB);
        }
    }

    /**
     * Draws the body when given a graphics context, measures it when given
     * {@code null}. Either way it returns the height the content occupies.
     */
    private int layoutBody(GuiGraphicsExtractor g, int top) {
        int left = cardX + PAD;
        int right = cardX + cardW - PAD - SCROLLBAR_W - 2;
        int y = top;

        if (this.confirmingReset) {
            y = text(g, "Erase everything collected here?", left, y, DANGER) + 4;
            y = text(g, "This clears the structures found in this world and", left, y, MUTED);
            y = text(g, "deletes the saved progress file for this server.", left, y, MUTED);
            y += 4;
            y = text(g, "Collection carries on afterwards, starting from", left, y, MUTED);
            y = text(g, "nothing.", left, y, MUTED);
            return y - top;
        }

        if (SeedCracker.foundSeed != null) {
            y = text(g, "WORLD SEED", left, y, LABEL) + 4;
            if (g != null) g.fill(left, y - 3, right, y + 13, WELL);
            String seed = String.valueOf(SeedCracker.foundSeed);
            if (g != null) {
                g.text(this.font, Component.literal(seed).withStyle(ChatFormatting.BOLD), left + 6, y + 1, GOOD);
            }
            y += 16 + 4;
            String via = SeedCracker.foundVia == null ? "" : SeedCracker.foundVia;
            boolean confirmed = via.startsWith("confirmed") || via.contains("singleplayer") || via.contains("saved");
            y = text(g, (confirmed ? "✓ " : "⚠ ") + capitalise(via), left, y, confirmed ? GOOD : WARN);
            return y - top;
        }

        DataStorage storage = SeedCracker.get().getDataStorage();

        // Status line with a coloured dot.
        int dot = switch (this.advice.mood()) {
            case DONE -> GOOD;
            case SOLVING -> BUSY;
            case BLOCKED -> WARN;
            default -> ACCENT;
        };
        if (g != null) g.fill(left, y + 2, left + 4, y + 6, dot);
        if (g != null) g.text(this.font, Component.literal(this.advice.headline()), left + 10, y, TEXT);
        y += LINE + 8;

        double lifting = storage.getLiftingBits();
        if (storage.hasPillarData()) {
            y = bar(g, left, right, y, "Position data", "End pillars found", 1.0, GOOD);
        } else {
            y = bar(g, left, right, y, "Position data",
                    (int) lifting + " / " + (int) Advisor.LIFTING_TARGET,
                    lifting / Advisor.LIFTING_TARGET,
                    lifting >= Advisor.LIFTING_TARGET ? GOOD : ACCENT);
        }

        double base = storage.getBaseBits();
        double wanted = storage.getWantedBits();
        y = bar(g, left, right, y, "Total data", (int) base + " / " + (int) wanted,
                base / wanted, base >= wanted ? GOOD : ACCENT);

        if (g != null) g.fill(left, y, right, y + 1, RULE);
        y += 6;
        y = text(g, "WHAT TO DO NEXT", left, y, LABEL) + 6;
        for (String step : this.advice.steps()) {
            y = step.isBlank() ? y + LINE : text(g, step, left, y, MUTED);
        }

        y += 8;
        if (g != null) g.fill(left, y, right, y + 1, RULE);
        y += 6;
        if (this.foundLines.isEmpty()) {
            y = text(g, "Nothing found yet", left, y, MUTED);
        } else {
            for (String line : this.foundLines) {
                y = text(g, line, left, y, MUTED);
            }
        }
        // On its own line: it used to be right-aligned against the first
        // breakdown line and the two overlapped as soon as that line was long.
        boolean hashed = storage.hasUsableHashedSeed();
        y = text(g, hashed ? "✓ hashed seed captured" : "⚠ no hashed seed from this server",
                left, y, hashed ? GOOD : WARN);

        return y - top;
    }

    /** Draws one line if drawing, and advances. */
    private int text(GuiGraphicsExtractor g, String content, int x, int y, int colour) {
        if (g != null) g.text(this.font, Component.literal(content), x, y, colour);
        return y + LINE;
    }

    /** One labelled progress bar. Returns the y to carry on from. */
    private int bar(GuiGraphicsExtractor g, int left, int right, int y,
                    String label, String value, double fraction, int colour) {
        if (g != null) {
            g.text(this.font, Component.literal(label), left, y, MUTED);
            g.text(this.font, Component.literal(value), right - this.font.width(value), y, colour);
        }
        y += LINE + 2;
        if (g != null) {
            double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
            g.fill(left, y, right, y + BAR_H, TRACK);
            int filled = (int) ((right - left) * clamped);
            if (filled > 0) g.fill(left, y, left + filled, y + BAR_H, colour);
        }
        return y + BAR_H + 8;
    }

    /** "3x Desert Pyramid · 1x Shipwreck", wrapped to the card. */
    private List<String> breakdownLines(DataStorage storage, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (Map.Entry<String, Integer> e : storage.getTypeCounts().entrySet()) {
            String part = e.getValue() + "x " + net.birb.crackers.Features.displayName(e.getKey());
            String candidate = current.isEmpty() ? part : current + "  ·  " + part;

            if (!current.isEmpty() && this.font.width(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(part);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private static String capitalise(String text) {
        if (text.isEmpty()) return text;
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    @Override
    public void onClose() {
        if (this.confirmingReset) {
            this.confirmingReset = false;
            this.rebuildWidgets();
            return;
        }
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
