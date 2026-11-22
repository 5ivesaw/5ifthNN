package io.fifthnn.helper;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.WorldSnapshotCapture;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;

/**
 * Step 2: Discrete Hotbar & Pre-Swap Helpers (Tier S — zero causal leak, zero anti-cheat risk).
 *
 * Includes:
 *  - Smart Item-Type Hotkeys (Sword, Blocks, Gapple, Pearl, Fireball, KB Stick)
 *  - Anticipatory "Prep Break" Key (strictly vanilla mc.hitResult <= 4.5b)
 *  - Falling Clutch-Item Quick-Swap (Water Bucket -> Ladder -> Wool)
 *  - Empty-Block-Stack Auto-Next-Stack Swap (only after EVT_BLOCK_PLACED, delayed 1-2 ticks!)
 *  - Post-Consume Return-to-Sword (only on completed consumption, delayed 1-2 ticks!)
 */
public final class HotbarHelper {
    private final Random rng = new Random();

    private long lastHotkeyTick = -99L;

    // Empty-block-stack refill state (only armed after EVT_BLOCK_PLACED!)
    private long lastBlockPlacedTick = -999L;
    private int pendingRefillSlot = -1;
    private long pendingRefillAtTick = -1L;

    // Post-consume return-to-sword state (only on completed consumption!)
    private boolean wasUsingConsumable = false;
    private int consumeSlot = -1;
    private int consumeStartCount = 0;
    private int slotBeforeConsume = -1;
    private long pendingSwordReturnAtTick = -1L;

    public void reset() {
        lastBlockPlacedTick = -999L;
        pendingRefillSlot = -1;
        pendingRefillAtTick = -1L;
        wasUsingConsumable = false;
        consumeSlot = -1;
        consumeStartCount = 0;
        slotBeforeConsume = -1;
        pendingSwordReturnAtTick = -1L;
    }

    public void onBlockPlaced(long tick) {
        lastBlockPlacedTick = tick;
    }

    public void onClientTick(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            pendingRefillSlot = -1;
            pendingSwordReturnAtTick = -1L;
            return;
        }
        Inventory inv = player.getInventory();
        int curSlot = inv.getSelectedSlot();

        // 1. Empty-Block-Stack Auto-Next-Stack Swap (armed ONLY after EVT_BLOCK_PLACED, delayed 1-2 ticks!)
        if (cfg.enableBlockRefillSwap) {
            if (pendingRefillSlot >= 0 && tick >= pendingRefillAtTick) {
                if (inv.getItem(curSlot).isEmpty() && isPlaceableBlock(WorldSnapshotCapture.classifyItem(inv.getItem(pendingRefillSlot)))) {
                    selectSlotSafe(inv, pendingRefillSlot);
                }
                pendingRefillSlot = -1;
            } else if (pendingRefillSlot < 0 && (tick - lastBlockPlacedTick) <= 2L && inv.getItem(curSlot).isEmpty()) {
                int nextBlockSlot = findBestBlockSlot(inv, curSlot);
                if (nextBlockSlot >= 0) {
                    pendingRefillSlot = nextBlockSlot;
                    pendingRefillAtTick = tick + 1L + rng.nextInt(2); // t+1 or t+2 (no 0-tick leak!)
                }
            }
        }

