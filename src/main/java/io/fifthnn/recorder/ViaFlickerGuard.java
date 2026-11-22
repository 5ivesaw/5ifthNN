package io.fifthnn.recorder;

import io.fifthnn.FifthNNClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Coordinates the 3-tick (150ms) deferred BlockChangedAck queue for {@code BlockStatePredictionHandlerMixin}.
 *
 * Automatically disables itself if ViaFabricPlus is installed (zero conflict) or if
 * {@code config.enableViaFlickerGuard} is turned off.
 */
public final class ViaFlickerGuard {
    private static final int DEFER_TICKS = 3;
    private static final boolean VIA_FABRIC_PLUS_LOADED = FabricLoader.getInstance().isModLoaded("viafabricplus");

    private record PendingAck(BlockStatePredictionHandler handler, int seq, ClientLevel level, long releaseTick) {}

    private static final Deque<PendingAck> PENDING = new ArrayDeque<>(32);
    private static long tickClock = 0L;
    private static boolean replaying = false;

    private ViaFlickerGuard() {}

    public static boolean isViaFabricPlusLoaded() {
        return VIA_FABRIC_PLUS_LOADED;
    }

    public static boolean shouldDeferAck() {
        if (replaying || VIA_FABRIC_PLUS_LOADED) return false;
        var cfg = FifthNNClient.getConfig();
        return cfg != null && cfg.enableViaFlickerGuard;
    }

    public static void enqueueDeferredAck(BlockStatePredictionHandler handler, int seq, ClientLevel level) {
        if (handler == null || level == null) return;
        PENDING.addLast(new PendingAck(handler, seq, level, tickClock + DEFER_TICKS));
        if (PENDING.size() > 64) {
            PendingAck oldest = PENDING.pollFirst();
            if (oldest != null) replayNow(oldest);
        }
    }

    public static void onClientTick(ClientLevel currentLevel) {
        tickClock++;
        if (PENDING.isEmpty()) return;
        while (!PENDING.isEmpty()) {
            PendingAck head = PENDING.peekFirst();
            if (head.level() != currentLevel) {
                PENDING.pollFirst();
                continue;
            }
            if (tickClock >= head.releaseTick()) {
                PENDING.pollFirst();
                replayNow(head);
            } else {
                break;
            }
        }
    }

    public static void clear() {
        PENDING.clear();
    }

    private static void replayNow(PendingAck ack) {
        replaying = true;
        try {
            ack.handler().endPredictionsUpTo(ack.seq(), ack.level());
        } catch (Throwable ignored) {
        } finally {
            replaying = false;
        }
    }
}
