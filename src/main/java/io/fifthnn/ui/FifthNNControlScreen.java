package io.fifthnn.ui;

import io.fifthnn.FifthNNClient;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.helper.LocalHelperManager;
import io.fifthnn.inference.AiController;
import io.fifthnn.recorder.DatasetRecorderManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

import java.util.ArrayList;
import java.util.List;

/**
 * Clean 3-Tab Native Minecraft 26.3 Control Panel GUI (v0.5.0, press F8).
 *
 * Tabs:
 *  - Recorder: Zero-config auto-recorder, HUD, Auto-Requeue, Share ZIP, Deps
 *  - Helpers:  1-click Permit Current Server + toggles for LMB, Edge-RMB, Auto W-Tap, Auto Blockhit
 *  - Phase 2 AI: ONNX model selector/loader, AI mode, safety lock, GCD smoothing, Emergency Stop
 */
public final class FifthNNControlScreen extends Screen {
    private static final String[] SERVER_PROFILES = {"auto", "local", "pika", "jartex", "mineberry", "hypixel", "custom"};
    private static int activeTab = 0; // 0=Recorder, 1=Helpers, 2=Phase 2 AI (remembers last tab)

    private final FifthNNConfig config;
    private final DatasetRecorderManager recorder;
    private final LocalHelperManager helperManager;
    private final AiController ai;
    private List<String> availableModels;
    private int modelIndex = 0;

