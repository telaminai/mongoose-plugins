/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UP-MON-01 — the YAML export must terminate its last document, not only the gaps between documents.
 *
 * <p>This drives {@link WebAdminService.YamlContainerWriter}, which the export uses, rather than a copy
 * of its separator rule. That distinction is the whole reason the audit-tail fix on this branch was sent
 * back: its three tests re-implemented the loop they were meant to guard, so both defects could be
 * reverted with the suite staying green.
 *
 * <p>Why the last separator matters is in the class comment. In short: a stream-end marker only counts
 * once its `---` is there, because a finished marker and a half-written one are the same bytes, and the
 * separator belongs to this formatter rather than to whatever writes the marker.
 */
class YamlContainerWriterTest {

    private static String write(String... documents) throws IOException {
        StringWriter out = new StringWriter();
        WebAdminService.YamlContainerWriter c = new WebAdminService.YamlContainerWriter(out);
        for (String d : documents) c.document(d);
        c.end();
        return out.toString();
    }

    private static String record(int n) {
        return "eventLogRecord:\n  logTime: " + (1000 + n) + "\n  event: Tick" + n + "\n";
    }

    @Test
    void theLastDocumentIsTerminated() throws IOException {
        String out = write(record(1), record(2));
        assertTrue(out.endsWith("\n---\n"),
                () -> "the export ended without closing its last document:\n" + out);
        assertEquals(2, out.split("(?m)^---$", -1).length - 1,
                () -> "two documents means two separators, not one:\n" + out);
    }

    @Test
    void documentsAreStillSeparatedFromEachOther() throws IOException {
        String out = write(record(1), record(2), record(3));
        assertTrue(out.contains("Tick1") && out.contains("Tick2") && out.contains("Tick3"), out);
        assertTrue(out.contains("event: Tick1\n\n---\neventLogRecord"),
                () -> "the separator between documents is unchanged:\n" + out);
    }

    @Test
    void aSingleDocumentIsAlsoTerminated() throws IOException {
        String out = write(record(1));
        assertTrue(out.startsWith("eventLogRecord:"), out);
        assertTrue(out.endsWith("\n---\n"), () -> "a one-record export closes too:\n" + out);
    }

    /**
     * An empty export writes nothing at all. A lone `---` would be a container holding one blank
     * document, which §1 skips, so it would be harmless — but writing nothing is what it means.
     */
    @Test
    void anEmptyExportWritesNothing() throws IOException {
        assertEquals("", write());
    }

    /**
     * The acceptance, stated as the rule the analyser applies: a marker is only a claim once its
     * separator follows it. The published reader's behaviour is verified separately against the released
     * jar; this pins the bytes this service produces.
     */
    @Test
    void aMarkerAppendedAsTheLastDocumentComesOutTerminated() throws IOException {
        String marker = "eventLogRecord:\n  streamEnd: normal\n  streamEndRecords: 2\n";
        String out = write(record(1), record(2), marker);
        assertTrue(out.contains("streamEnd: normal"), out);
        assertTrue(out.endsWith("streamEndRecords: 2\n\n---\n"),
                () -> "the marker must be followed by its separator or the claim is ignored:\n" + out);
    }
}
