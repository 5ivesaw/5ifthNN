package io.fifthnn.mixin;

import io.fifthnn.FifthNNClient;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures directional BedWars sound cues (footsteps, TNT fuse, fireball, pearl, bed break)
 * for the Phase 1 sound radar.
 *
 * Injects at RETURN (after SoundEngine.play calls sound.resolve()) so AbstractSoundInstance.sound
 * is initialized before getVolume() is read.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play", at = @At("RETURN"))
    private void fifthnn$captureSound(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (sound == null) return;
        try {
            var tracker = FifthNNClient.getInputTracker();
            if (tracker == null || sound.getIdentifier() == null) return;
            float vol = sound.getSound() != null ? sound.getVolume() : 1.0f;
            tracker.onSoundPlayed(
                    sound.getIdentifier().getPath(),
                    sound.getX(),
                    sound.getY(),
                    sound.getZ(),
                    vol
            );
        } catch (Throwable ignored) {
            // Never allow passive sound telemetry to crash the Minecraft client
        }
    }
}
