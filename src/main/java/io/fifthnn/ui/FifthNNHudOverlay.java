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
 * Compact, non-intrusive HUD status badge for Minecraft 26.3 (HudElementRegistry + GuiGraphicsExtractor).
 * Shows real-time recording state (IN-GAME vs LOBBY/PAUSE filter), episode number, live tracked
 * arena landmarks (Beds / Gens), valid frame count, file size, and Phase 2 ONNX latency.
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
        if (!isRec && !isAi) {
            return;
        }

        int x = 8;
        int y = 8;

        if (isRec) {
            long sec = rec.getElapsedSeconds();
            boolean validNow = st != null && st.isLastFrameValidInGame();
            String phaseBadge = validNow ? "§aIN-GAME" : "§eLOBBY/SKIP";
            int ep = st != null ? st.getEpisodeId() : 1;
            int beds = st != null ? st.getLandmarkCache().getTrackedBedCount() : 0;
            int gens = st != null ? st.getLandmarkCache().getTrackedGenCount() : 0;

            String line = String.format(
                    "§c● REC §f[%s] %s §7%02d:%02d | Ep#%d | %d valid (%.1fMB) | B:%d G:%d",
                    cfg.getRecordingModeEnum().name(),
                    phaseBadge,
                    sec / 60, sec % 60,
                    ep,
                    rec.getValidFramesWritten(),
                    rec.getBytesWrittenEst() / (1024.0 * 1024.0),
                    beds, gens
            );
            drawBadge(graphics, mc, line, x, y);
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
