package io.fifthnn.ui;

import io.fifthnn.FifthNNClient;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.inference.OnnxPolicyEngine;
import io.fifthnn.recorder.ViaFlickerGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smart, no-auto-download, no-spam recommended dependency screen for 5ifthNN (v0.3.0).
 *
 * - Never shows if ViaFabricPlus is installed (and ONNX Runtime isn't needed yet).
 * - Otherwise shows once on first launch, then at most once every 14 days (or never if suppressed),
 *   explaining the exact 1.8 BedWars bugs and giving both Official Modrinth link + Copy Name buttons.
 */
public final class FifthNNDependencyScreen extends Screen {
    public static final String VIA_MODRINTH_URL = "https://modrinth.com/mod/viafabricplus";
    public static final String VIA_MODRINTH_NAME = "ViaFabricPlus";
    public static final String VIA_RECOMMENDED_VERSION = "5.1.3+ (MC 26.3 Fabric)";
    private static final long COOLDOWN_MS = 14L * 24L * 60L * 60L * 1000L;

    private final Screen parent;
    private String clipboardNotice = null;

    public FifthNNDependencyScreen(Screen parent) {
        super(Component.literal("5ifthNN Recommended Setup"));
        this.parent = parent;
    }

    public static boolean isMissingViaFabricPlus() {
        return !ViaFlickerGuard.isViaFabricPlusLoaded();
    }

    public static boolean isMissingOnnxForModels(FifthNNConfig cfg) {
        if (OnnxPolicyEngine.isRuntimeAvailable() || cfg == null) return false;
        Path modelsDir = cfg.getModelsDir();
        if (modelsDir == null || !Files.exists(modelsDir)) return false;
        try (var stream = Files.list(modelsDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".onnx"));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean hasAnyMissingRecommendation(FifthNNConfig cfg) {
        return isMissingViaFabricPlus() || isMissingOnnxForModels(cfg);
    }

    public static boolean shouldShowAutoPrompt(FifthNNConfig cfg) {
        if (cfg == null || cfg.suppressDependencyPrompt) return false;
        if (!hasAnyMissingRecommendation(cfg)) return false;
        long now = System.currentTimeMillis();
        return cfg.lastDependencyPromptMs == 0L || (now - cfg.lastDependencyPromptMs) >= COOLDOWN_MS;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = this.height / 2 + 24;

        if (isMissingViaFabricPlus()) {
            addRenderableWidget(Button.builder(
                    Component.literal("§aOpen Official Modrinth (ViaFabricPlus)"),
                    btn -> {
                        if (this.minecraft != null) {
                            ConfirmLinkScreen.confirmLinkNow(this, VIA_MODRINTH_URL);
                        }
                    }
            ).bounds(cx - 155, y, 200, 20).build());

            addRenderableWidget(Button.builder(
                    Component.literal("§bCopy Name"),
                    btn -> {
                        if (this.minecraft != null) {
                            this.minecraft.keyboardHandler.setClipboard(VIA_MODRINTH_NAME);
                            clipboardNotice = "§aCopied \"ViaFabricPlus\" to clipboard! Search it on Modrinth / Prism.";
                        }
                    }
            ).bounds(cx + 50, y, 105, 20).build());
            y += 26;
        }

        addRenderableWidget(Button.builder(
                Component.literal("Continue to Minecraft"),
                btn -> {
                    FifthNNConfig cfg = FifthNNClient.getConfig();
                    if (cfg != null) {
                        cfg.lastDependencyPromptMs = System.currentTimeMillis();
                        cfg.save();
                    }
                    onClose();
                }
        ).bounds(cx - 155, y + 6, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("§7Don't Remind Me"),
                btn -> {
                    FifthNNConfig cfg = FifthNNClient.getConfig();
                    if (cfg != null) {
                        cfg.suppressDependencyPrompt = true;
                        cfg.lastDependencyPromptMs = System.currentTimeMillis();
                        cfg.save();
                    }
                    onClose();
                }
        ).bounds(cx + 5, y + 6, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = Math.max(24, this.height / 2 - 96);

        int white = ARGB.opaque(0xFFFFFF);
        int yellow = ARGB.opaque(0xFACC15);
        int gray = ARGB.opaque(0xCBD5E1);
        int cyan = ARGB.opaque(0x38BDF8);
        int green = ARGB.opaque(0x4ADE80);

        drawCentered(graphics, "§l5ifthNN — Recommended BedWars Setup (MC 26.3)", cx, top, white);

        int y = top + 20;
        if (isMissingViaFabricPlus()) {
            drawCentered(graphics, "§eRecommended Mod: §f" + VIA_MODRINTH_NAME + " §7(" + VIA_RECOMMENDED_VERSION + ")", cx, y, yellow);
            y += 14;
            drawCentered(graphics, "Why: 1.8 BedWars servers (Pika/Jartex/Hypixel) send out-of-order block acks to MC 26.3,", cx, y, gray);
            y += 11;
            drawCentered(graphics, "causing 1-tick bridging flickers, water-clutch swim lagbacks & sneak hitbox desyncs.", cx, y, gray);
            y += 13;
            drawCentered(graphics, "Set your BedWars server to §b1.8.x §7in ViaFabricPlus for native 1.8 bridging feel.", cx, y, cyan);
            y += 12;
            drawCentered(graphics, "§a(5ifthNN's built-in Flicker Guard is ON as a safe fallback even without it.)", cx, y, green);
            y += 16;
        }

        if (isMissingOnnxForModels(FifthNNClient.getConfig())) {
            drawCentered(graphics, "§ePhase 2 AI Models Found: §7Install §f5ifthNN-OnnxRuntime-1.32.0.jar §7once in mods/ to run .onnx AI.", cx, y, yellow);
            y += 14;
        }

        if (clipboardNotice != null) {
            drawCentered(graphics, clipboardNotice, cx, this.height - 24, green);
        } else {
            drawCentered(graphics, "§85ifthNN never auto-downloads files. This notice only reappears every 14 days.", cx, this.height - 24, gray);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        Component comp = Component.literal(text);
        int w = this.font.width(comp);
        graphics.text(this.font, comp, centerX - (w / 2), y, color, true);
    }

    @Override
    public void onClose() {
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        mc.gui.setScreen(parent);
    }
}
