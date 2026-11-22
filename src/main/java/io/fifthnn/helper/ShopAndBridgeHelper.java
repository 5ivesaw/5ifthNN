package io.fifthnn.helper;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.FrameData;
import io.fifthnn.recorder.WorldSnapshotCapture;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Step 5: Optional Helpers (Default OFF, strictly gated by {@link LocalHelperManager}):
 *  - Human-Paced Shop Quick-Buy Preset (GUI_ITEM_SHOP only, zero WASD held, 3-6t spacing, desync abort)
 *  - Post-Place Speed-Bridge Unsneak Assist (t+1 or t+2 after EVT_BLOCK_PLACED, 1-2t max, OFF on jump/air)
 */
public final class ShopAndBridgeHelper {
    private final Random rng = new Random();

    // Shop Quick-Buy queue
    private final Deque<Integer> queuedItemCats = new ArrayDeque<>();
    private long nextShopClickTick = -1L;

    // Post-Place Speed-Bridge Unsneak state
    private long pendingUnsneakStartTick = -1L;
    private int unsneakTicksRemaining = 0;

    public void reset(Minecraft mc) {
        queuedItemCats.clear();
        nextShopClickTick = -1L;
        pendingUnsneakStartTick = -1L;
        unsneakTicksRemaining = 0;
    }

    public void onBlockPlaced(long tick) {
        // Trigger on t+1 or t+2 (never fixed 100% t+1!)
        pendingUnsneakStartTick = tick + 1L + rng.nextInt(2);
    }

    /** Queue a basic rush kit (Wool -> Stone Sword -> Golden Apple) when BedWars Item Shop is open. */
    public void triggerQuickBuyRushKit(Minecraft mc, FifthNNConfig cfg, long tick) {
        if (!cfg.enableShopQuickBuy || mc == null || mc.player == null) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) return;
        queuedItemCats.clear();
        queuedItemCats.addLast(FifthNNSchema.ITEM_WOOL);
        queuedItemCats.addLast(FifthNNSchema.ITEM_STONE_SWORD);
        queuedItemCats.addLast(FifthNNSchema.ITEM_GOLDEN_APPLE);
        nextShopClickTick = tick + 2L;
    }

    public void onClientTick(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, FrameData lastFrame, long tick) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.options == null) return;

        // 1. Human-Paced Shop Quick-Buy Preset
        if (!queuedItemCats.isEmpty()) {
            boolean stationary = !mc.options.keyUp.isDown() && !mc.options.keyDown.isDown()
                    && !mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown();
            if (!cfg.enableShopQuickBuy || !(mc.gui.screen() instanceof AbstractContainerScreen<?> acs)
                    || !stationary || (lastFrame != null && lastFrame.guiType != FifthNNSchema.GUI_ITEM_SHOP)) {
                queuedItemCats.clear();
            } else if (tick >= nextShopClickTick && mc.gameMode != null) {
                int wantCat = queuedItemCats.pollFirst();
                AbstractContainerMenu menu = acs.getMenu();
                int slotIdx = findShopSlotByCat(menu, wantCat);
                if (slotIdx >= 0) {
                    mc.gameMode.handleContainerInput(menu.containerId, slotIdx, 0, ContainerInput.PICKUP, p);
                }
                nextShopClickTick = tick + 3L + rng.nextInt(4); // 3-6 ticks (150-300ms) realistic travel!
            }
        }

        // 2. Optional Post-Place Speed-Bridge Unsneak Assist (clamped strictly to 1-2 ticks, OFF on jump/air!)
        if (!cfg.enableBridgeUnsneak || mc.gui.screen() != null || mc.options.keyJump.isDown() || !p.onGround()) {
            pendingUnsneakStartTick = -1L;
            unsneakTicksRemaining = 0;
            return;
        }

        BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ());
        boolean solidUnder = !mc.level.getBlockState(under).isAir();

        if (pendingUnsneakStartTick >= 0 && tick >= pendingUnsneakStartTick) {
            pendingUnsneakStartTick = -1L;
            boolean bridgingMove = mc.options.keyDown.isDown() && (mc.options.keyLeft.isDown() || mc.options.keyRight.isDown());
            if (bridgingMove && mc.options.keyShift.isDown() && solidUnder) {
                unsneakTicksRemaining = 1 + rng.nextInt(2); // strictly 1-2 ticks (50-100ms, never 4t!)
            }
        }

        if (unsneakTicksRemaining > 0) {
            if (!solidUnder) {
                unsneakTicksRemaining = 0; // Safety cancel if center block under feet is AIR!
            } else {
                mc.options.keyShift.setDown(false);
                unsneakTicksRemaining--;
            }
        }
    }

    private static int findShopSlotByCat(AbstractContainerMenu menu, int targetCat) {
        int limit = Math.min(menu.slots.size(), 54);
        for (int i = 0; i < limit; i++) {
            if (WorldSnapshotCapture.classifyItem(menu.slots.get(i).getItem()) == targetCat) {
                return i;
            }
        }
        return -1;
    }
}
