/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LogTailTest {

    @Test
    void append_keeps_last_n_records() {
        LogTail tail = new LogTail(3);
        tail.append(new LogTail.LogLine(1, "INFO", "a", "one"));
        tail.append(new LogTail.LogLine(2, "INFO", "a", "two"));
        tail.append(new LogTail.LogLine(3, "INFO", "a", "three"));
        tail.append(new LogTail.LogLine(4, "INFO", "a", "four"));

        List<LogTail.LogLine> snap = tail.snapshot();
        assertEquals(3, snap.size());
        assertEquals("two",   snap.get(0).msg());
        assertEquals("three", snap.get(1).msg());
        assertEquals("four",  snap.get(2).msg());
    }

    @Test
    void subscribers_receive_appended_records() {
        LogTail tail = new LogTail(10);
        List<LogTail.LogLine> seen = new CopyOnWriteArrayList<>();
        tail.subscribe(seen::add);

        tail.append(new LogTail.LogLine(1, "INFO", "x", "hi"));
        tail.append(new LogTail.LogLine(2, "WARNING", "x", "warn"));

        assertEquals(2, seen.size());
        assertEquals("hi",   seen.get(0).msg());
        assertEquals("warn", seen.get(1).msg());
    }

    @Test
    void zero_capacity_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new LogTail(0));
        assertThrows(IllegalArgumentException.class, () -> new LogTail(-1));
    }

    @Test
    void handler_skips_admin_web_logger_to_avoid_feedback() {
        LogTail tail = new LogTail(10);
        tail.start();
        try {
            Logger noisy = Logger.getLogger("com.telamin.mongoose.plugin.svc.adminweb.NoisySubscriber");
            noisy.setUseParentHandlers(false); // don't spam test output
            LogRecord rec = new LogRecord(Level.INFO, "should be filtered");
            rec.setLoggerName("com.telamin.mongoose.plugin.svc.adminweb.NoisySubscriber");
            // Drive the handler directly — same code path as the j.u.l publish().
            for (java.util.logging.Handler h : LogManager.getLogManager().getLogger("").getHandlers()) {
                if (h.getClass().getName().endsWith("TailHandler")) {
                    h.publish(rec);
                }
            }
            assertEquals(0, tail.size(), "records from svc-admin-web logger must be ignored");
        } finally {
            tail.stop();
        }
    }

    @Test
    void handler_captures_root_logger_records() {
        LogTail tail = new LogTail(10);
        tail.start();
        try {
            Logger app = Logger.getLogger("test.user.app");
            app.setLevel(Level.ALL);
            app.info("hello tail");

            List<LogTail.LogLine> snap = tail.snapshot();
            assertTrue(snap.stream().anyMatch(l -> "hello tail".equals(l.msg())),
                    "expected captured record: " + snap);
        } finally {
            tail.stop();
        }
    }
}
