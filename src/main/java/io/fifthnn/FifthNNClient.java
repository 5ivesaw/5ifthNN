package io.fifthnn;

import com.mojang.blaze3d.platform.InputConstants;
import io.fifthnn.command.FifthNNCommands;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.inference.AiController;
import io.fifthnn.inference.HumanActionExecutor;
import io.fifthnn.inference.OnnxPolicyEngine;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.DatasetRecorderManager;
import io.fifthnn.recorder.FrameData;
import io.fifthnn.recorder.InputTracker;
import io.fifthnn.ui.FifthNNControlScreen;
import io.fifthnn.ui.FifthNNHudOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 5ifthNN Entrypoint (Minecraft Java 26.3 / Fabric).
 *
 * Phase 1: Lossless 1:1 structured dataset recorder with hotkeys, GUI, commands & failsafes.
 * Phase 2: Embedded lightweight ONNX Runtime inference engine + HG-DAgger live takeover.
 */
public final class FifthNNClient implements ClientModInitializer {
    public static final String MOD_ID = "fifthnn";
    public static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN");

    private static FifthNNConfig config;
    private static InputTracker inputTracker;
    private static BedWarsStateTracker stateTracker;
    private static DatasetRecorderManager recorder;
    private static OnnxPolicyEngine onnxEngine;
    private static HumanActionExecutor actionExecutor;
    private static AiController aiController;

    private static KeyMapping keyToggleRecord;
    private static KeyMapping keyOpenGui;
    private static KeyMapping keyToggleAi;
    private static KeyMapping keyDaggerTakeover;

    private int lastSelectedHotbar = -1;
    private boolean wasContainerOpen = false;

    @Override
    public void onInitializeClient() {
        config = FifthNNConfig.load(FabricLoader.getInstance().getConfigDir());
        inputTracker = new InputTracker();
        stateTracker = new BedWarsStateTracker();
        recorder = new DatasetRecorderManager(config, inputTracker, stateTracker);
        onnxEngine = new OnnxPolicyEngine();
        actionExecutor = new HumanActionExecutor();
        aiController = new AiController(config, onnxEngine, actionExecutor);

        registerKeyMappings();
        registerHud();
        registerCommands();
        registerEvents();

        // JVM shutdown failsafe: guarantees GZIP stream flush even on abrupt exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (recorder != null) recorder.onClientShutdown();
            if (onnxEngine != null) onnxEngine.close();
        }, "5ifthNN-ShutdownHook"));

        LOGGER.info("5ifthNN initialized (MC 26.3) | Recordings: {} | Models: {}",
                config.getRecordingsDir(), config.getModelsDir());
    }

    private void registerKeyMappings() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "controls")
        );
        keyToggleRecord = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.toggle_record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category
        ));
        keyOpenGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.open_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, category
        ));
        keyToggleAi = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.toggle_ai", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, category
        ));
        keyDaggerTakeover = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.dagger_takeover", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_ALT, category
        ));
    }

    private void registerHud() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                FifthNNHudOverlay.HUD_ID,
                FifthNNHudOverlay::render
        );
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                FifthNNCommands.register(dispatcher)
        );
    }

    private void registerEvents() {
        // Capture sub-tick 1.8 PvP click counts right before Minecraft consumes them
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.options == null) return;
            int leftClicks = ((KeyMappingAccessor) mc.options.keyAttack).fifthnn$getClickCount();
            int rightClicks = ((KeyMappingAccessor) mc.options.keyUse).fifthnn$getClickCount();
            for (int i = 0; i < leftClicks; i++) inputTracker.onMouseButtonPress(0);
            for (int i = 0; i < rightClicks; i++) inputTracker.onMouseButtonPress(1);

            int curSlot = mc.player.getInventory().getSelectedSlot();
            if (lastSelectedHotbar >= 0 && curSlot != lastSelectedHotbar) {
                inputTracker.onHotbarSlotSelected(curSlot);
            }
            lastSelectedHotbar = curSlot;

            boolean containerOpen = mc.screen instanceof AbstractContainerScreen<?>;
            if (wasContainerOpen && !containerOpen) {
                inputTracker.onGuiClosed();
            }
            wasContainerOpen = containerOpen;
        });

        // Main 20 Hz tick loop: hotkeys -> Phase 1 capture -> Phase 2 ONNX inference
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (keyToggleRecord.consumeClick()) {
                recorder.toggleRecording(mc);
            }
            while (keyOpenGui.consumeClick()) {
                if (mc.screen instanceof FifthNNControlScreen) {
                    mc.setScreen(null);
                } else {
                    mc.setScreen(new FifthNNControlScreen());
                }
            }
            while (keyToggleAi.consumeClick()) {
                aiController.toggleAi(mc);
            }

            boolean aiActive = aiController.isAiEnabled();
            boolean takeoverHeld = aiActive && keyDaggerTakeover.isDown();

            FrameData frame = recorder.onClientTick(mc, aiActive, takeoverHeld);
            if (aiActive) {
                aiController.onClientTick(mc, frame, takeoverHeld);
            }
        });

        // Track block placements, block breaks, and melee attacks for player-placed block detection
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() && hitResult != null) {
                stateTracker.markBlockPlaced(hitResult.getBlockPos().relative(hitResult.getDirection()));
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() && pos != null) {
                stateTracker.markBlockBroken(pos);
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide()) {
                stateTracker.markDamageDealt();
            }
            return InteractionResult.PASS;
        });

        // Chat & system event parser for BedWars milestones (beds, kills, final kills, upgrades, win/loss)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Minecraft mc = Minecraft.getInstance();
            String self = mc.player != null ? mc.player.getGameProfile().name() : "";
            stateTracker.onChatMessage(message.getString(), self);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            Minecraft mc = Minecraft.getInstance();
            String self = mc.player != null ? mc.player.getGameProfile().name() : "";
            stateTracker.onChatMessage(message.getString(), self);
        });

        // Clean disconnect & shutdown failsafes
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            aiController.onDisconnect(mc);
            recorder.onDisconnect(mc);
            lastSelectedHotbar = -1;
            wasContainerOpen = false;
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            aiController.emergencyStop(mc, "Client closing");
            recorder.onClientShutdown();
            onnxEngine.close();
        });
    }

    public static FifthNNConfig getConfig() { return config; }
    public static InputTracker getInputTracker() { return inputTracker; }
    public static BedWarsStateTracker getStateTracker() { return stateTracker; }
    public static DatasetRecorderManager getRecorder() { return recorder; }
    public static AiController getAiController() { return aiController; }
}
