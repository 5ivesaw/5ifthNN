package io.fifthnn.recorder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Watches recently placed block coordinates for 15 ticks (750ms) to detect server-rejected
 * placements and ViaVersion 1-tick W->AIR->W block flickers.
 */
public final class PlacementFlickerMonitor {
    private static final int WATCH_WINDOW_TICKS = 15;
    private static final long ONE_MINUTE_MS = 60_000L;

    private static final class WatchedPlacement {
        final BlockPos pos;
        final long placedTick;
        boolean sawSolid;
        boolean flagged;

        WatchedPlacement(BlockPos pos, long placedTick) {
            this.pos = pos;
            this.placedTick = placedTick;
        }
    }

    private final Deque<WatchedPlacement> watched = new ArrayDeque<>(64);
    private final Map<Long, Long> selfBrokenUntilTick = new HashMap<>(64);
    private final Deque<Long> recentFlickerWallMs = new ArrayDeque<>(32);
    private int sessionFlickerCount = 0;
    private boolean highFlickerHintShown = false;

    public synchronized void reset() {
        watched.clear();
        selfBrokenUntilTick.clear();
        recentFlickerWallMs.clear();
        sessionFlickerCount = 0;
        highFlickerHintShown = false;
    }

    public synchronized void onBlockPlaced(BlockPos pos, long tick) {
        if (pos == null) return;
        watched.addLast(new WatchedPlacement(pos.immutable(), tick));
        while (watched.size() > 64) {
            watched.pollFirst();
        }
    }

    public synchronized void onBlockBrokenBySelf(BlockPos pos, long tick) {
        if (pos == null) return;
        selfBrokenUntilTick.put(pos.asLong(), tick + 25L);
    }

    /**
     * Inspect all recently placed blocks. Returns true if a non-mined block vanished
     * into AIR during this tick (ghost block or ViaVersion 1-tick flicker).
     */
    public synchronized boolean tickCheck(ClientLevel level, long currentTick) {
        if (level == null) return false;
        long nowMs = System.currentTimeMillis();
        while (!recentFlickerWallMs.isEmpty() && nowMs - recentFlickerWallMs.peekFirst() > ONE_MINUTE_MS) {
            recentFlickerWallMs.pollFirst();
        }
        selfBrokenUntilTick.entrySet().removeIf(e -> currentTick > e.getValue());

        boolean flickerThisTick = false;
        Iterator<WatchedPlacement> it = watched.iterator();
        while (it.hasNext()) {
            WatchedPlacement wp = it.next();
            long age = currentTick - wp.placedTick;
            if (age > WATCH_WINDOW_TICKS) {
                it.remove();
                continue;
            }
            if (!level.isLoaded(wp.pos)) continue;

            BlockState bs = level.getBlockState(wp.pos);
            boolean isAir = bs.isAir();
            if (!isAir) {
                wp.sawSolid = true;
            } else if (wp.sawSolid && !wp.flagged && age >= 1) {
                // Solid predicted block vanished back into AIR without self mining!
                if (!selfBrokenUntilTick.containsKey(wp.pos.asLong())) {
                    wp.flagged = true;
                    flickerThisTick = true;
                    sessionFlickerCount++;
                    recentFlickerWallMs.addLast(nowMs);
                }
            }
        }
        return flickerThisTick;
    }

    public synchronized int getSessionFlickerCount() {
        return sessionFlickerCount;
    }

    public synchronized boolean shouldShowHighFlickerHint() {
        if (highFlickerHintShown) return false;
        if (recentFlickerWallMs.size() >= 5) {
            highFlickerHintShown = true;
            return true;
        }
        return false;
    }
}
