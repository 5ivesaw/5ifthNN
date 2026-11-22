package io.fifthnn;

import com.mojang.blaze3d.platform.InputConstants;
import io.fifthnn.command.FifthNNCommands;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.helper.LocalHelperManager;
import io.fifthnn.inference.AiController;
import io.fifthnn.inference.HumanActionExecutor;
import io.fifthnn.inference.OnnxPolicyEngine;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.DatasetRecorderManager;
import io.fifthnn.recorder.FrameData;
import io.fifthnn.recorder.InputTracker;
import io.fifthnn.recorder.ViaFabricPlusBridge;
import io.fifthnn.recorder.ViaFlickerGuard;
import io.fifthnn.schema.FifthNNSchema;
import io.fifthnn.ui.FifthNNControlScreen;
import io.fifthnn.ui.FifthNNDependencyScreen;
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
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 5ifthNN Entrypoint (Minecraft Java 26.3 / Fabric, v0.4.0, GPL-3.0).
 *
 * Phase 1: Zero-config 1:1 BedWars auto-recorder with 40-tick lagback/flicker ring buffer,
 *          F6/F7 tagging, `/5nn share`, auto-requeue, ViaFabricPlus 1.8 auto-config & flicker guard.
 * Local Helpers: Single centralized {@link LocalHelperManager} gated strictly to local/permitted servers.
 * Phase 2: Optional ONNX Runtime inference engine + HG-DAgger live takeover.
 */
public final class FifthNNClient implements ClientModInitializer {
    public static final String MOD_ID = "fifthnn";
    public static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN");

    private static FifthNNConfig config;
    private static InputTracker inputTracker;
    private static BedWarsStateTracker stateTracker;
    private static DatasetRecorderManager recorder;
    private static LocalHelperManager helperManager;
    private static OnnxPolicyEngine onnxEngine;
    private static HumanActionExecutor actionExecutor;
    private static AiController aiController;

    // Core recorder & Phase 2 AI keybinds
    private static KeyMapping keyHighlight;
    private static KeyMapping keyScrap;
    private static KeyMapping keyToggleRecord;
    private static KeyMapping keyOpenGui;
    private static KeyMapping keyToggleAi;
    private static KeyMapping keyDaggerTakeover;

    private int lastSelectedHotbar = -1;
    private boolean wasContainerOpen = false;
    private boolean autoDepScreenHandledThisSession = false;

