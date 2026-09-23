/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Scratch: what do toEnd().index() and moveToIndex actually do on an empty and a non-empty queue? */
class ChronicleIndexSemanticsTest {

    private static void write(ChronicleQueue q, int from, int n) {
        ExcerptAppender a = q.createAppender();
        for (int i = 0; i < n; i++) {
            try (DocumentContext dc = a.writingDocument()) {
                dc.wire().getValueOut().text("r" + (from + i));
            }
        }
    }

    private static String drain(ExcerptTailer t) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            try (DocumentContext dc = t.readingDocument()) {
                if (!dc.isPresent()) break;
                sb.append(dc.wire().getValueIn().text()).append(' ');
            }
        }
        return sb.toString().trim();
    }

    @Test
    void emptyQueue() throws Exception {
        Path dir = Files.createTempDirectory("idx-empty");
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            long end;
            try (ExcerptTailer p = q.createTailer()) { end = p.toEnd().index(); }
            System.out.println("[IDX] empty: toEnd().index()=" + end
                    + " firstIndex=" + q.firstIndex());
            write(q, 0, 5);
            ExcerptTailer t = q.createTailer();
            boolean moved = t.moveToIndex(end);
            System.out.println("[IDX] empty: moveToIndex(" + end + ")=" + moved
                    + " then read -> [" + drain(t) + "]");

            ExcerptTailer t2 = q.createTailer();
            System.out.println("[IDX] empty: toStart() -> [" + drain(t2.toStart()) + "]");
        }
    }

    /** The probe's exact shape: a writer handle with an appender already open, then the service's handle. */
    @Test
    void appenderOpenButNothingWritten() throws Exception {
        Path dir = Files.createTempDirectory("idx-appender");
        try (ChronicleQueue writer = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            ExcerptAppender a = writer.createAppender();
            try (ChronicleQueue service = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
                long first = service.firstIndex();
                long end;
                try (ExcerptTailer p = service.createTailer()) { end = p.toEnd().index(); }
                System.out.println("[IDX] appenderOpen: firstIndex=" + first
                        + " (MAX=" + (first == Long.MAX_VALUE) + ") toEnd().index()=" + end);

                for (int i = 0; i < 5; i++) {
                    try (DocumentContext dc = a.writingDocument()) {
                        dc.wire().getValueOut().text("r" + i);
                    }
                }
                ExcerptTailer t = service.createTailer();
                boolean moved = t.moveToIndex(end);
                System.out.println("[IDX] appenderOpen: moveToIndex(" + end + ")=" + moved
                        + " then read -> [" + drain(t) + "]   (want: r0..r4)");
                ExcerptTailer t2 = service.createTailer();
                System.out.println("[IDX] appenderOpen: toStart() -> [" + drain(t2.toStart()) + "]");
            }
        }
    }

    @Test
    void nonEmptyQueue() throws Exception {
        Path dir = Files.createTempDirectory("idx-full");
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(dir.toFile()).build()) {
            write(q, 0, 3);
            long end;
            try (ExcerptTailer p = q.createTailer()) { end = p.toEnd().index(); }
            System.out.println("[IDX] nonEmpty: after 3 writes toEnd().index()=" + end);
            write(q, 100, 4);
            ExcerptTailer t = q.createTailer();
            boolean moved = t.moveToIndex(end);
            System.out.println("[IDX] nonEmpty: moveToIndex(" + end + ")=" + moved
                    + " then read -> [" + drain(t) + "]   (want: r100 r101 r102 r103)");
        }
    }
}
