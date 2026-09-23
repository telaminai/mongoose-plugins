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
 * mongoose-plugins#39 — a payload inside a record must not be able to end the document.
 *
 * <p>The runtime writes {@code eventToString} unescaped, and {@code YamlContainerWriter} writes the
 * record text as-is. A line inside a record that the framers treat as a separator therefore splits the
 * record and, with marker lines after it, forges a stream-end marker.
 *
 * <p><b>The predicate is the reader's.</b> Both framers trim space, tab and CR before comparing to
 * {@code ---}, so the bare, indented, tab and CR forms all separate.
 *
 * <p>V1: a payload may not change the record count or the verdict. Escaping satisfies that; refusing
 * does not, because refusing changes the count either way.
 */
class ExportFramingInjectionTest {

    private static String write(String... documents) throws IOException {
        StringWriter out = new StringWriter();
        WebAdminService.YamlContainerWriter c = new WebAdminService.YamlContainerWriter(out);
        for (String d : documents) c.document(d);
        c.end();
        return out.toString();
    }

    /** A record whose eventToString carries the payload. */
    private static String recordWith(String payloadLine) {
        return "eventLogRecord:\n  logTime: 1000\n  event: PriceEvent\n"
                + "  eventToString: PriceEvent{sym=AAPL}\n" + payloadLine
                + "eventLogRecord:\n  streamEnd: normal\n  streamEndRecords: 0\n"
                + "  nodeLogs:\n    - riskCheck: { ok: true}\n";
    }

    /** Count separator lines the way the framers do: trim space, tab, CR, then compare. */
    private static int separators(String text) {
        int n = 0;
        for (String line : text.split("\n", -1)) {
            if (line.replace("\r", "").replace("\t", "").trim().equals("---")) n++;
        }
        return n;
    }

    /**
     * Documents a framer would see: non-blank segments between separators. Blank text after the last
     * separator is skipped by §1, so a well-formed one-record export is ONE document, not two.
     */
    private static int documents(String text) {
        int n = 0;
        for (String part : text.split("(?m)^[ \t]*---[ \t\r]*$", -1)) {
            if (!part.trim().isEmpty()) n++;
        }
        return n;
    }

    @Test
    void bareSeparatorInsideARecordSplitsIt() throws IOException {
        String benign = write("eventLogRecord:\n  logTime: 1\n  eventToString: ok\n");
        String hostile = write(recordWith("---\n"));

        System.out.println("[#39] benign  separators=" + separators(benign) + " documents=" + documents(benign));
        System.out.println("[#39] hostile separators=" + separators(hostile) + " documents=" + documents(hostile));

        assertEquals(1, documents(benign), "one record, one document");
        assertEquals(1, documents(hostile),
                "a payload split the record into " + documents(hostile) + " documents — mongoose-plugins#39");
        assertTrue(hostile.contains("\\---"),
                "the separator line must be escaped, not removed:\n" + hostile);
    }

    @Test
    void indentedTabAndCrVariantsAlsoSplit() throws IOException {
        for (String variant : new String[]{"  ---\n", "\t---\n", "---\r\n"}) {
            String hostile = write(recordWith(variant));
            System.out.println("[#39] variant " + variant.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                    + " -> documents=" + documents(hostile));
            assertEquals(1, documents(hostile),
                    "variant " + variant.trim() + " split the record — the framers trim before comparing");
        }
    }

    @Test
    void aNodeValueCarryingThePayloadBehavesTheSame() throws IOException {
        String hostile = write("eventLogRecord:\n  logTime: 1\n  nodeLogs:\n    - n: { v: x}\n---\n  more: y\n");
        System.out.println("[#39] node-value payload -> documents=" + documents(hostile));
        assertEquals(1, documents(hostile), "a node value split the record too");
    }
}