    @Override
    public void onInitializeClient() {
        config = FifthNNConfig.load(FabricLoader.getInstance().getConfigDir());
        inputTracker = new InputTracker();
        stateTracker = new BedWarsStateTracker();
        recorder = new DatasetRecorderManager(config, inputTracker, stateTracker);
        helperManager = new LocalHelperManager(config);
        onnxEngine = new OnnxPolicyEngine();
        actionExecutor = new HumanActionExecutor();
        aiController = new AiController(config, onnxEngine, actionExecutor);

        registerKeyMappings();
        registerHud();
        registerCommands();
        registerTitleScreenPrompt();
        registerEvents();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (recorder != null) recorder.onClientShutdown();
            if (onnxEngine != null) onnxEngine.close();
        }, "5ifthNN-ShutdownHook"));

        LOGGER.info("5ifthNN v0.5.0 initialized (MC 26.3) | ViaFabricPlus={} | ONNX={} | Recordings={}",
                ViaFlickerGuard.isViaFabricPlusLoaded(), OnnxPolicyEngine.isRuntimeAvailable(), config.getRecordingsDir());
    }

    private void registerKeyMappings() {
        KeyMapping.Category coreCat = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "controls")
        );
        keyHighlight = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.highlight", InputConstants.Type.KEYBOARD, InputConstants.KEY_F6, coreCat
        ));
        keyScrap = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.scrap", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, coreCat
        ));
        keyOpenGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.open_gui", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, coreCat
        ));
        keyToggleRecord = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.toggle_record", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, coreCat
        ));
        keyToggleAi = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.toggle_ai", InputConstants.Type.KEYBOARD, InputConstants.KEY_F10, coreCat
        ));
        keyDaggerTakeover = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fifthnn.dagger_takeover", InputConstants.Type.KEYBOARD, InputConstants.KEY_RALT, coreCat
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

    private void registerTitleScreenPrompt() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ViaFabricPlusBridge.ensureDefault18(config);
            if (!(screen instanceof TitleScreen)) return;
            if (!FifthNNDependencyScreen.hasAnyMissingRecommendation(config)) return;

            Screens.getWidgets(screen).add(Button.builder(
                    Component.literal("§e⚠ 5ifthNN Deps"),
                    btn -> client.gui.setScreen(new FifthNNDependencyScreen(screen))
            ).bounds(scaledWidth - 102, 6, 96, 18).build());

            if (!autoDepScreenHandledThisSession && FifthNNDependencyScreen.shouldShowAutoPrompt(config)) {
                autoDepScreenHandledThisSession = true;
                client.execute(() -> client.gui.setScreen(new FifthNNDependencyScreen(screen)));
            }
        });
    }

    private void registerEvents() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.options == null) return;
            int leftClicks = ((KeyMappingAccessor) mc.options.keyAttack).fifthnn$getClickCount();
            int rightClicks = ((KeyMappingAccessor) mc.options.keyUse).fifthnn$getClickCount();
            for (int i = 0; i < leftClicks; i++) inputTracker.onMouseButtonPress(0);
            for (int i = 0; i < rightClicks; i++) inputTracker.onMouseButtonPress(1);
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ViaFlickerGuard.onClientTick(mc.level);

            // Detect hotbar slot changes & container closes at END_CLIENT_TICK (AFTER handleKeybinds()!)
            // right before recorder.onClientTick so BOTH manual and helper swaps align on exact action tick t+1!
            if (mc.player != null) {
                int curSlot = mc.player.getInventory().getSelectedSlot();
                if (lastSelectedHotbar >= 0 && curSlot != lastSelectedHotbar) {
                    inputTracker.onHotbarSlotSelected(curSlot);
                }
                lastSelectedHotbar = curSlot;

                boolean containerOpen = mc.gui.screen() instanceof AbstractContainerScreen<?>;
                if (wasContainerOpen && !containerOpen) {
                    inputTracker.onGuiClosed();
                }
                wasContainerOpen = containerOpen;
            }

            while (keyHighlight.consumeClick()) recorder.onHighlightKey(mc);
            while (keyScrap.consumeClick()) recorder.onScrapKey(mc);
            while (keyToggleRecord.consumeClick()) recorder.toggleRecording(mc);
            while (keyOpenGui.consumeClick()) {
                if (mc.gui.screen() instanceof FifthNNControlScreen) {
                    mc.gui.setScreen(null);
                } else {
                    mc.gui.setScreen(new FifthNNControlScreen());
                }
            }
            while (keyToggleAi.consumeClick()) aiController.toggleAi(mc);

            boolean aiActive = aiController.isAiEnabled();
            boolean takeoverHeld = aiActive && keyDaggerTakeover.isDown();

            FrameData frame = recorder.onClientTick(mc, aiActive, takeoverHeld);
            helperManager.onClientTick(mc, stateTracker, aiActive);

            if (aiActive) {
                aiController.onClientTick(mc, frame, takeoverHeld);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() && hitResult != null && player.getItemInHand(hand).getItem() instanceof BlockItem) {
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
                helperManager.onMeleeAttack(entity);
            }
            return InteractionResult.PASS;
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Minecraft mc = Minecraft.getInstance();
            String self = mc.player != null ? mc.player.getGameProfile().name() : "";
            stateTracker.onChatMessage(message.getString(), self);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) ->
                ViaFabricPlusBridge.ensureDefault18(config)
        );
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            aiController.onDisconnect(mc);
            helperManager.onDisconnect(mc, stateTracker);
            recorder.onDisconnect(mc);
            lastSelectedHotbar = -1;
            wasContainerOpen = false;
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            aiController.emergencyStop(mc, "Client closing");
            helperManager.onDisconnect(mc, stateTracker);
            recorder.onClientShutdown();
            onnxEngine.close();
        });
    }

    public static FifthNNConfig getConfig() { return config; }
    public static InputTracker getInputTracker() { return inputTracker; }
    public static BedWarsStateTracker getStateTracker() { return stateTracker; }
    public static DatasetRecorderManager getRecorder() { return recorder; }
    public static LocalHelperManager getHelperManager() { return helperManager; }
    public static AiController getAiController() { return aiController; }
}
