package io.fifthnn.helper;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.FrameData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Single centralized gatekeeper & coordinator for ALL active local training helpers (v0.4.0).
 *
 * Strictly enforces permitted-server safety:
 *  - On singleplayer, localhost/LAN, or an explicit IP in {@code config.permittedHelperServers},
 *    {@code allHelpersAllowed = true} and enabled helper modules operate normally.
 *  - On ANY non-permitted public server (or on disconnect), {@code allHelpersAllowed = false}
 *    immediately disables every active helper module and releases all virtual inputs.
 */
public final class LocalHelperManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Helpers");

    private final FifthNNConfig config;
    private final HotbarHelper hotbarHelper = new HotbarHelper();
    private final ClickCadenceHelper clickHelper = new ClickCadenceHelper();
    private final CombatWeaveHelper combatHelper = new CombatWeaveHelper();
    private final ShopAndBridgeHelper shopBridgeHelper = new ShopAndBridgeHelper();

    private boolean allHelpersAllowed = false;
    private boolean slowMoSensApplied = false;
    private double savedBaseSensitivity = -1.0;

    public LocalHelperManager(FifthNNConfig config) {
        this.config = config;
    }

    public static boolean isPermittedServer(Minecraft mc, FifthNNConfig cfg) {
        if (mc == null) return false;
        if (mc.isLocalServer()) return true;
        ServerData srv = mc.getCurrentServer();
        if (srv == null || srv.ip == null) return true;
        String ip = srv.ip.toLowerCase(Locale.ROOT).trim();
        int colon = ip.indexOf(':');
        String host = colon >= 0 ? ip.substring(0, colon) : ip;

        if (host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.equals("0.0.0.0") || host.equals("::1")) {
            return true;
        }
        if (cfg != null && cfg.permittedHelperServers != null) {
            for (String allowed : cfg.permittedHelperServers) {
                if (allowed != null && !allowed.isBlank() && host.equalsIgnoreCase(allowed.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean areHelpersAllowed() {
        return allHelpersAllowed;
    }

    public HotbarHelper getHotbarHelper() { return hotbarHelper; }
    public ClickCadenceHelper getClickHelper() { return clickHelper; }
    public CombatWeaveHelper getCombatHelper() { return combatHelper; }
    public ShopAndBridgeHelper getShopBridgeHelper() { return shopBridgeHelper; }

    public void onClientTick(Minecraft mc, BedWarsStateTracker stateTracker, FrameData lastFrame, boolean aiActive) {
        if (mc == null || mc.player == null || mc.level == null || aiActive) {
            disableAll(mc, stateTracker);
            return;
        }

        boolean permitted = isPermittedServer(mc, config);
        if (!permitted) {
            if (allHelpersAllowed) {
                LOGGER.info("Non-permitted server detected — disabling all local training helpers.");
            }
            disableAll(mc, stateTracker);
            return;
        }
        allHelpersAllowed = true;

        // Local 15 TPS Slow-Mo 0.75x sensitivity sync + TPS lag-spike floor adjustment
        updateSlowMoSync(mc, stateTracker);

        long tick = stateTracker != null ? stateTracker.getTrackerTickClock() : 0L;
        hotbarHelper.onClientTick(mc, config, stateTracker, tick);
        clickHelper.onClientTick(mc, config, stateTracker, tick);
        combatHelper.onClientTick(mc, config, stateTracker, tick);
        shopBridgeHelper.onClientTick(mc, config, stateTracker, lastFrame, tick);
    }

    public void onBlockPlaced(long tick) {
        if (!allHelpersAllowed) return;
        hotbarHelper.onBlockPlaced(tick);
        shopBridgeHelper.onBlockPlaced(tick);
    }

    public void onMeleeAttack(long tick) {
        if (!allHelpersAllowed) return;
        combatHelper.onMeleeAttack(tick);
    }

    public void onDisconnect(Minecraft mc, BedWarsStateTracker stateTracker) {
        disableAll(mc, stateTracker);
    }

    private void disableAll(Minecraft mc, BedWarsStateTracker stateTracker) {
        allHelpersAllowed = false;
        restoreSensitivityIfNeeded(mc, stateTracker);
        hotbarHelper.reset();
        clickHelper.reset(mc);
        combatHelper.reset(mc);
        shopBridgeHelper.reset(mc);
    }

    private void updateSlowMoSync(Minecraft mc, BedWarsStateTracker stateTracker) {
        boolean wantSlowMo = config.enableLocalSlowMoSensSync && allHelpersAllowed;
        if (stateTracker != null) {
            stateTracker.setSlowMoDrillMode(wantSlowMo);
        }
        if (mc.options == null) return;
        if (wantSlowMo && !slowMoSensApplied) {
            savedBaseSensitivity = mc.options.sensitivity().get();
            mc.options.sensitivity().set(Math.max(0.0, Math.min(1.0, savedBaseSensitivity * 0.75)));
            slowMoSensApplied = true;
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§b[5ifthNN] Local 15-TPS Slow-Mo sens sync ON (0.75x)"));
            }
        } else if (!wantSlowMo && slowMoSensApplied) {
            restoreSensitivityIfNeeded(mc, stateTracker);
        }
    }

    private void restoreSensitivityIfNeeded(Minecraft mc, BedWarsStateTracker stateTracker) {
        if (stateTracker != null) {
            stateTracker.setSlowMoDrillMode(false);
        }
        if (slowMoSensApplied && mc != null && mc.options != null && savedBaseSensitivity >= 0.0) {
            mc.options.sensitivity().set(savedBaseSensitivity);
        }
        slowMoSensApplied = false;
        savedBaseSensitivity = -1.0;
    }
}
