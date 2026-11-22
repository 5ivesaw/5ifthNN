package io.fifthnn.ui;

import io.fifthnn.FifthNNClient;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.inference.AiController;
import io.fifthnn.recorder.DatasetRecorderManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

import java.util.List;

/**
 * Native Minecraft 26.3 Control Panel GUI (v0.3.0) for Phase 1 auto-recorder settings,
 * share export, dependency setup, and Phase 2 ONNX inference & guardrails.
 */
public final class FifthNNControlScreen extends Screen {
    private static final String[] SERVER_PROFILES = {"auto", "local", "pika", "jartex", "mineberry", "hypixel", "custom"};

    private final FifthNNConfig config;
    private final DatasetRecorderManager recorder;
    private final AiController ai;
    private List<String> availableModels;
    private int modelIndex = 0;

    public FifthNNControlScreen() {
        super(Component.literal("5ifthNN Control Panel"));
        this.config = FifthNNClient.getConfig();
        this.recorder = FifthNNClient.getRecorder();
        this.ai = FifthNNClient.getAiController();
        this.availableModels = ai.listAvailableModels();
        for (int i = 0; i < availableModels.size(); i++) {
            if (availableModels.get(i).equals(config.selectedModelFile)) {
                modelIndex = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int colL = cx - 155;
        int colR = cx + 5;
        int bw = 150;
        int bh = 20;
        int y = 36;

        // === PHASE 1: AUTO-RECORDER ===
        addRenderableWidget(Button.builder(
                Component.literal(recorder.isRecording() ? "§c■ Stop Recording (F9)" : "§a● Manual Record (F9)"),
                btn -> {
                    if (this.minecraft != null) {
                        recorder.toggleRecording(this.minecraft);
                        rebuildWidgets();
                    }
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Auto-Record BW: " + (config.autoRecordBedWars ? "§aON" : "§7OFF")),
                btn -> {
                    config.autoRecordBedWars = !config.autoRecordBedWars;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 24;
        addRenderableWidget(Button.builder(
                Component.literal("Mode: §e" + config.getRecordingModeEnum().displayName),
                btn -> {
                    config.setRecordingModeEnum(config.getRecordingModeEnum().next());
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Server Tag: §b" + config.serverProfile),
                btn -> {
                    int idx = 0;
                    for (int i = 0; i < SERVER_PROFILES.length; i++) {
                        if (SERVER_PROFILES[i].equalsIgnoreCase(config.serverProfile)) {
                            idx = (i + 1) % SERVER_PROFILES.length;
                            break;
                        }
                    }
                    config.serverProfile = SERVER_PROFILES[idx];
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 24;
        addRenderableWidget(Button.builder(
                Component.literal("HUD: " + (!config.showHudOverlay ? "§7OFF" : (config.minimalHudMode ? "§aMinimal Dot" : "§bFull Stats"))),
                btn -> {
                    if (!config.showHudOverlay) {
                        config.showHudOverlay = true;
                        config.minimalHudMode = true;
                    } else if (config.minimalHudMode) {
                        config.minimalHudMode = false;
                    } else {
                        config.showHudOverlay = false;
                    }
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Flicker Guard: " + (config.enableViaFlickerGuard ? "§aON" : "§7OFF")),
                btn -> {
                    config.enableViaFlickerGuard = !config.enableViaFlickerGuard;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 24;
        addRenderableWidget(Button.builder(
                Component.literal("§bExport Share ZIP"),
                btn -> {
                    if (this.minecraft != null) {
                        recorder.exportShareZip(this.minecraft);
                    }
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("§eRecommended Setup"),
                btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.gui.setScreen(new FifthNNDependencyScreen(this));
                    }
                }
        ).bounds(colR, y, bw, bh).build());

        // === PHASE 2: ONNX PURE NN ENGINE ===
        y += 32;
        String currentModelLabel = availableModels.isEmpty()
                ? "Model: §7(none in models/)"
                : "Model: §b" + availableModels.get(modelIndex);
        addRenderableWidget(Button.builder(
                Component.literal(currentModelLabel),
                btn -> {
                    availableModels = ai.listAvailableModels();
                    if (!availableModels.isEmpty()) {
                        modelIndex = (modelIndex + 1) % availableModels.size();
                        config.selectedModelFile = availableModels.get(modelIndex);
                        config.save();
                    }
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("§aLoad / Reload ONNX"),
                btn -> {
                    if (this.minecraft != null) {
                        availableModels = ai.listAvailableModels();
                        String file = availableModels.isEmpty() ? config.selectedModelFile : availableModels.get(modelIndex);
                        ai.loadSelectedModel(this.minecraft, file);
                        rebuildWidgets();
                    }
                }
        ).bounds(colR, y, bw, bh).build());

        y += 24;
        addRenderableWidget(Button.builder(
                Component.literal("AI Mode: §d" + ai.getMode().displayName),
                btn -> {
                    if (this.minecraft != null) {
                        ai.setMode(this.minecraft, ai.getMode().next());
                        rebuildWidgets();
                    }
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Local Safety Lock: " + (config.localServerSafetyLock ? "§aON" : "§cOFF")),
                btn -> {
                    config.localServerSafetyLock = !config.localServerSafetyLock;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 24;
        addRenderableWidget(Button.builder(
                Component.literal("Mouse GCD Smooth: " + (config.enableMouseGcdSmoothing ? "§aON" : "§7OFF")),
                btn -> {
                    config.enableMouseGcdSmoothing = !config.enableMouseGcdSmoothing;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("§c⚠ STOP AI (F10)"),
                btn -> {
                    if (this.minecraft != null) {
                        ai.emergencyStop(this.minecraft, "GUI Emergency Stop");
                        rebuildWidgets();
                    }
                }
        ).bounds(colR, y, 100, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> onClose()
        ).bounds(colR + 104, y, 46, bh).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int white = ARGB.opaque(0xFFFFFF);
        int gray = ARGB.opaque(0xB0B8C8);
        int cyan = ARGB.opaque(0x38BDF8);

        drawCentered(graphics, "§l5ifthNN v0.3 §r§7— Pure NN BedWars AI (MC 26.3 / 1.8 PvP)", cx, 10, white);
        drawCentered(graphics, "§7Phase 1: Zero-Config Auto-Recorder (F6=Highlight, F7=Scrap 5s/15s, F9=Manual)", cx, 23, cyan);
        drawCentered(graphics, "§7Phase 2: Optional ONNX Runtime Engine (~1.8M params)", cx, 130, cyan);

        String footer = String.format(
                "State: %s | Saved: %.1fm (%d matches) | ONNX: %s",
                recorder.getRecorderState().name(),
                recorder.getTotalSavedMinutes(),
                recorder.getTotalSavedMatches(),
                ai.getEngine().getLoadedModelName()
        );
        drawCentered(graphics, footer, cx, this.height - 16, gray);
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        Component comp = Component.literal(text);
        int w = this.font.width(comp);
        graphics.text(this.font, comp, centerX - (w / 2), y, color, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
