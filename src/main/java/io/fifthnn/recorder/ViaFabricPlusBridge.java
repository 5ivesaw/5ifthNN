package io.fifthnn.recorder;

import io.fifthnn.config.FifthNNConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Zero-dependency reflection bridge for ViaFabricPlus (when installed).
 *
 * Automatically sets ViaFabricPlus's global default target protocol to Minecraft 1.8.x
 * so friends never have to configure per-server versions manually for BedWars.
 */
public final class ViaFabricPlusBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Via");
    private static boolean appliedThisSession = false;

    private ViaFabricPlusBridge() {}

    public static void ensureDefault18(FifthNNConfig config) {
        if (appliedThisSession || config == null || !config.autoSetViaFabricPlus18) return;
        if (!ViaFlickerGuard.isViaFabricPlusLoaded()) return;

        try {
            Class<?> implCls = Class.forName("com.viaversion.viafabricplus.ViaFabricPlusImpl");
            Object impl = implCls.getMethod("impl").invoke(null);
            if (impl == null) return;

            Object pt = implCls.getMethod("protocolTranslation").invoke(impl);
            if (pt == null) return;

            Class<?> pvCls = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object v18 = pvCls.getField("v1_8").get(null);
            if (v18 == null) return;

            Method setTarget = pt.getClass().getMethod("setTargetVersion", pvCls, boolean.class);
            setTarget.invoke(pt, v18, false);
            appliedThisSession = true;
            LOGGER.info("Auto-configured ViaFabricPlus default target version to 1.8.x");
        } catch (Throwable t) {
            // ViaFabricPlus may still be finishing async init; will retry on next screen/join
            LOGGER.debug("ViaFabricPlus 1.8 auto-config deferred: {}", t.getMessage());
        }
    }
}
