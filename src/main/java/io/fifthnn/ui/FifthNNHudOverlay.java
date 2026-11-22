package io.fifthnn.ui;

import io.fifthnn.FifthNNClient;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.inference.AiController;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.DatasetRecorderManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Zero-leakage HUD status overlay for Minecraft 26.3 (v0.3.0):
 *  - Minimal mode (default): unobtrusive corner traffic-light dot (Green=clean, Yellow=paused/quarantined,
 *    Red=flicker/ping anomaly; hidden in STANDBY) + 2.5s F6/F7/auto-record toast.
 *  - Full mode (toggleable in F8 or /5nn hud full): adds RB/FLK protocol-health counters,
 *    episode/landmark counts, and cumulative dataset skill-diet totals.
 */
public final class FifthNNHudOverlay {
    public static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(FifthNNClient.MOD_ID, "hud_status");

    private FifthNNHudOverlay() {}

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        FifthNNConfig cfg = FifthNNClient.getConfig();
        if (mc.player == null || cfg == null || !cfg.showHudOverlay) {
            return;
        }

        DatasetRecorderManager rec = FifthNNClient.getRecorder();
        BedWarsStateTracker st = FifthNNClient.getStateTracker();
        AiController ai = FifthNNClient.getAiController();
        boolean isRec = rec != null && rec.isRecording();
        boolean isAi = ai != null && ai.isAiEnabled();
        String toast = rec != null ? rec.getActiveToast() : null;

        if (!isRec && !isAi && toast == null) {
            return;
        }

        int x = 8;
        int y = 8;

        if (isRec) {
            boolean validNow = st != null && st.isLastFrameValidInGame();
            boolean quarantined = rec.isQuarantinedNow();
            int rb = rec.getSessionLagbacks();
            int flk = rec.getSessionFlickers();
            int beds = st != null ? st.getLandmarkCache().getTrackedBedCount() : 0;
            int gens = st != null ? st.getLandmarkCache().getTrackedGenCount() : 0;

            String dot;
            if (flk >= 5 || rb >= 5) {
                dot = "§c●"; // Red: protocol/lag anomaly
            } else if (!validNow || quarantined) {
                dot = "§e●"; // Yellow: lobby/pause/quarantine
            } else {
                dot = "§a●"; // Green: clean recording
            }

            if (cfg.minimalHudMode) {
                String compact = dot + " §75nn" + (flk > 0 || rb > 0 ? String.format(" §6RB:%d FLK:%d", rb, flk) : "");
                drawBadge(graphics, mc, compact, x, y);
                y += 14;
            } else {
                long sec = rec.getElapsedSeconds();
                String phaseBadge = quarantined ? "§6QUARANTINE" : (validNow ? "§aIN-GAME" : "§eSKIP");
                int ep = st != null ? st.getEpisodeId() : 1;
                String line = String.format(
                        "%s REC §f[%s] %s §7%02d:%02d | Ep#%d | %.1fMB | B:%d G:%d | §6RB:%d FLK:%d",
                        dot,
                        cfg.getRecordingModeEnum().name(),
                        phaseBadge,
                        sec / 60, sec % 60,
                        ep,
                        rec.getBytesWrittenEst() / (1024.0 * 1024.0),
                        beds, gens, rb, flk
                );
                drawBadge(graphics, mc, line, x, y);
                y += 14;

                String diet = String.format(
                        "§7Dataset Total: §f%.1fm §7(%d matches | Shop:%d | Blocks:%d)",
                        rec.getTotalSavedMinutes(), rec.getTotalSavedMatches(),
                        rec.getTotalSavedShopClicks(), rec.getTotalSavedBlocks()
                );
                drawBadge(graphics, mc, diet, x, y);
                y += 14;
            }
        }

        if (toast != null) {
            drawBadge(graphics, mc, "§f" + toast, x, y);
            y += 14;
        }

        if (isAi) {
            String line;
            if (ai.isTakeoverActive()) {
                line = "§6✋ HG-DAGGER TAKEOVER §7(Human Driving — Recording Correction)";
            } else {
                line = String.format(
                        "§b⚡ 5ifthNN AI §f[%s] §7%s | %.2f ms",
                        ai.getMode().name(),
                        ai.getEngine().getLoadedModelName(),
                        ai.getEngine().getEmaLatencyMs()
                );
            }
            drawBadge(graphics, mc, line, x, y);
        }
    }

    private static void drawBadge(GuiGraphicsExtractor graphics, Minecraft mc, String text, int x, int y) {
        Component comp = Component.literal(text);
        int w = mc.font.width(comp);
        graphics.fill(x - 3, y - 2, x + w + 4, y + 10, ARGB.color(175, 14, 18, 28));
        graphics.text(mc.font, comp, x, y, ARGB.opaque(0xFFFFFF), true);
    }
}