        // 2. Post-Consume Return-to-Sword (ONLY on completed consumption where stack count decremented!)
        if (cfg.enablePostConsumeSwap) {
            if (pendingSwordReturnAtTick >= 0 && tick >= pendingSwordReturnAtTick) {
                pendingSwordReturnAtTick = -1L;
                if (inv.getSelectedSlot() == consumeSlot) {
                    int swordSlot = findBestSwordSlot(inv);
                    if (swordSlot >= 0 && swordSlot != curSlot) {
                        selectSlotSafe(inv, swordSlot);
                    }
                }
            }

            boolean usingNow = player.isUsingItem();
            int curCat = WorldSnapshotCapture.classifyItem(inv.getItem(curSlot));
            boolean holdingConsumable = isConsumable(curCat);

            if (usingNow && holdingConsumable) {
                if (!wasUsingConsumable) {
                    wasUsingConsumable = true;
                    consumeSlot = curSlot;
                    consumeStartCount = inv.getItem(curSlot).getCount();
                }
            } else if (wasUsingConsumable) {
                wasUsingConsumable = false;
                // Verify consumption COMPLETED (count decremented or slot became empty/glass bottle — NOT canceled!)
                int afterCount = consumeSlot >= 0 ? inv.getItem(consumeSlot).getCount() : 0;
                int afterCat = consumeSlot >= 0 ? WorldSnapshotCapture.classifyItem(inv.getItem(consumeSlot)) : 0;
                boolean completed = (afterCount < consumeStartCount) || !isConsumable(afterCat);
                if (completed && curSlot == consumeSlot) {
                    pendingSwordReturnAtTick = tick + 1L + rng.nextInt(2); // 1-2 tick human delay!
                }
            } else if (!holdingConsumable) {
                slotBeforeConsume = curSlot;
            }
        }
    }

    /** Smart Item-Type Hotkey: Sword (Diamond > Iron > Stone > Wood). */
    public void selectBestSword(Minecraft mc, FifthNNConfig cfg, long tick) {
        if (!canTriggerHotkey(mc, cfg.enableSmartHotbarKeys, tick)) return;
        Inventory inv = mc.player.getInventory();
        int target = findBestSwordSlot(inv);
        selectSlotSafe(inv, target);
    }

    /** Smart Item-Type Hotkey: Blocks (Wool -> Terracotta -> Wood -> Endstone -> Glass -> Obsidian; no-op if already holding). */
    public void selectBestBlocks(Minecraft mc, FifthNNConfig cfg, long tick) {
        if (!canTriggerHotkey(mc, cfg.enableSmartHotbarKeys, tick)) return;
        Inventory inv = mc.player.getInventory();
        int curCat = WorldSnapshotCapture.classifyItem(inv.getItem(inv.getSelectedSlot()));
        if (isPlaceableBlock(curCat)) return; // Clutch-safe no-op!
        int target = findBestBlockSlot(inv, -1);
        selectSlotSafe(inv, target);
    }

    /** Smart Item-Type Hotkey: Gapple / Pearl / Fireball / KB Stick. */
    public void selectItemCategory(Minecraft mc, FifthNNConfig cfg, int targetCat, long tick) {
        if (!canTriggerHotkey(mc, cfg.enableSmartHotbarKeys, tick)) return;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (WorldSnapshotCapture.classifyItem(inv.getItem(i)) == targetCat) {
                selectSlotSafe(inv, i);
                return;
            }
        }
    }

    /** Falling Clutch-Item Quick-Swap: Water Bucket -> Ladder (on vertical face) -> Wool fallback. */
    public void selectClutchItem(Minecraft mc, FifthNNConfig cfg, long tick) {
        if (!canTriggerHotkey(mc, cfg.enableSmartHotbarKeys, tick)) return;
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();
        int curCat = WorldSnapshotCapture.classifyItem(inv.getItem(inv.getSelectedSlot()));
        if (curCat == FifthNNSchema.ITEM_WATER_BUCKET || curCat == FifthNNSchema.ITEM_WOOL) return;

        int water = findSlotByCat(inv, FifthNNSchema.ITEM_WATER_BUCKET);
        if (water >= 0) {
            selectSlotSafe(inv, water);
            return;
        }
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getDirection().getAxis() != Direction.Axis.Y) {
            int ladder = findSlotByCat(inv, FifthNNSchema.ITEM_LADDER);
            if (ladder >= 0) {
                selectSlotSafe(inv, ladder);
                return;
            }
        }
        int blocks = findBestBlockSlot(inv, -1);
        selectSlotSafe(inv, blocks);
    }

    /**
     * Anticipatory "Prep Break" Key: strictly reads vanilla mc.hitResult (<= 4.5b) and swaps to
     * Shears (wool), best Axe (wood), or best Pickaxe (endstone/terracotta/obsidian).
     */
    public void prepBreakTarget(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        if (!canTriggerHotkey(mc, cfg.enablePrepBreakKey, tick)) return;
        if (mc.level == null) return;
        HitResult hr = mc.hitResult;
        if (hr instanceof EntityHitResult) return; // Combat interrupt: never swap to pickaxe on enemy!
        if (!(hr instanceof BlockHitResult bhr)) return;

        BlockState bs = mc.level.getBlockState(bhr.getBlockPos());
        int packed = WorldSnapshotCapture.classifyPackedVoxel(bs, bhr.getBlockPos(), st, st.getOwnTeamId());
        int bcat = packed & 0x3F;
        if (bcat == FifthNNSchema.BLOCK_BED_ENEMY || bcat == FifthNNSchema.BLOCK_BED_OWN) return;

        Inventory inv = mc.player.getInventory();
        int target = -1;
        if (bcat == FifthNNSchema.BLOCK_WOOL) {
            target = findSlotByCat(inv, FifthNNSchema.ITEM_SHEARS);
        } else if (bcat == FifthNNSchema.BLOCK_WOOD_PLANKS) {
            target = findBestAxeSlot(inv);
        } else if (bcat == FifthNNSchema.BLOCK_ENDSTONE || bcat == FifthNNSchema.BLOCK_TERRACOTTA
                || bcat == FifthNNSchema.BLOCK_OBSIDIAN || bcat == FifthNNSchema.BLOCK_SOLID_MAP) {
            target = findBestPickaxeSlot(inv);
        }
        selectSlotSafe(inv, target);
    }

    private boolean canTriggerHotkey(Minecraft mc, boolean flagEnabled, long tick) {
        if (!flagEnabled || mc == null || mc.player == null || mc.gui.screen() != null) return false;
        if (tick - lastHotkeyTick < 1L) return false;
        lastHotkeyTick = tick;
        return true;
    }

    public static void selectSlotSafe(Inventory inv, int targetSlot) {
        if (targetSlot < 0 || targetSlot > 8) return;
        if (inv.getSelectedSlot() == targetSlot) return; // Never emit redundant same-slot packet!
        inv.setSelectedSlot(targetSlot);
    }

    public static int findSlotByCat(Inventory inv, int cat) {
        for (int i = 0; i < 9; i++) {
            if (WorldSnapshotCapture.classifyItem(inv.getItem(i)) == cat) return i;
        }
        return -1;
    }

    public static int findBestSwordSlot(Inventory inv) {
        int[] order = {
                FifthNNSchema.ITEM_DIAMOND_SWORD, FifthNNSchema.ITEM_IRON_SWORD,
                FifthNNSchema.ITEM_STONE_SWORD, FifthNNSchema.ITEM_WOOD_SWORD
        };
        for (int cat : order) {
            int s = findSlotByCat(inv, cat);
            if (s >= 0) return s;
        }
        return -1;
    }

    public static int findBestAxeSlot(Inventory inv) {
        int[] order = {
                FifthNNSchema.ITEM_DIAMOND_AXE, FifthNNSchema.ITEM_IRON_AXE,
                FifthNNSchema.ITEM_STONE_AXE, FifthNNSchema.ITEM_WOOD_AXE
        };
        for (int cat : order) {
            int s = findSlotByCat(inv, cat);
            if (s >= 0) return s;
        }
        return -1;
    }

    public static int findBestPickaxeSlot(Inventory inv) {
        int[] order = {
                FifthNNSchema.ITEM_DIAMOND_PICKAXE, FifthNNSchema.ITEM_GOLD_PICKAXE,
                FifthNNSchema.ITEM_IRON_PICKAXE, FifthNNSchema.ITEM_WOOD_PICKAXE
        };
        for (int cat : order) {
            int s = findSlotByCat(inv, cat);
            if (s >= 0) return s;
        }
        return -1;
    }

    public static int findBestBlockSlot(Inventory inv, int excludeSlot) {
        int[] order = {
                FifthNNSchema.ITEM_WOOL, FifthNNSchema.ITEM_TERRACOTTA,
                FifthNNSchema.ITEM_WOOD_PLANKS, FifthNNSchema.ITEM_ENDSTONE,
                FifthNNSchema.ITEM_BLASTPROOF_GLASS, FifthNNSchema.ITEM_OBSIDIAN
        };
        for (int cat : order) {
            for (int i = 0; i < 9; i++) {
                if (i == excludeSlot) continue;
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && WorldSnapshotCapture.classifyItem(st) == cat) return i;
            }
        }
        return -1;
    }

    public static boolean isPlaceableBlock(int cat) {
        return cat == FifthNNSchema.ITEM_WOOL || cat == FifthNNSchema.ITEM_TERRACOTTA
                || cat == FifthNNSchema.ITEM_WOOD_PLANKS || cat == FifthNNSchema.ITEM_ENDSTONE
                || cat == FifthNNSchema.ITEM_BLASTPROOF_GLASS || cat == FifthNNSchema.ITEM_OBSIDIAN
                || cat == FifthNNSchema.ITEM_LADDER;
    }

    private static boolean isConsumable(int cat) {
        return cat == FifthNNSchema.ITEM_GOLDEN_APPLE || cat == FifthNNSchema.ITEM_POTION_SPEED
                || cat == FifthNNSchema.ITEM_POTION_JUMP || cat == FifthNNSchema.ITEM_POTION_INVIS;
    }
}
