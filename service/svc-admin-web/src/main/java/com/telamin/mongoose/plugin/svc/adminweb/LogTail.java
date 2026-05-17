/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import lombok.extern.log4j.Log4j2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Bounded ring buffer of recent log lines plus a {@link java.util.logging.Handler}
 * that feeds it.
 *
 * <p>Mongoose uses {@code java.util.logging} as the runtime sink; libraries that
 * bridge SLF4J/Log4j2 to j.u.l also flow in through the root logger. Subscribers
 * — typically {@code /ws/logs} WebSocket connections — receive each new record
 * after it lands in the buffer.
 *
 * <p>Buffer access is synchronized; record fan-out is best-effort (subscriber
 * exceptions are caught and logged at this layer so a single bad client cannot
 * starve the others).
 */
@Log4j2
final class LogTail {

    private final int capacity;
    private final Deque<LogLine> buffer;
    private final List<Consumer<LogLine>> subscribers = new CopyOnWriteArrayList<>();
    private final TailHandler handler = new TailHandler();
    private Logger attachedTo;

    LogTail(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("logTailBuffer must be > 0, was " + capacity);
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    void start() {
        if (attachedTo != null) return;
        attachedTo = LogManager.getLogManager().getLogger("");
        if (attachedTo != null) {
            attachedTo.addHandler(handler);
        }
    }

    void stop() {
        if (attachedTo != null) {
            attachedTo.removeHandler(handler);
            attachedTo = null;
        }
    }

    void subscribe(Consumer<LogLine> c) {
        subscribers.add(c);
    }

    void unsubscribe(Consumer<LogLine> c) {
        subscribers.remove(c);
    }

    int capacity() {
        return capacity;
    }

    int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /** Snapshot of all retained records, oldest first. */
    List<LogLine> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    /** Direct append for tests + internal use. */
    void append(LogLine line) {
        synchronized (buffer) {
            if (buffer.size() == capacity) {
                buffer.removeFirst();
            }
            buffer.addLast(line);
        }
        for (Consumer<LogLine> sub : subscribers) {
            try {
                sub.accept(line);
            } catch (Exception e) {
                log.warn("log-tail subscriber threw", e);
            }
        }
    }

    /** Plain DTO — public for Jackson. */
    public record LogLine(long ts, String level, String logger, String msg) {
    }

    private final class TailHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            if (record == null) return;
            // Skip records emitted by the WebAdminService itself to avoid feedback
            // loops if a subscriber logs while we're broadcasting.
            String loggerName = record.getLoggerName() == null ? "" : record.getLoggerName();
            if (loggerName.startsWith("com.telamin.mongoose.plugin.svc.adminweb")) return;

            Level level = record.getLevel() == null ? Level.INFO : record.getLevel();
            String msg = record.getMessage();
            if (msg != null && record.getParameters() != null && record.getParameters().length > 0) {
                try {
                    msg = java.text.MessageFormat.format(msg, record.getParameters());
                } catch (Exception ignore) {
                    // fall back to raw template
                }
            }
            append(new LogLine(record.getMillis(), level.getName(), loggerName, msg == null ? "" : msg));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
