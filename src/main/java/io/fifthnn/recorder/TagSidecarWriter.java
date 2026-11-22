package io.fifthnn.recorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append-only sidecar tag writer (`<recording>.5nn.gz.tags.jsonl`) for zero-rewrite
 * quarantine markers, pearl-landing continuity tags, and F6/F7 user highlight/scrap ranges.
 */
public final class TagSidecarWriter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Tags");

    private final Path tagsPath;
    private BufferedWriter writer;

    public TagSidecarWriter(Path recordingPath) {
        this.tagsPath = Path.of(recordingPath.toString() + ".tags.jsonl");
    }

    public synchronized void writeTag(long startTick, long endTick, int episodeId, String tag) {
        try {
            if (writer == null) {
                Files.createDirectories(tagsPath.getParent());
                writer = Files.newBufferedWriter(
                        tagsPath,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
            String line = String.format(
                    "{\"startTick\":%d,\"endTick\":%d,\"episodeId\":%d,\"tag\":\"%s\"}",
                    Math.max(0L, startTick), Math.max(0L, endTick), episodeId, escape(tag)
            );
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOGGER.warn("Failed to write tag sidecar {}: {}", tagsPath, e.getMessage());
        }
    }

    public Path getTagsPath() {
        return tagsPath;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
    }
}
