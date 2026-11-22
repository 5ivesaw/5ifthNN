package io.fifthnn.recorder;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Computes true finite-difference velocities for remote multiplayer entities.
 *
 * Why this matters: in Minecraft multiplayer, {@code RemotePlayer.getDeltaMovement()}
 * returns (0, 0, 0) on ~80% of ticks due to client position interpolation. Tracking
 * tick-to-tick position deltas recovers exact opponent strafing, rushing, knockback,
 * and fall velocities across every server.
 */
public final class EntityVelocityTracker {

    private static final class TrackState {
        double prevX, prevY, prevZ;
        double minX, maxX, minZ, maxZ;
        long lastTick;
        float vx, vy, vz;

        TrackState(double x, double y, double z, long tick) {
            this.prevX = x;
            this.prevY = y;
            this.prevZ = z;
            this.minX = x;
            this.maxX = x;
            this.minZ = z;
            this.maxZ = z;
            this.lastTick = tick;
        }
    }

    private final Map<Integer, TrackState> tracks = new HashMap<>(128);
    private long lastPruneTick = 0L;

    public synchronized void clear() {
        tracks.clear();
        lastPruneTick = 0L;
    }

    /**
     * Update tracker for an entity and return its best velocity estimate (blocks/tick).
     * Prefers finite-difference velocity when vanilla deltaMovement is near zero.
     */
    public synchronized float[] computeVelocity(Entity ent, long currentTick) {
        int id = ent.getId();
        double x = ent.getX();
        double y = ent.getY();
        double z = ent.getZ();
        Vec3 rawVel = ent.getDeltaMovement();

        TrackState st = tracks.get(id);
        if (st == null) {
            st = new TrackState(x, y, z, currentTick);
            st.vx = (float) rawVel.x;
            st.vy = (float) rawVel.y;
            st.vz = (float) rawVel.z;
            tracks.put(id, st);
            return new float[]{st.vx, st.vy, st.vz};
        }

        long dt = currentTick - st.lastTick;
        if (dt > 0 && dt <= 10) {
            double dx = x - st.prevX;
            double dy = y - st.prevY;
            double dz = z - st.prevZ;
            double stepDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Ignore server teleports (> 4 blocks/tick)
            if (stepDist / dt <= 4.0) {
                float fdx = (float) (dx / dt);
                float fdy = (float) (dy / dt);
                float fdz = (float) (dz / dt);

                // Use finite-difference when vanilla velocity is zeroed/stale, or blend slightly
                boolean rawZero = Math.abs(rawVel.x) < 1e-5 && Math.abs(rawVel.z) < 1e-5;
                if (rawZero) {
                    st.vx = fdx;
                    st.vy = Math.abs(rawVel.y) > 1e-5 ? (float) rawVel.y : fdy;
                    st.vz = fdz;
                } else {
                    st.vx = 0.5f * (float) rawVel.x + 0.5f * fdx;
                    st.vy = 0.5f * (float) rawVel.y + 0.5f * fdy;
                    st.vz = 0.5f * (float) rawVel.z + 0.5f * fdz;
                }

                st.minX = Math.min(st.minX, x);
                st.maxX = Math.max(st.maxX, x);
                st.minZ = Math.min(st.minZ, z);
                st.maxZ = Math.max(st.maxZ, z);
            } else {
                st.vx = 0.0f;
                st.vy = 0.0f;
                st.vz = 0.0f;
            }
            st.prevX = x;
            st.prevY = y;
            st.prevZ = z;
            st.lastTick = currentTick;
        } else if (dt > 10) {
            // Re-appeared after long occlusion
            st.prevX = x;
            st.prevY = y;
            st.prevZ = z;
            st.lastTick = currentTick;
            st.vx = (float) rawVel.x;
            st.vy = (float) rawVel.y;
            st.vz = (float) rawVel.z;
        }

        if (currentTick - lastPruneTick >= 100L) {
            pruneStale(currentTick);
            lastPruneTick = currentTick;
        }

        return new float[]{st.vx, st.vy, st.vz};
    }

    public synchronized double getHorizontalSpan(int entityId) {
        TrackState st = tracks.get(entityId);
        if (st == null) return 0.0;
        return Math.hypot(st.maxX - st.minX, st.maxZ - st.minZ);
    }

    private void pruneStale(long currentTick) {
        Iterator<Map.Entry<Integer, TrackState>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            if (currentTick - it.next().getValue().lastTick > 200L) {
                it.remove();
            }
        }
    }
}
