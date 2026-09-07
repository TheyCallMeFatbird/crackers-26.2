package net.birb.crackers.gui;

import net.birb.crackers.SeedCracker;
import net.birb.crackers.config.Config;
import net.birb.crackers.cracker.storage.DataStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code /cracker} GUI. Shows live cracking progress, the found seed (with
 * a copy button) and context-aware instructions for the player.
 */
public class CrackerScreen extends Screen {

    // Colours (0xAARRGGBB).
    private static final int PANEL_BG = 0xE6101018;
    private static final int PANEL_BORDER = 0xFF2A2A3A;
    private static final int HEADER = 0xFF7C4DFF;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int GREEN = 0xFF00E676;
    private static final int AMBER = 0xFFFFB74D;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFB0B0C0;
    private static final int DARK = 0xFF303044;
    private static final int BAR_BG = 0xFF23232F;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private Button copyButton;
    private Button toggleButton;

    public CrackerScreen(Screen parent) {
        super(Component.literal("Crackers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelW = Math.min(340, this.width - 40);
        this.panelH = Math.min(250, this.height - 40);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        int btnW = (this.panelW - 30) / 2;
        int btnH = 20;
        int btnY = this.panelY + this.panelH - btnH - 12;
        int leftX = this.panelX + 10;
        int rightX = this.panelX + this.panelW - 10 - btnW;

        this.copyButton = Button.builder(Component.literal("Copy Seed"), b -> copySeed())
                .bounds(leftX, btnY, btnW, btnH).build();
        this.addRenderableWidget(this.copyButton);

        this.toggleButton = Button.builder(toggleLabel(), b -> {
            Config.get().active = !Config.get().active;
            Config.save();
            b.setMessage(toggleLabel());
        }).bounds(rightX, btnY, btnW, btnH).build();
        this.addRenderableWidget(this.toggleButton);

        int topY = btnY - btnH - 6;
        this.addRenderableWidget(Button.builder(Component.literal("Clear Data"), b -> {
                    net.birb.crackers.config.StructureSave.deleteSave();
                    SeedCracker.get().reset();
                })
                .bounds(leftX, topY, btnW, btnH).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
                .bounds(rightX, topY, btnW, btnH).build());
    }

    private Component toggleLabel() {
        return Component.literal(Config.get().active ? "Cracking: ON" : "Cracking: OFF");
    }

    private void copySeed() {
        Long seed = SeedCracker.foundSeed;
        if (seed != null) {
            Minecraft.getInstance().keyboardHandler.setClipboard(String.valueOf(seed));
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);

        // Keep the copy button state in sync with whether we have a seed.
        boolean hasSeed = SeedCracker.foundSeed != null;
        if (this.copyButton != null) {
            this.copyButton.active = hasSeed;
        }

        // Panel.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, PANEL_BORDER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        // Header bar.
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 24, HEADER);

        int cx = panelX + panelW / 2;
        drawCentered(graphics, "Crackers", cx, panelY + 8, WHITE);
        drawCentered(graphics, "Seed cracker \u00b7 Minecraft 26.2 Java", cx, panelY + 28, GREY);

        int y = panelY + 44;
        if (hasSeed) {
            renderFound(graphics, cx, y);
        } else {
            renderProgress(graphics, y);
        }
    }

    private void renderFound(GuiGraphicsExtractor graphics, int cx, int y) {
        drawCentered(graphics, "WORLD SEED FOUND", cx, y, GREEN);
        String seed = String.valueOf(SeedCracker.foundSeed);
        // Big-ish emphasis: draw the seed centered.
        drawCentered(graphics, seed, cx, y + 14, WHITE);
        String via = SeedCracker.foundVia == null ? "" : "via " + SeedCracker.foundVia;
        drawCentered(graphics, via, cx, y + 30, GREY);
        drawCentered(graphics, "Use \"Copy Seed\" below, then paste it anywhere.", cx, y + 48, GREY);
    }

    private void renderProgress(GuiGraphicsExtractor graphics, int y) {
        DataStorage s = SeedCracker.get().getDataStorage();
        double bits = s.getBaseBits();
        double wanted = s.getWantedBits();
        double lifting = s.getLiftingBits();
        boolean pillars = s.hasPillarData();
        boolean hashed = s.hashedSeedData != null && s.hashedSeedData.getHashedSeed() != 0;
        boolean gateOpen = lifting >= 40 || pillars;
        int left = panelX + 14;
        int right = panelX + panelW - 14;
        int barH = 8;

        DataStorage.Status status = s.getStatus();
        String activity;
        int activityColor = ACCENT;
        if (!Config.get().active) {
            activity = "Paused - press \"Cracking: ON\"";
        } else if (status.isStalled()) {
            activity = status.getMessage();
            activityColor = AMBER;
        } else if (bits >= wanted && gateOpen) {
            activity = "Cracking the seed\u2026 watch the chat!";
        } else if (bits >= wanted) {
            activity = "Now fill the lifting bar (or visit the End).";
        } else {
            activity = "Collecting world-gen data\u2026";
        }
        graphics.text(this.font, Component.literal(activity), left, y, activityColor);

        // Bar 1: general structure bits (candidate-checking data).
        int bar1Y = y + 13;
        drawBar(graphics, left, bar1Y, right, barH, bits / wanted, ACCENT);
        graphics.text(this.font, Component.literal(String.format("Structure bits: %d / %d", (int) bits, (int) wanted)),
                left, bar1Y + barH + 3, bits >= wanted ? GREEN : WHITE);

        // Bar 2: lifting bits - the gate that actually starts to solve.
        int bar2Y = bar1Y + barH + 15;
        if (pillars) {
            drawBar(graphics, left, bar2Y, right, barH, 1.0, GREEN);
            graphics.text(this.font, Component.literal("End pillars captured - lifting not needed"),
                    left, bar2Y + barH + 3, GREEN);
        } else {
            drawBar(graphics, left, bar2Y, right, barH, lifting / 40.0, lifting >= 40 ? GREEN : AMBER);
            graphics.text(this.font, Component.literal(String.format(
                            "Lifting bits: %d / 40 (temples, igloos, huts, wrecks)", (int) lifting)),
                    left, bar2Y + barH + 3, lifting >= 40 ? GREEN : WHITE);
        }

        int infoY = bar2Y + barH + 15;
        graphics.text(this.font, Component.literal(
                        "Data points: " + s.getStructureCount()
                                + "  \u00b7  Hashed seed: " + (hashed ? "captured" : "not yet")),
                left, infoY, hashed ? GREEN : GREY);

        int breakdownY = infoY + 11;
        for (String breakdown : breakdownLines(s, right - left)) {
            graphics.text(this.font, Component.literal(breakdown), left, breakdownY, GREY);
            breakdownY += 10;
        }

        // Instructions.
        int insY = breakdownY + 4;
        graphics.fill(left, insY - 4, right, insY - 3, DARK);
        graphics.text(this.font, Component.literal("How to crack:").withStyle(net.minecraft.ChatFormatting.BOLD),
                left, insY, WHITE);
        int line = insY + 11;
        for (String tip : instructions()) {
            graphics.text(this.font, Component.literal(tip), left, line, GREY);
            line += 10;
        }
    }

    /** What has actually been found, so "11 structures but no lift" is self-explaining. */
    private List<String> breakdownLines(DataStorage s, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (Map.Entry<String, Integer> e : s.getTypeCounts().entrySet()) {
            String part = e.getValue() + "x " + prettify(e.getKey());
            String candidate = current.length() == 0 ? part : current + "  \u00b7  " + part;

            if (current.length() > 0 && this.font.width(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(part);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private static String prettify(String name) {
        StringBuilder sb = new StringBuilder();
        for (String word : name.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private void drawBar(GuiGraphicsExtractor graphics, int left, int y, int right, int h, double frac, int color) {
        frac = Math.max(0.0, Math.min(1.0, frac));
        graphics.fill(left, y, right, y + h, BAR_BG);
        graphics.fill(left, y, left + (int) ((right - left) * frac), y + h, color);
    }

    private List<String> instructions() {
        List<String> tips = new ArrayList<>();
        boolean singleplayer = Minecraft.getInstance().getSingleplayerServer() != null;
        if (singleplayer) {
            tips.add("\u2022 Singleplayer: the seed is read instantly, no work needed.");
            tips.add("\u2022 If empty, walk around to load the world, then reopen.");
        } else {
            tips.add("\u2022 Fill both bars. Only untouched structures count.");
            tips.add("\u2022 Lifting bar: desert/jungle temples, igloos,");
            tips.add("  witch huts, shipwrecks (~9 bits each).");
            tips.add("\u2022 Shortcut: Visit the End - seeing the pillars");
            tips.add("  replaces the whole lifting bar.");
            tips.add("\u2022 Progress auto-saves & restores when you rejoin.");
        }
        return tips;
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        int w = this.font.width(text);
        graphics.text(this.font, Component.literal(text), centerX - w / 2, y, color);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
