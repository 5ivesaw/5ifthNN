package io.fifthnn.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.fifthnn.schema.FifthNNSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Crash-safe, zero-stutter asynchronous binary writer for `.5nn.gz` datasets.
 *
 * Guarantees:
 *  - Client tick thread NEVER blocks on disk I/O (serializes to byte[] + non-blocking enqueue).
 *  - Sync-flushes GZIP stream every N ticks (default 40 = 2s) with FRM1 + length framing so
 *    even a hard JVM crash or power outage preserves all previously flushed frames.
 *  - Monitors free disk space & session byte caps; stops gracefully before filling disk.
 */
public final class BinaryDatasetWriter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Writer");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final byte[] POISON_PILL = new byte[0];

    private final Path filePath;
    private final Path metaPath;
    private final Map<String, Object> headerMeta;
    private final int flushIntervalTicks;
    private final long maxSessionBytes;
    private final long minFreeDiskBytes;

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(512);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicLong framesWritten = new AtomicLong(0);
    private final AtomicLong validFramesWritten = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private final AtomicLong bytesWrittenEst = new AtomicLong(0);

    private volatile String failureReason = null;
    private final Thread writerThread;

    public BinaryDatasetWriter(
            Path filePath,
            Map<String, Object> headerMeta,
            int flushIntervalTicks,
            long maxSessionSizeMb,
            long minFreeDiskMb
    ) throws IOException {
        this.filePath = filePath;
        this.metaPath = Path.of(filePath.toString() + ".meta.json");
        this.headerMeta = new LinkedHashMap<>(headerMeta);
        this.flushIntervalTicks = Math.max(10, flushIntervalTicks);
        this.maxSessionBytes = Math.max(32L, maxSessionSizeMb) * 1024L * 1024L;
        this.minFreeDiskBytes = Math.max(256L, minFreeDiskMb) * 1024L * 1024L;

        Files.createDirectories(filePath.getParent());
        checkDiskSpace();

        this.writerThread = new Thread(this::runWriterLoop, "5ifthNN-DatasetWriter");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    private void checkDiskSpace() throws IOException {
        FileStore store = Files.getFileStore(filePath.getParent());
        long usable = store.getUsableSpace();
        if (usable < minFreeDiskBytes) {
            throw new IOException("Low disk space: " + (usable / (1024 * 1024)) + " MB free (min required: "
                    + (minFreeDiskBytes / (1024 * 1024)) + " MB)");
        }
    }

    /**
     * Serialize frame on caller thread and enqueue without blocking disk I/O.
     * Returns false if writer failed, disk full, or session cap reached.
     */
    public boolean enqueueFrame(FrameData fr) {
        if (!running.get() || failed.get()) {
            return false;
        }
        try {
            byte[] payload = serializeFramePayload(fr);
            if (!queue.offer(payload)) {
                droppedFrames.incrementAndGet();
                return true;
            }
            if ((fr.qualityFlags & FifthNNSchema.QFLAG_VALID_IN_GAME) != 0) {
                validFramesWritten.incrementAndGet();
            }
            return true;
        } catch (IOException e) {
            failed.set(true);
            failureReason = "Frame serialization error: " + e.getMessage();
            LOGGER.error(failureReason, e);
            return false;
        }
    }

    private void runWriterLoop() {
        try (var rawOut = Files.newOutputStream(filePath);
             var bufOut = new BufferedOutputStream(rawOut, 65536);
             var gzOut = new GZIPOutputStream(bufOut, 65536, true);
             var dos = new DataOutputStream(gzOut)) {

            // Write file header (5NN1 + schemaVersion + JSON metadata)
            dos.write(FifthNNSchema.FILE_MAGIC);
            dos.writeShort(FifthNNSchema.SCHEMA_VERSION);
            byte[] hdrBytes = GSON.toJson(headerMeta).getBytes(StandardCharsets.UTF_8);
            dos.writeInt(hdrBytes.length);
            dos.write(hdrBytes);
            dos.flush();

            long sinceFlush = 0;
            while (true) {
                byte[] payload = queue.poll(500, TimeUnit.MILLISECONDS);
                if (payload == null) {
                    if (!running.get()) break;
                    continue;
                }
                if (payload == POISON_PILL) {
                    break;
                }

                dos.write(FifthNNSchema.FRAME_MAGIC);
                dos.writeInt(payload.length);
                dos.write(payload);

                long count = framesWritten.incrementAndGet();
                sinceFlush++;

                if (sinceFlush >= flushIntervalTicks) {
                    dos.flush();
                    sinceFlush = 0;

                    long fileSize = Files.exists(filePath) ? Files.size(filePath) : 0L;
                    bytesWrittenEst.set(fileSize);

                    // Periodic failsafe checks (every ~10s)
                    if (count % (flushIntervalTicks * 5L) == 0) {
                        checkDiskSpace();
                        if (fileSize >= maxSessionBytes) {
                            failureReason = "Session reached max file size cap (" + (maxSessionBytes / (1024 * 1024)) + " MB)";
                            failed.set(true);
                            LOGGER.warn(failureReason);
                            break;
                        }
                    }
                }
            }

            dos.flush();
            gzOut.finish();
            bufOut.flush();
            bytesWrittenEst.set(Files.exists(filePath) ? Files.size(filePath) : bytesWrittenEst.get());
        } catch (Exception e) {
            failed.set(true);
            failureReason = e.getMessage();
            LOGGER.error("5ifthNN writer thread failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
            writeMetaSidecar();
        }
    }

    private void writeMetaSidecar() {
        try {
            Map<String, Object> meta = new LinkedHashMap<>(headerMeta);
            meta.put("endTimestampMs", System.currentTimeMillis());
            meta.put("framesWritten", framesWritten.get());
            meta.put("validInGameFrames", validFramesWritten.get());
            meta.put("droppedFrames", droppedFrames.get());
            meta.put("compressedBytes", bytesWrittenEst.get());
            meta.put("cleanShutdown", !failed.get());
            if (failureReason != null) {
                meta.put("stopReason", failureReason);
            }
            Files.writeString(metaPath, GSON.toJson(meta), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to write sidecar metadata {}: {}", metaPath, e.getMessage());
        }
    }

    /**
     * Exact big-endian frame layout synchronized with python/fifthnn/dataset_io.py.
     */
    public static byte[] serializeFramePayload(FrameData fr) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(3072);
        DataOutputStream dos = new DataOutputStream(baos);

        // 1. Frame Meta (>qqiBBhf) — Schema v2 adds episodeId for clean multi-game segmentation
        dos.writeLong(fr.tickIndex);
        dos.writeLong(fr.timestampMs);
        dos.writeInt(fr.episodeId);
        dos.writeByte(fr.matchPhase & 0xFF);
        dos.writeByte(fr.qualityFlags & 0xFF);
        dos.writeShort(fr.pingMs);
        dos.writeFloat(fr.serverTpsEst);

        // 2. Self State (>ddd15fHBBH)
        dos.writeDouble(fr.x);
        dos.writeDouble(fr.y);
        dos.writeDouble(fr.z);
        dos.writeFloat(fr.vx);
        dos.writeFloat(fr.vy);
        dos.writeFloat(fr.vz);
        dos.writeFloat(fr.yaw);
        dos.writeFloat(fr.pitch);
        dos.writeFloat(fr.prevYaw);
        dos.writeFloat(fr.prevPitch);
        dos.writeFloat(fr.eyeHeight);
        dos.writeFloat(fr.fallDist);
        dos.writeFloat(fr.hp);
        dos.writeFloat(fr.absorption);
        dos.writeFloat(fr.hunger);
        dos.writeFloat(fr.swingProgress);
        dos.writeFloat(fr.attackCooldown);
        dos.writeFloat(fr.attackSpeedAttr);
        dos.writeShort(fr.selfFlags & 0xFFFF);
        dos.writeByte(fr.hurtTime & 0xFF);
        dos.writeByte(fr.ownTeamId & 0xFF);
        dos.writeShort(fr.effectFlags & 0xFFFF);

        // 3. Crosshair Target (>BfiiiBBBfi)
        dos.writeByte(fr.hitType & 0xFF);
        dos.writeFloat(fr.hitDist);
        dos.writeInt(fr.hitBx);
        dos.writeInt(fr.hitBy);
        dos.writeInt(fr.hitBz);
        dos.writeByte(fr.hitFace & 0xFF);
        dos.writeByte(fr.hitBlockCat & 0xFF);
        dos.writeByte(fr.hitFlags & 0xFF);
        dos.writeFloat(fr.miningProgress);
        dos.writeInt(fr.hitEntityId);

        // 4. 1:1 Local Voxel Cube (1,183 bytes)
        dos.write(fr.voxels, 0, FifthNNSchema.RAW_VOXEL_COUNT);

        // 5. 32-Ray Panoramic Lidar (32 x >fBB)
        for (int i = 0; i < FifthNNSchema.LIDAR_RAY_COUNT; i++) {
            dos.writeFloat(fr.lidarDist[i]);
            dos.writeByte(fr.lidarCat[i] & 0xFF);
            dos.writeByte(fr.lidarFlags[i] & 0xFF);
        }

        // 6. Tracked Landmarks
        int lmCount = Math.min(fr.landmarks.size(), FifthNNSchema.MAX_LANDMARKS_RAW);
        dos.writeByte(lmCount);
        for (int i = 0; i < lmCount; i++) {
            var lm = fr.landmarks.get(i);
            dos.writeByte(lm.type() & 0xFF);
            dos.writeByte(lm.teamId() & 0xFF);
            dos.writeFloat(lm.x());
            dos.writeFloat(lm.y());
            dos.writeFloat(lm.z());
            dos.writeByte(lm.aliveFlag() & 0xFF);
        }

        // 7. Visible Players
        int pCount = Math.min(fr.players.size(), FifthNNSchema.MAX_PLAYERS_RAW);
        dos.writeByte(pCount);
        for (int i = 0; i < pCount; i++) {
            var p = fr.players.get(i);
            dos.writeInt(p.entityId());
            dos.writeInt(p.uuidHash());
            dos.writeByte(p.teamId() & 0xFF);
            dos.writeByte(p.flags() & 0xFF);
            dos.writeFloat(p.x());
            dos.writeFloat(p.y());
            dos.writeFloat(p.z());
            dos.writeFloat(p.vx());
            dos.writeFloat(p.vy());
            dos.writeFloat(p.vz());
            dos.writeFloat(p.yaw());
            dos.writeFloat(p.pitch());
            dos.writeFloat(p.hpEst());
            dos.writeByte(p.mainHandCat() & 0xFF);
            dos.writeByte(p.offHandCat() & 0xFF);
            dos.writeByte(p.armorTierSum() & 0xFF);
        }

        // 8. World Threats & Dropped Items
        int tCount = Math.min(fr.threats.size(), FifthNNSchema.MAX_THREATS_RAW);
        dos.writeByte(tCount);
        for (int i = 0; i < tCount; i++) {
            var t = fr.threats.get(i);
            dos.writeByte(t.type() & 0xFF);
            dos.writeFloat(t.x());
            dos.writeFloat(t.y());
            dos.writeFloat(t.z());
            dos.writeFloat(t.vx());
            dos.writeFloat(t.vy());
            dos.writeFloat(t.vz());
            dos.writeFloat(t.extra());
        }

        // 9. Inventory & Economy (>10hB6B + 41 x >BBB)
        for (int i = 0; i < 10; i++) {
            dos.writeShort(fr.econ[i]);
        }
        dos.writeByte(fr.selectedSlot & 0xFF);
        for (int i = 0; i < 6; i++) {
            dos.writeByte(fr.gearTiers[i] & 0xFF);
        }
        for (int i = 0; i < FifthNNSchema.INVENTORY_SLOT_COUNT; i++) {
            dos.writeByte(fr.invItemCat[i] & 0xFF);
            dos.writeByte(fr.invCount[i] & 0xFF);
            dos.writeByte(fr.invEnchantFlags[i] & 0xFF);
        }

        // 10. GUI / Container State (>BBHB + N x >BBB)
        int gCount = Math.max(0, Math.min(fr.guiSlotCount, FifthNNSchema.MAX_GUI_SLOTS));
        dos.writeByte(fr.guiType & 0xFF);
        dos.writeByte(fr.shopTab & 0xFF);
        dos.writeShort(fr.affordableMask & 0xFFFF);
        dos.writeByte(gCount);
        for (int i = 0; i < gCount; i++) {
            dos.writeByte(fr.guiItemCat[i] & 0xFF);
            dos.writeByte(fr.guiCount[i] & 0xFF);
            dos.writeByte(fr.guiFlags[i] & 0xFF);
        }

        // 11. Match & Scoreboard State (>BBB5BBBiH)
        dos.writeByte(fr.ownBedAlive & 0xFF);
        dos.writeByte(fr.enemyBedsAliveMask & 0xFF);
        dos.writeByte(fr.enemiesAliveCount & 0xFF);
        for (int i = 0; i < 5; i++) {
            dos.writeByte(fr.teamUpgrades[i] & 0xFF);
        }
        dos.writeByte(fr.diamondTier & 0xFF);
        dos.writeByte(fr.emeraldTier & 0xFF);
        dos.writeInt(fr.matchElapsedTicks);
        dos.writeShort(fr.eventFlagsThisTick & 0xFFFF);

        // 12. Recent Sound Radar
        int sCount = Math.min(fr.sounds.size(), FifthNNSchema.MAX_SOUNDS_RAW);
        dos.writeByte(sCount);
        for (int i = 0; i < sCount; i++) {
            var s = fr.sounds.get(i);
            dos.writeByte(s.soundCat() & 0xFF);
            dos.writeFloat(s.x());
            dos.writeFloat(s.y());
            dos.writeFloat(s.z());
            dos.writeFloat(s.volume());
        }

        // 13. Exact Human Inputs & Reward (>H4fBBbbBBf)
        dos.writeShort(fr.keyMask & 0xFFFF);
        dos.writeFloat(fr.rawMouseDx);
        dos.writeFloat(fr.rawMouseDy);
        dos.writeFloat(fr.deltaYaw);
        dos.writeFloat(fr.deltaPitch);
        dos.writeByte(Math.min(255, fr.leftClicksThisTick) & 0xFF);
        dos.writeByte(Math.min(255, fr.rightClicksThisTick) & 0xFF);
        dos.writeByte(fr.hotbarKeyPressed);
        dos.writeByte(fr.guiClickSlot);
        dos.writeByte(fr.guiClickButton & 0xFF);
        dos.writeByte(fr.guiClickType & 0xFF);
        dos.writeFloat(fr.rewardDelta);

        dos.flush();
        return baos.toByteArray();
    }

    public Path getFilePath() {
        return filePath;
    }

    public long getFramesWritten() {
        return framesWritten.get();
    }

    public long getValidFramesWritten() {
        return validFramesWritten.get();
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    public long getBytesWrittenEst() {
        return bytesWrittenEst.get();
    }

    public boolean hasFailed() {
        return failed.get();
    }

    public String getFailureReason() {
        return failureReason;
    }

    @Override
    public void close() {
        running.set(false);
        queue.offer(POISON_PILL);
        try {
            writerThread.join(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