    public FifthNNControlScreen() {
        super(Component.literal("5ifthNN Control Panel"));
        this.config = FifthNNClient.getConfig();
        this.recorder = FifthNNClient.getRecorder();
        this.helperManager = FifthNNClient.getHelperManager();
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

        // === TOP TAB BAR ===
        int tabY = 28;
        int tabW = 100;
        addRenderableWidget(Button.builder(
                Component.literal(activeTab == 0 ? "§b§l[ Recorder ]" : "§7Recorder"),
                btn -> { activeTab = 0; rebuildWidgets(); }
        ).bounds(cx - 155, tabY, tabW, 18).build());

        addRenderableWidget(Button.builder(
                Component.literal(activeTab == 1 ? "§a§l[ Helpers ]" : "§7Helpers"),
                btn -> { activeTab = 1; rebuildWidgets(); }
        ).bounds(cx - 50, tabY, tabW, 18).build());

        addRenderableWidget(Button.builder(
                Component.literal(activeTab == 2 ? "§d§l[ Phase 2 AI ]" : "§7Phase 2 AI"),
                btn -> { activeTab = 2; rebuildWidgets(); }
        ).bounds(cx + 55, tabY, tabW, 18).build());

        int y = 62;
        if (activeTab == 0) {
            buildRecorderTab(colL, colR, bw, bh, y);
        } else if (activeTab == 1) {
            buildHelpersTab(colL, colR, bw, bh, y);
        } else {
            buildAiTab(colL, colR, bw, bh, y);
        }

        // === BOTTOM BAR ===
        addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> onClose()
        ).bounds(cx - 50, this.height - 38, 100, 20).build());
    }

    private void buildRecorderTab(int colL, int colR, int bw, int bh, int y) {
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

        y += 26;
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

        y += 26;
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
                Component.literal("Auto-Requeue: " + (config.autoRequeueEnabled ? "§aON" : "§7OFF")),
                btn -> {
                    config.autoRequeueEnabled = !config.autoRequeueEnabled;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 26;
        addRenderableWidget(Button.builder(
                Component.literal("§bEval & Pack Dataset"),
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
    }

    private void buildHelpersTab(int colL, int colR, int bw, int bh, int y) {
        addRenderableWidget(Button.builder(
                Component.literal("Combat LMB Hold: " + (config.enableHoldToClickLmb ? "§aON" : "§7OFF")),
                btn -> {
                    config.enableHoldToClickLmb = !config.enableHoldToClickLmb;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Edge-Only RMB: " + (config.enableHoldToClickRmb ? "§aON" : "§7OFF")),
                btn -> {
                    config.enableHoldToClickRmb = !config.enableHoldToClickRmb;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 26;
        addRenderableWidget(Button.builder(
                Component.literal("Auto 1.8 W-Tap: " + (config.enableContextWTap ? "§aON" : "§7OFF")),
                btn -> {
                    config.enableContextWTap = !config.enableContextWTap;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build());

        addRenderableWidget(Button.builder(
                Component.literal("Auto Blockhit: " + (config.enableContextBlockhit ? "§aON" : "§7OFF")),
                btn -> {
                    config.enableContextBlockhit = !config.enableContextBlockhit;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());

        y += 26;
        // 1-Click Permit / Unpermit Current Server (no chat commands needed!)
        String curHost = getCurrentRemoteHost();
        boolean curWhitelisted = curHost != null && config.permittedHelperServers != null
                && config.permittedHelperServers.stream().anyMatch(s -> s.equalsIgnoreCase(curHost));
        String permitLabel;
        if (curHost == null) {
            permitLabel = "§aLocal / Singleplayer";
        } else if (curWhitelisted) {
            permitLabel = "§eUnpermit: §f" + trimHost(curHost);
        } else {
            permitLabel = "§aPermit: §f" + trimHost(curHost);
        }
        Button permitBtn = Button.builder(
                Component.literal(permitLabel),
                btn -> {
                    if (curHost == null) return;
                    if (config.permittedHelperServers == null) config.permittedHelperServers = new ArrayList<>();
                    if (curWhitelisted) {
                        config.permittedHelperServers.removeIf(s -> s.equalsIgnoreCase(curHost));
                    } else {
                        config.permittedHelperServers.add(curHost);
                    }
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colL, y, bw, bh).build();
        permitBtn.active = (curHost != null);
        addRenderableWidget(permitBtn);

        addRenderableWidget(Button.builder(
                Component.literal("15-TPS Sens Sync: " + (config.enableLocalSlowMoSensSync ? "§bON" : "§7OFF")),
                btn -> {
                    config.enableLocalSlowMoSensSync = !config.enableLocalSlowMoSensSync;
                    config.save();
                    rebuildWidgets();
                }
        ).bounds(colR, y, bw, bh).build());
    }

    private void buildAiTab(int colL, int colR, int bw, int bh, int y) {
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
        ).bounds(colR, y, bw, bh).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int white = ARGB.opaque(0xFFFFFF);
        int gray = ARGB.opaque(0xB0B8C8);
        int cyan = ARGB.opaque(0x38BDF8);

        drawCentered(graphics, "§l5ifthNN v0.5.1 §r§7— Pure NN BedWars AI (MC 26.3 / 1.8 PvP)", cx, 10, white);

        if (activeTab == 0) {
            drawCentered(graphics, "§7Zero-Config Auto-Recorder  |  F6 = Highlight 8s  |  F7 = Scrap 3s / 10s", cx, 50, cyan);
        } else if (activeTab == 1) {
            boolean allowed = helperManager != null && helperManager.areHelpersAllowed();
            String gateText = allowed
                    ? "§a● Helpers PERMITTED on this server §7(Zero extra keybinds — auto from normal play)"
                    : "§c● Helpers LOCKED on this server §7(Click Permit below if you have server permission)";
            drawCentered(graphics, gateText, cx, 50, white);
            drawCentered(graphics, "§7LMB: 9-12 CPS in combat  |  RMB: Edge-only side faces (never double-places!)", cx, 148, gray);
            drawCentered(graphics, "§7W-Tap & Blockhit: Auto 1-2t after sword hit in close 1.8 trades", cx, 160, gray);
        } else {
            drawCentered(graphics, "§7Phase 2 Optional ONNX Runtime Inference (~1.79M params, <1ms CPU)", cx, 50, cyan);
        }

        String footer = String.format(
                "Recorder: %s | Saved: %.1fm (%d matches) | ONNX: %s",
                recorder.getRecorderState().name(),
                recorder.getTotalSavedMinutes(),
                recorder.getTotalSavedMatches(),
                ai.getEngine().getLoadedModelName()
        );
        drawCentered(graphics, footer, cx, this.height - 14, gray);
    }

    private String getCurrentRemoteHost() {
        if (this.minecraft == null || this.minecraft.isLocalServer()) return null;
        ServerData srv = this.minecraft.getCurrentServer();
        if (srv == null || srv.ip == null || srv.ip.isBlank()) return null;
        String host = LocalHelperManager.extractHost(srv.ip);
        if (host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.equals("0.0.0.0") || host.equals("::1")) {
            return null;
        }
        return host;
    }

    private static String trimHost(String host) {
        return host.length() <= 16 ? host : host.substring(0, 15) + "…";
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
