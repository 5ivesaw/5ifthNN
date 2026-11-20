package io.fifthnn.recorder;

import io.fifthnn.schema.FifthNNSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Captures sub-tick human inputs (raw mouse deltas, 1.8 PvP high-CPS clicks,
 * GUI slot clicks, hotbar presses) and directional sound radar events between 20Hz ticks.
 */
public final class InputTracker {
    private float accumMouseDx = 0.0f;
    private float accumMouseDy = 0.0f;
    private int leftClicksThisTick = 0;
    private int rightClicksThisTick = 0;
    private byte hotbarKeyPressed = -1;

    private byte guiClickSlot = -1; // -1=none, -2=close_gui, 0..53=slot
    private int guiClickButton = 0;
    private int guiClickType = 0;

    private float prevYaw = 0.0f;
    private float prevPitch = 0.0f;
    private boolean hasPrevRot = false;

    private static final class TimedSound {
        final FrameData.SoundEntry entry;
        int ttlTicks;

        TimedSound(FrameData.SoundEntry entry, int ttlTicks) {
            this.entry = entry;
            this.ttlTicks = ttlTicks;
        }
    }

    private final List<TimedSound> recentSounds = new ArrayList<>();

    public synchronized void onRawMouseMove(double dx, double dy) {
        accumMouseDx += (float) dx;
        accumMouseDy += (float) dy;
    }

    public synchronized void onMouseButtonPress(int button) {
        if (button == 0) {
            leftClicksThisTick++;
        } else if (button == 1) {
            rightClicksThisTick++;
        }
    }

    public synchronized void onHotbarSlotSelected(int slot) {
        if (slot >= 0 && slot <= 8) {
            hotbarKeyPressed = (byte) slot;
        }
    }

    public synchronized void onGuiSlotClick(int slotId, int button, int clickType) {
        if (slotId >= 0 && slotId < FifthNNSchema.MAX_GUI_SLOTS) {
            guiClickSlot = (byte) slotId;
            guiClickButton = button;
            guiClickType = clickType;
        }
    }

    public synchronized void onGuiClosed() {
        if (guiClickSlot == -1) {
            guiClickSlot = -2;
        }
    }

    public synchronized void onSoundPlayed(String soundIdPath, double x, double y, double z, float volume) {
        int cat = classifySound(soundIdPath);
        if (cat < 0) return;
        recentSounds.add(new TimedSound(
                new FrameData.SoundEntry(cat, (float) x, (float) y, (float) z, volume),
                20 // retain for 1 second so 20Hz policy sees directional threat cues
        ));
        if (recentSounds.size() > 24) {
            recentSounds.remove(0);
        }
    }

    private static int classifySound(String path) {
        if (path == null) return -1;
        if (path.contains("ender_dragon") || path.contains("wither.death")) return FifthNNSchema.SND_BED_DESTROY;
        if (path.contains("tnt.primed") || path.contains("creeper.primed")) return FifthNNSchema.SND_TNT_FUSE;
        if (path.contains("explode")) return FifthNNSchema.SND_EXPLOSION;
        if (path.contains("ghast.shoot") || path.contains("blaze.shoot") || path.contains("firecharge")) return FifthNNSchema.SND_FIREBALL;
        if (path.contains("ender_pearl") || path.contains("enderman.teleport")) return FifthNNSchema.SND_PEARL;
        if (path.contains("drink") || path.contains("splash_potion")) return FifthNNSchema.SND_POTION_DRINK;
        if (path.contains(".step")) return FifthNNSchema.SND_FOOTSTEP;
        if (path.contains(".place")) return FifthNNSchema.SND_BLOCK_PLACE;
        if (path.contains(".break")) return FifthNNSchema.SND_BLOCK_BREAK;
        if (path.contains("player.hurt") || path.contains("player.attack")) return FifthNNSchema.SND_HIT_HURT;
        return -1;
    }

    public synchronized void drainIntoFrame(FrameData fr, float curYaw, float curPitch) {
        fr.rawMouseDx = accumMouseDx;
        fr.rawMouseDy = accumMouseDy;
        fr.leftClicksThisTick = leftClicksThisTick;
        fr.rightClicksThisTick = rightClicksThisTick;
        fr.hotbarKeyPressed = hotbarKeyPressed;
        fr.guiClickSlot = guiClickSlot;
        fr.guiClickButton = guiClickButton;
        fr.guiClickType = guiClickType;

        if (!hasPrevRot) {
            prevYaw = curYaw;
            prevPitch = curPitch;
            hasPrevRot = true;
        }
        fr.prevYaw = prevYaw;
        fr.prevPitch = prevPitch;
        fr.deltaYaw = wrapDegrees(curYaw - prevYaw);
        fr.deltaPitch = curPitch - prevPitch;
        prevYaw = curYaw;
        prevPitch = curPitch;

        // Copy active directional sounds & tick TTL
        fr.sounds.clear();
        Iterator<TimedSound> it = recentSounds.iterator();
        while (it.hasNext()) {
            TimedSound ts = it.next();
            if (fr.sounds.size() < FifthNNSchema.MAX_SOUNDS_RAW) {
                fr.sounds.add(ts.entry);
            }
            ts.ttlTicks--;
            if (ts.ttlTicks <= 0) {
                it.remove();
            }
        }

        // Reset per-tick accumulators
        accumMouseDx = 0.0f;
        accumMouseDy = 0.0f;
        leftClicksThisTick = 0;
        rightClicksThisTick = 0;
        hotbarKeyPressed = -1;
        guiClickSlot = -1;
        guiClickButton = 0;
        guiClickType = 0;
    }

    public synchronized void reset() {
        accumMouseDx = 0.0f;
        accumMouseDy = 0.0f;
        leftClicksThisTick = 0;
        rightClicksThisTick = 0;
        hotbarKeyPressed = -1;
        guiClickSlot = -1;
        hasPrevRot = false;
        recentSounds.clear();
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}
