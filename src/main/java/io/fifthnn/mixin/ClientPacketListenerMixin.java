package io.fifthnn.mixin;

import io.fifthnn.FifthNNClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks exact server position sync packets (ClientboundPlayerPositionPacket).
 *
 * Normal walking, jumping, bridging, knockback, and fireball/TNT jumps NEVER send this packet —
 * ONLY server teleports, Ender Pearl landings, and anti-cheat rubberbands do!
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void fifthnn$onServerPositionSync(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.isSameThread()) {
            var st = FifthNNClient.getStateTracker();
            if (st != null) {
                st.onServerPositionPacket();
            }
        }
    }
}
