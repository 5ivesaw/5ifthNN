package io.fifthnn.ui;

import io.fifthnn.FifthNNClient;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.inference.AiController;
import io.fifthnn.recorder.DatasetRecorderManager;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

import java.util.List;

/**
 * Native Minecraft 26.3 Control Panel GUI (GuiGraphicsExtractor / extractRenderState)
 * for selecting Phase 1 recording modes/tags and controlling Phase 2 ONNX inference & guardrails.
 */
public final class FifthNNControlScreen extends Screen {
    private static final String[] SERVER_PROFILES = {"local", "pika", "hypixel", "custom"};

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
        int y = 42;

        // === PHASE 1: DATASET RECORDER ===
        addRenderableWidget(Button.builder(
                Component.literal(recorder.isRecording() ? "§c■ Stop Recording (F9)" : "§a● Start Recording (F9)"),
                btn -> {
                    if (this.minecraft != null) {
                        recorder.toggleRecording(this.minecraft);
                        rebuildWidgets();
                    }
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Mode: §e" + config.getRecordingModeEnum().displayName),
                btn -> {
                    config.setRecordingModeEnum(config.getRecordingModeEnum().next());
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 26;
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
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("PvP Mode: " + (config.pvpMode18 ? "§a1.8 BedWars" : "§7Modern")),
                btn -> {
                    config.pvpMode18 = !config.pvpMode18;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 26;
        addRenderableWidget(Button.builder(
                Component.literal("Filter Lobby/Spec: " + (config.autoTagLobbyInvalid ? "§aON" : "§cOFF")),
                btn -> {
                    config.autoTagLobbyInvalid = !config.autoTagLobbyInvalid;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("HUD Overlay: " + (config.showHudOverlay ? "§aON" : "§7OFF")),
                btn -> {
                    config.showHudOverlay = !config.showHudOverlay;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        // === PHASE 2: ONNX PURE NN ENGINE ===
        y += 38;
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

        y += 26;
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

        y += 26;
        addRenderableWidget(Button.builder(
                Component.literal("Max Human CPS: §e" + config.maxHumanCps),
                btn -> {
                    config.maxHumanCps = (config.maxHumanCps >= 14) ? 8 : (config.maxHumanCps + 1);
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal(String.format("Temp: §e%.2f", config.samplingTemperature)),
                btn -> {
                    config.samplingTemperature = (config.samplingTemperature >= 1.15f) ? 0.50f : (config.samplingTemperature + 0.15f);
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 30;
        addRenderableWidget(Button.builder(
                Component.literal("§c⚠ EMERGENCY STOP AI (F10)"),
                btn -> {
                    if (this.minecraft != null) {
                        ai.emergencyStop(this.minecraft, "GUI Emergency Stop");
                        rebuildWidgets();
                    }
                }
        ).bounds(colL, y, 210, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> onClose()
        ).bounds(colL + 220, y, 90, bh).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int white = ARGB.opaque(0xFFFFFF);
        int gray = ARGB.opaque(0xB0B8C8);
        int cyan = ARGB.opaque(0x38BDF8);

        drawCentered(graphics, "§l5ifthNN §r§7— Pure NN BedWars AI (MC 26.3 / 1.8 PvP)", cx, 12, white);
        drawCentered(graphics, "§7Phase 1: 1:1 Structured Dataset Recorder", cx, 28, cyan);
        drawCentered(graphics, "§8" + config.getRecordingModeEnum().description, cx, 118, gray);
        drawCentered(graphics, "§7Phase 2: Embedded ONNX Runtime Engine (~2.0M params)", cx, 134, cyan);

        String footer = String.format(
                "Recorder: %s | ONNX: %s (%.2f ms)",
                recorder.isRecording()
                        ? ("REC " + recorder.getValidFramesWritten() + " valid ticks")
                        : recorder.getLastStatusMessage(),
                ai.getEngine().getLoadedModelName(),
                ai.getEngine().getEmaLatencyMs()
        );
        drawCentered(graphics, footer, cx, this.height - 20, gray);
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
