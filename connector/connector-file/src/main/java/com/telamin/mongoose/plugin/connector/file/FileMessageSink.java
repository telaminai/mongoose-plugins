/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.connector.file;


import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Append-only file sink. Supports optional rotation by size and/or wall-clock
 * interval; rotated files are named {@code <filename>.<yyyyMMdd-HHmmss>} and
 * older backups beyond {@code maxBackupFiles} are deleted oldest-first.
 */
@Log4j2
public class FileMessageSink extends AbstractMessageSink<Object>
        implements Lifecycle {

    private static final DateTimeFormatter ROTATION_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Getter
    @Setter
    private String filename;
    @Getter
    @Setter
    private Supplier<Object> firstLineSupplier;
    /**
     * Rotate when the active file exceeds this many bytes. {@code 0} disables size-based rotation.
     */
    @Getter
    @Setter
    private long rotateOnSizeBytes = 0L;
    /**
     * Rotate when the active file is older than this. {@code 0} disables time-based rotation.
     */
    @Getter
    @Setter
    private long rotateOnIntervalMillis = 0L;
    /**
     * Cap on retained rotated backups. {@code 0} keeps all. Oldest are deleted first.
     */
    @Getter
    @Setter
    private int maxBackupFiles = 0;

    private PrintStream printStream;
    private CountingStream countingStream;
    private long openedAtMs;

    @Override
    public void init() {
    }

    @SneakyThrows
    @Override
    public void start() {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalStateException("FileMessageSink has no filename configured");
        }
        if (rotateOnSizeBytes < 0) {
            throw new IllegalStateException("rotateOnSizeBytes must be >= 0");
        }
        if (rotateOnIntervalMillis < 0) {
            throw new IllegalStateException("rotateOnIntervalMillis must be >= 0");
        }
        if (maxBackupFiles < 0) {
            throw new IllegalStateException("maxBackupFiles must be >= 0");
        }
        openActiveFile();
    }

    private void openActiveFile() throws IOException {
        Path path = Paths.get(filename);
        boolean exists = Files.exists(path) && Files.size(path) > 0;
        File parent = path.toFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        countingStream = new CountingStream(out, exists ? Files.size(path) : 0L);
        printStream = new PrintStream(countingStream, false, StandardCharsets.UTF_8);
        openedAtMs = System.currentTimeMillis();

        if (!exists && firstLineSupplier != null) {
            Object firstLine = firstLineSupplier.get();
            if (firstLine != null) {
                printStream.print(firstLine);
            }
        }
    }

    @Override
    protected void sendToSink(Object value) {
        log.trace("sink publish:{}", value);
        if (printStream == null) {
            log.warn("FileMessageSink not started — dropping event");
            return;
        }
        printStream.println(value);
        if (shouldRotate()) {
            try {
                rotate();
            } catch (IOException e) {
                log.warn("file rotation failed", e);
            }
        }
    }

    private boolean shouldRotate() {
        if (rotateOnSizeBytes > 0 && countingStream.bytesWritten() >= rotateOnSizeBytes) {
            return true;
        }
        if (rotateOnIntervalMillis > 0
                && (System.currentTimeMillis() - openedAtMs) >= rotateOnIntervalMillis) {
            return true;
        }
        return false;
    }

    private synchronized void rotate() throws IOException {
        if (printStream == null) return;
        printStream.flush();
        printStream.close();
        Path active = Paths.get(filename);
        if (Files.exists(active)) {
            String stamp = ROTATION_STAMP.format(Instant.now());
            Path target = active.resolveSibling(active.getFileName() + "." + stamp);
            // Suffix collision is rare but possible at sub-second rotations: append .N
            int n = 1;
            while (Files.exists(target)) {
                target = active.resolveSibling(active.getFileName() + "." + stamp + "." + n++);
            }
            Files.move(active, target);
            log.info("rotated file:{} -> {}", filename, target.getFileName());
        }
        pruneBackups();
        openActiveFile();
    }

    private void pruneBackups() {
        if (maxBackupFiles <= 0) return;
        Path active = Paths.get(filename);
        Path parent = active.toAbsolutePath().getParent();
        if (parent == null) return;
        String base = active.getFileName().toString();
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parent)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(base + ".") && !name.equals(base)) {
                    backups.add(p);
                }
            });
        } catch (IOException e) {
            log.warn("could not enumerate backups for {}", filename, e);
            return;
        }
        if (backups.size() <= maxBackupFiles) return;
        backups.sort(Comparator.comparing(Path::getFileName));
        int toDelete = backups.size() - maxBackupFiles;
        for (int i = 0; i < toDelete; i++) {
            try {
                Files.deleteIfExists(backups.get(i));
                log.info("pruned old backup {}", backups.get(i).getFileName());
            } catch (IOException e) {
                log.warn("could not delete backup {}", backups.get(i), e);
            }
        }
    }

    @Override
    public void stop() {
        if (printStream != null) {
            printStream.flush();
            printStream.close();
            printStream = null;
            countingStream = null;
        }
    }

    @Override
    public void tearDown() {
        stop();
    }

    /**
     * For tests: list rotated backups in deterministic (filename) order.
     */
    public List<String> listRotatedBackups() {
        if (filename == null) return Collections.emptyList();
        Path active = Paths.get(filename);
        Path parent = active.toAbsolutePath().getParent();
        if (parent == null) return Collections.emptyList();
        String base = active.getFileName().toString();
        List<String> backups = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parent)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(base + ".") && !name.equals(base)) {
                    backups.add(name);
                }
            });
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(backups);
        return backups;
    }

    private static final class CountingStream extends FilterOutputStream {
        private long count;

        CountingStream(OutputStream out, long initialCount) {
            super(out);
            this.count = initialCount;
        }

        long bytesWritten() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
