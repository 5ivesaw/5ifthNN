package io.fifthnn.helper;

import com.mojang.blaze3d.platform.InputConstants;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.recorder.BedWarsStateTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Single centralized gatekeeper & coordinator for focused gameplay helpers (v0.5.0).
 *
 * Strictly enforces permitted-server safety:
 *  - On singleplayer, localhost/LAN, or an explicit IP in {@code config.permittedHelperServers},
 *    {@code allHelpersAllowed = true} and enabled helpers operate normally with ZERO extra keybinds.
 *  - On ANY non-permitted server (or unknown server / disconnect), {@code allHelpersAllowed = false}
 *    immediately disables every helper and restores true physical key states.
 */
public final class LocalHelperManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Helpers");

    private final FifthNNConfig config;
    private final ClickCadenceHelper clickHelper = new ClickCadenceHelper();
    private final CombatWeaveHelper combatHelper = new CombatWeaveHelper();

    private boolean allHelpersAllowed = false;
    private boolean slowMoSensApplied = false;
    private double savedBaseSensitivity = -1.0;
    private long helperTickClock = 0L;

    public LocalHelperManager(FifthNNConfig config) {
        this.config = config;
    }

    public static boolean isPermittedServer(Minecraft mc, FifthNNConfig cfg) {
        if (mc == null) return false;
        if (mc.isLocalServer()) return true;
        ServerData srv = mc.getCurrentServer();
        if (srv == null || srv.ip == null || srv.ip.isBlank()) return false;

        String host = extractHost(srv.ip);
        if (host.equals("localhost") || host.startsWith("127.") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.equals("0.0.0.0") || host.equals("::1")) {
            return true;
        }
        if (cfg != null && cfg.permittedHelperServers != null) {
            for (String allowed : cfg.permittedHelperServers) {
                if (allowed != null && !allowed.isBlank() && host.equalsIgnoreCase(extractHost(allowed))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String extractHost(String rawAddress) {
        if (rawAddress == null) return "";
        String ip = rawAddress.toLowerCase(Locale.ROOT).trim();
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            return close > 1 ? ip.substring(1, close) : ip;
        }
        int colon = ip.indexOf(':');
        int lastColon = ip.lastIndexOf(':');
        if (colon >= 0 && colon == lastColon) {
            return ip.substring(0, colon);
        }
        return ip;
    }

    /**
     * Queries the true physical hardware state of a KeyMapping on MC 26.3 (SDL3),
     * preventing virtual setDown overrides from leaving keys stuck released or held.
     */
    public static boolean isPhysicallyHeld(Minecraft mc, KeyMapping key) {
        if (mc == null || key == null) return false;
        InputConstants.Key bound = ((KeyMappingAccessor) key).fifthnn$getKey();
        if (bound == null || bound.equals(InputConstants.UNKNOWN)) return false;
        if (bound.getType() == InputConstants.Type.KEYBOARD) {
            return InputConstants.isKeyDown(bound.getValue());
        }
        if (bound.getType() == InputConstants.Type.MOUSE && mc.mouseHandler != null) {
            return switch (bound.getValue()) {
                case 0 -> mc.mouseHandler.isLeftPressed();
                case 1 -> mc.mouseHandler.isRightPressed();
                case 2 -> mc.mouseHandler.isMiddlePressed();
                default -> false;
            };
        }
        return false;
    }

    public boolean areHelpersAllowed() {
        return allHelpersAllowed;
    }

    public long getHelperTickClock() {
        return helperTickClock;
    }

    public void onClientTick(Minecraft mc, BedWarsStateTracker stateTracker, boolean aiActive) {
        helperTickClock++;
        if (mc == null || mc.player == null || mc.level == null || aiActive) {
            disableAll(mc, stateTracker);
            return;
        }

        boolean permitted = isPermittedServer(mc, config);
        if (!permitted) {
            if (allHelpersAllowed) {
                LOGGER.info("Non-permitted server detected — disabling all gameplay helpers.");
            }
            disableAll(mc, stateTracker);
            return;
        }
        allHelpersAllowed = true;

        updateSlowMoSync(mc, stateTracker);

        // Run CombatWeaveHelper FIRST so ClickCadenceHelper knows if a blockhit pulse/release is active!
        combatHelper.onClientTick(mc, config, stateTracker, helperTickClock);
        clickHelper.onClientTick(mc, config, stateTracker, combatHelper.isBlockhitBusy(helperTickClock), helperTickClock);
    }

    public void onMeleeAttack(Entity target) {
        if (!allHelpersAllowed) return;
        combatHelper.onMeleeAttack(helperTickClock, target);
    }

    public void onDisconnect(Minecraft mc, BedWarsStateTracker stateTracker) {
        disableAll(mc, stateTracker);
    }

    private void disableAll(Minecraft mc, BedWarsStateTracker stateTracker) {
        allHelpersAllowed = false;
        restoreSensitivityIfNeeded(mc, stateTracker);
        clickHelper.reset();
        combatHelper.reset(mc);
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
