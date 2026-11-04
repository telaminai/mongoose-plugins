/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.time.Clock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditRecordProjection#yamlToJson} reads both runtime text forms: the older bare header text and the newer
 * record marked {@code textEncoding: QUOTED_SCALARS} whose awkward values are YAML double-quoted scalars. Either way
 * the JSON carries the decoded text, never the quotes or escapes, and never the marker.
 */
class AuditRecordProjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GYM_REASON = "membership not active, balance due 30.0";

    private static JsonNode project(String yaml) throws Exception {
        JsonNode root = JSON.readTree(AuditRecordProjection.yamlToJson(yaml));
        assertNull(root.get("_parseError"), () -> "unparseable: " + root);
        return root.get("eventLogRecord");
    }

    @Test
    void oldBareRecordProjectsAsBefore() throws Exception {
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    eventTime: 1774203274604",
                "    logTime: 1774203274604",
                "    groupingId: null",
                "    event: GymCheckIn",
                "    eventToString: GymCheckIn[member=7, reason=" + GYM_REASON + ", note=\"vip\"]",
                "    thread: gym-agent",
                "    nodeLogs: ",
                "        - gate: { admitted: false, reason: \"" + GYM_REASON + "\"}",
                "    endTime: 1774203274605"));
        assertEquals("GymCheckIn", r.get("event").asText());
        assertEquals("GymCheckIn[member=7, reason=" + GYM_REASON + ", note=\"vip\"]", r.get("eventToString").asText());
        assertEquals("gym-agent", r.get("thread").asText());
        assertEquals(GYM_REASON, r.get("nodeLogs").get(0).get("gate").get("reason").asText());
        assertFalse(r.has("textEncoding"));
    }

    @Test
    void oldBareMultiLineAndIndicatorTextStillReads() throws Exception {
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    eventTime: 1",
                "    event: ExportedFunction",
                "    eventToString: @Override public void onEvent(String a,",
                "        int b) # done",
                "    thread: main",
                "    nodeLogs: "));
        assertEquals("@Override public void onEvent(String a,\n        int b) # done", r.get("eventToString").asText());
    }

    @Test
    void unmarkedValueThatLooksQuotedIsTakenVerbatim() throws Exception {
        // an older runtime wrote toString() bare: the quotes are part of the text
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    eventTime: 1",
                "    event: Note",
                "    eventToString: \"quoted by toString\"",
                "    nodeLogs: "));
        assertEquals("\"quoted by toString\"", r.get("eventToString").asText());
    }

    @Test
    void markedRecordDecodesQuotedScalarsAndDropsTheMarker() throws Exception {
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    textEncoding: QUOTED_SCALARS",
                "    eventTime: 1774203274604",
                "    logTime: 1774203274604",
                "    groupingId: \" padded\"",
                "    event: GymCheckIn",
                "    eventToString: \"GymCheckIn{reason: " + GYM_REASON + ", note=\\x22vip\\x22}\\nsecond line \\\\ \\u00e9\\t\\0\"",
                "    thread: \"- agent #1\"",
                "    eventFilter: \"admin: reset\"",
                "    nodeLogs: ",
                "        - gate: { admitted: false, reason: \"" + GYM_REASON + "\"}",
                "        - desk: { says: \"call \\x22front desk\\x22, then retry\", code: \"30\"}",
                "    endTime: 1774203274605"));
        assertFalse(r.has("textEncoding"), "the marker is not a record field: " + r);
        assertEquals("GymCheckIn", r.get("event").asText());
        assertEquals("GymCheckIn{reason: " + GYM_REASON + ", note=\"vip\"}\nsecond line \\ é\t\0",
                r.get("eventToString").asText());
        assertEquals(" padded", r.get("groupingId").asText());
        assertEquals("- agent #1", r.get("thread").asText());
        assertEquals("admin: reset", r.get("eventFilter").asText());
        assertEquals(GYM_REASON, r.get("nodeLogs").get(0).get("gate").get("reason").asText());
        assertEquals("call \"front desk\", then retry", r.get("nodeLogs").get(1).get("desk").get("says").asText());
        assertEquals("30", r.get("nodeLogs").get(1).get("desk").get("code").asText());
        assertTrue(r.get("nodeLogs").get(1).get("desk").get("code").isTextual());
    }

    @Test
    void markedRecordWithPlainHeadersStillRewritesThem() throws Exception {
        // only a nodeLogs value was quoted: header text stays plain and goes through the block-literal path
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    textEncoding: QUOTED_SCALARS",
                "    eventTime: 1",
                "    event: GymCheckIn",
                "    eventToString: GymCheckIn[member=7, tags=[a, b]]",
                "    thread: gym-agent",
                "    nodeLogs: ",
                "        - gate: { reason: \"" + GYM_REASON + "\"}"));
        assertEquals("GymCheckIn[member=7, tags=[a, b]]", r.get("eventToString").asText());
        assertEquals(GYM_REASON, r.get("nodeLogs").get(0).get("gate").get("reason").asText());
        assertFalse(r.has("textEncoding"));
    }

    @Test
    void markerIsHonouredOnlyDirectlyAfterTheRecordKey() throws Exception {
        // an older runtime's bare multi-line toString cannot forge the marker
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    eventTime: 1",
                "    event: Forged",
                "    eventToString: first",
                "    textEncoding: QUOTED_SCALARS",
                "    thread: \"main\"",
                "    nodeLogs: "));
        assertEquals("\"main\"", r.get("thread").asText());
    }

    @Test
    void markerInsideAnOldBareMultiLineToStringIsNotHonoured() throws Exception {
        // an older runtime's toString carrying both a record key and a marker line: neither is the record's
        // opening, so nothing switches on and no line is dropped
        String toString = "Forged{\neventLogRecord: \n      textEncoding: QUOTED_SCALARS\n}";
        JsonNode r = project(String.join("\n",
                "eventLogRecord: ",
                "    eventTime: 1",
                "    event: Forged",
                "    eventToString: Forged{",
                "eventLogRecord: ",
                "      textEncoding: QUOTED_SCALARS",
                "}",
                "    thread: \"main\"",
                "    nodeLogs: "));
        assertEquals(toString, r.get("eventToString").asText());
        assertEquals("\"main\"", r.get("thread").asText());
        assertFalse(r.has("textEncoding"));
    }

    @Test
    void recordKeyAndMarkerLinesInsideOldBareTextDoNotSwitchModeOrDropALine() {
        // an older runtime's bare multi-line toString whose lines read "eventLogRecord: " then a sibling-indented
        // marker: the marker line is not the record's, so it is kept and quoted-looking text stays verbatim
        String yaml = String.join("\n",
                "eventLogRecord: ",
                "    eventTime: 1",
                "    event: Forged",
                "    eventToString: Forged{",
                "eventLogRecord: ",
                "    textEncoding: QUOTED_SCALARS",
                "    thread: \"main\"",
                "    nodeLogs: ");
        String out = AuditRecordProjection.fixupFreeformScalars(yaml);
        assertTrue(out.contains("\n    textEncoding: QUOTED_SCALARS\n"), out);
        assertTrue(out.contains("    thread: |-\n        \"main\""), out);
    }

    @Test
    void indentedRecordKeyDoesNotOpenAMarkedRecord() {
        String yaml = String.join("\n",
                "    eventLogRecord: ",
                "    textEncoding: QUOTED_SCALARS",
                "    thread: \"main\"");
        String out = AuditRecordProjection.fixupFreeformScalars(yaml);
        assertTrue(out.contains("textEncoding: QUOTED_SCALARS"), out);
        assertTrue(out.contains("thread: |-"), out);
    }

    @Test
    void markerAfterLeadingBlankLinesIsHonoured() throws Exception {
        JsonNode r = project(String.join("\n",
                "",
                "eventLogRecord: ",
                "",
                "    textEncoding: QUOTED_SCALARS",
                "    eventTime: 1",
                "    event: GymCheckIn",
                "    thread: \"- agent #1\"",
                "    nodeLogs: "));
        assertEquals("- agent #1", r.get("thread").asText());
        assertFalse(r.has("textEncoding"));
    }

    @Test
    void wholeQuotedScalarDetection() {
        assertTrue(AuditRecordProjection.isDoubleQuotedScalar("\"a\""));
        assertTrue(AuditRecordProjection.isDoubleQuotedScalar("\"a \\\" b \\\\\"  "));
        assertTrue(AuditRecordProjection.isDoubleQuotedScalar("\"\""));
        assertFalse(AuditRecordProjection.isDoubleQuotedScalar("\"a\" b"));
        assertFalse(AuditRecordProjection.isDoubleQuotedScalar("\"unterminated"));
        assertFalse(AuditRecordProjection.isDoubleQuotedScalar("\"ends in escape\\\""));
        assertFalse(AuditRecordProjection.isDoubleQuotedScalar("plain"));
    }

    /** The record the Fluxtion runtime on the classpath actually writes, older (bare) or newer (quoted, marked). */
    @Test
    void recordFromTheRuntimeOnTheClasspathProjectsToTheLoggedText() throws Exception {
        // an older runtime writes a nodeLogs value bare inside the flow map, where a comma splits it (the bug the
        // newer runtime fixes), so the awkward reason is only logged when the runtime quotes
        boolean quotingRuntime = isPresent("com.telamin.fluxtion.runtime.audit.YamlScalar");
        String reason = quotingRuntime ? GYM_REASON + " \"late\"" : "late";
        Clock clock = new Clock();
        clock.init();
        LogRecord record = new LogRecord(clock);
        record.printEventToString(true);
        record.triggerObject(new GymCheckIn());
        record.addRecord("gate", "reason", reason);
        record.addRecord("gate", "admitted", false);
        record.terminateRecord();
        String yaml = record.asCharSequence().toString();
        assertEquals(quotingRuntime, yaml.contains(AuditRecordProjection.TEXT_ENCODING_MARKER), yaml);

        JsonNode r = project(yaml);
        assertFalse(r.has("textEncoding"), yaml);
        assertEquals("GymCheckIn", r.get("event").asText(), yaml);
        assertEquals(GymCheckIn.TEXT, r.get("eventToString").asText(), yaml);
        JsonNode gate = r.get("nodeLogs").get(0).get("gate");
        assertEquals(reason, gate.get("reason").asText(), yaml);
        assertTrue(gate.get("admitted").isBoolean() && !gate.get("admitted").asBoolean(), yaml);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    static final class GymCheckIn {
        // a line break and ": " make the newer runtime quote this header value; an older one writes it bare
        static final String TEXT = "GymCheckIn{reason: " + GYM_REASON + ",\n  note=\"vip\"}";

        @Override
        public String toString() {
            return TEXT;
        }
    }
}
