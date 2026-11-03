/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit stream header ({@code auditStreamHeader:}) the Fluxtion runtime writes first in every audit
 * stream is metadata, not a record. The paged read must page over event records only, so a client sees
 * the same records on the same pages with or without headers, and must surface the header in the
 * {@code X-Audit-Stream-Headers} response header instead. The live tail must not send it as a record;
 * the export, which is the whole stream, keeps it.
 *
 * <p>Fixtures append header documents directly: the header is plain text in the Chronicle queue, the
 * same as a record, so this needs no runtime that writes one.
 */
class AuditStreamHeaderPagingTest {

    private static final String PROCESSOR = "header-processor";
    private static final ObjectMapper JSON = new ObjectMapper();

    private WebAdminService svc;
    private Path queueDir;
    private int port;

    @AfterEach
    void stop() {
        if (svc != null) {
            try {
                svc.stop();
            } catch (Exception ignored) {
                // the service is going away; a failure here must not mask the assertion
            }
        }
    }

    /** The header text exactly as the runtime renders it (AuditStreamHeader.appendTo). */
    static String header(String serverId, String startedAt) {
        return "auditStreamHeader:\n"
                + "    schema: 1\n"
                + "    processor: com.acme.TradeProcessor\n"
                + "    sourceFingerprint: null\n"
                + "    toolchainVersion: 1.0.84\n"
                + "    runtimeVersion: 1.1.4\n"
                + "    startedAt: '" + startedAt + "'\n"
                + "    host: {serverId: " + serverId + ", group: pricing, name: quoter, generation: '1'}";
    }

    static String record(int n) {
        return "eventLogRecord:\n  logTime: " + (1_000_000 + n) + "\n  event: Tick\n"
                + "  nodeLogs:\n    - probe: { seq: " + n + "}\n";
    }

    private void write(String... docs) throws Exception {
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(queueDir.toFile()).build()) {
            ExcerptAppender a = q.createAppender();
            for (String d : docs) {
                try (DocumentContext dc = a.writingDocument()) {
                    dc.wire().getValueOut().text(d);
                }
            }
        }
    }

    private static String[] records(int from, int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) out[i] = record(from + i);
        return out;
    }

    private static String[] concat(Object... parts) {
        List<String> out = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof String s) out.add(s);
            else for (String s : (String[]) p) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    private MongooseAuditIntrospectionService introspection() {
        AuditSinkHandle handle = new AuditSinkHandle(PROCESSOR, PROCESSOR, queueDir, 0, 0, 0,
                Instant.now(), Instant.now(), true);
        return new MongooseAuditIntrospectionService() {
            @Override public List<AuditSinkHandle> listAvailable() { return List.of(handle); }
            @Override public AuditSinkHandle currentSink(String processorName) {
                return PROCESSOR.equals(processorName) ? handle : null;
            }
            @Override public Map<String, AuditSinkHandle> currentSinks() { return Map.of(PROCESSOR, handle); }
        };
    }

    private void boot(String... docs) throws Exception {
        queueDir = Files.createTempDirectory("audit-header");
        write(docs);
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setAuthMode(WebAdminService.AuthMode.NONE);
        svc.auditIntrospectionService(introspection(), "audit");
        svc.init();
        svc.start();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r;
    }

    /** One page: the seq of each NDJSON line, in order, and the parsed stream-header metadata. */
    private record Page(List<Integer> seqs, JsonNode headers, boolean truncated) {}

    private Page page(long from, long limit) throws Exception {
        HttpResponse<String> r = get("/api/audit/file/" + PROCESSOR + "?from=" + from + "&limit=" + limit);
        List<Integer> seqs = new ArrayList<>();
        for (String line : r.body().split("\n")) {
            if (line.isBlank()) continue;
            JsonNode rec = JSON.readTree(line);
            assertFalse(rec.has("auditStreamHeader"), "a header was returned as a record: " + line);
            seqs.add(rec.at("/eventLogRecord/nodeLogs/0/probe/seq").asInt());
        }
        String h = r.headers().firstValue(WebAdminService.STREAM_HEADERS_HTTP_HEADER).orElse(null);
        assertNotNull(h, "the stream-headers response header is always present");
        return new Page(seqs, JSON.readTree(h),
                r.headers().firstValue(WebAdminService.STREAM_HEADERS_HTTP_HEADER + "-Truncated").isPresent());
    }

    private static List<Integer> range(int from, int toExclusive) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) out.add(i);
        return out;
    }

    @Test
    void aHeaderFirstIsMetadataNotTheFirstRecord() throws Exception {
        boot(concat(header("pricing-1", "2026-11-03T09:15:02.118Z"), records(0, 5)));

        Page p = page(0, 3);
        assertEquals(List.of(0, 1, 2), p.seqs(), "the header takes no slot: three records on a page of three");
        assertEquals(1, p.headers().size(), p.headers().toString());
        JsonNode e = p.headers().get(0);
        assertEquals(0, e.get("recordIndex").asLong());
        assertEquals(1, e.at("/header/schema").asInt());
        assertEquals("com.acme.TradeProcessor", e.at("/header/processor").asText());
        assertTrue(e.at("/header/sourceFingerprint").isNull());
        assertEquals("2026-11-03T09:15:02.118Z", e.at("/header/startedAt").asText(),
                "startedAt stays the string the runtime wrote");
        assertEquals("pricing-1", e.at("/header/host/serverId").asText());
        assertEquals("1", e.at("/header/host/generation").asText());

        Page next = page(3, 3);
        assertEquals(List.of(3, 4), next.seqs());
        assertEquals(1, next.headers().size(), "the header in effect is reported on every page");
        assertEquals(0, next.headers().get(0).get("recordIndex").asLong(),
                "at the index of the record it precedes, however far back");
    }

    @Test
    void aHeaderRepeatedMidStreamIsReportedWhereItChangesAndIdenticalRepeatsAreNot() throws Exception {
        String h1 = header("pricing-1", "2026-11-03T09:15:02.118Z");
        String h2 = header("pricing-2", "2026-11-03T10:00:00.000Z");
        boot(concat(h1, records(0, 4), h1 /* roll-cycle repeat */, records(4, 2), h2, records(6, 4)));

        Page all = page(0, 100);
        assertEquals(range(0, 10), all.seqs());
        assertEquals(2, all.headers().size(), "an identical repeat changes nothing and is not listed: "
                + all.headers());
        assertEquals(0, all.headers().get(0).get("recordIndex").asLong());
        assertEquals(6, all.headers().get(1).get("recordIndex").asLong());
        assertEquals("pricing-2", all.headers().get(1).at("/header/host/serverId").asText());

        Page tail = page(7, 100);
        assertEquals(List.of(7, 8, 9), tail.seqs());
        assertEquals(1, tail.headers().size());
        assertEquals("pricing-2", tail.headers().get(0).at("/header/host/serverId").asText(),
                "the page starts under the second header");
        assertEquals(6, tail.headers().get(0).get("recordIndex").asLong());

        Page headerAtPageStart = page(6, 2);
        assertEquals(List.of(6, 7), headerAtPageStart.seqs());
        assertEquals(1, headerAtPageStart.headers().size());
        assertEquals(6, headerAtPageStart.headers().get(0).get("recordIndex").asLong());
        assertFalse(headerAtPageStart.truncated());
    }

    @Test
    void aHeaderOnlyStreamHasZeroRecordsAndStillReportsItsHeader() throws Exception {
        boot(header("pricing-1", "2026-11-03T09:15:02.118Z"));

        Page p = page(0, 1000);
        assertEquals(List.of(), p.seqs(), "a header-only stream holds zero records");
        assertEquals(1, p.headers().size());
        assertEquals(0, p.headers().get(0).get("recordIndex").asLong());
    }

    @Test
    void pageBoundariesAreUnchangedByHeaders() throws Exception {
        // Reference: the same 23 records, no headers.
        boot(records(0, 23));
        List<List<Integer>> plain = new ArrayList<>();
        for (int from = 0; from < 25; from += 5) plain.add(page(from, 5).seqs());
        assertEquals("[]", page(0, 5).headers().toString(), "no header in the stream: an empty list");
        svc.stop();
        svc = null;

        String h1 = header("pricing-1", "2026-11-03T09:15:02.118Z");
        String h2 = header("pricing-2", "2026-11-03T10:00:00.000Z");
        // headers at the start, exactly on a page boundary (before record 5), mid-page, repeated, and last
        boot(concat(h1, records(0, 5), h2, records(5, 3), h1, records(8, 7), h1, records(15, 8), h2));
        List<List<Integer>> withHeaders = new ArrayList<>();
        for (int from = 0; from < 25; from += 5) withHeaders.add(page(from, 5).seqs());

        assertEquals(plain, withHeaders, "the same records on the same pages");
        assertEquals(range(0, 5), withHeaders.get(0));
        assertEquals(List.of(20, 21, 22), withHeaders.get(4));

        // the last page ends the stream, so the trailing header (after record 22) is reported too
        Page last = page(20, 5);
        assertEquals(2, last.headers().size(), last.headers().toString());
        // h1 before record 15 repeats the h1 already in effect since record 8, so it is not a change
        assertEquals(8, last.headers().get(0).get("recordIndex").asLong());
        assertEquals(23, last.headers().get(1).get("recordIndex").asLong());

        // past the end: no records, the header in effect at the end
        Page past = page(100, 5);
        assertEquals(List.of(), past.seqs());
        assertEquals(1, past.headers().size());
        assertEquals("pricing-2", past.headers().get(0).at("/header/host/serverId").asText());
    }

    @Test
    void tooManyHeaderChangesInOnePageAreCappedAndFlagged() throws Exception {
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < WebAdminService.MAX_STREAM_HEADERS_PER_PAGE + 4; i++) {
            docs.add(header("pricing-" + i, "2026-11-03T09:15:02.118Z"));
            docs.add(record(i));
        }
        boot(docs.toArray(new String[0]));
        Page p = page(0, 1000);
        assertEquals(range(0, WebAdminService.MAX_STREAM_HEADERS_PER_PAGE + 4), p.seqs());
        assertTrue(p.truncated());
        assertEquals(WebAdminService.MAX_STREAM_HEADERS_PER_PAGE + 1, p.headers().size(),
                "the header in effect at the page start, plus the capped mid-page changes");
    }

    @Test
    void theExportKeepsTheHeaderAsItsOwnDocument() throws Exception {
        boot(concat(header("pricing-1", "2026-11-03T09:15:02.118Z"), records(0, 2)));

        String yaml = get("/api/audit/file/" + PROCESSOR + "/export?format=yaml").body();
        assertTrue(yaml.startsWith("auditStreamHeader:\n"), yaml);
        assertEquals(3, yaml.split("\n---\n", -1).length - 1, "header + two records, each terminated:\n" + yaml);

        String[] lines = get("/api/audit/file/" + PROCESSOR + "/export?format=jsonl").body().split("\n");
        assertEquals(3, lines.length);
        JsonNode h = JSON.readTree(lines[0]);
        assertEquals("com.acme.TradeProcessor", h.at("/auditStreamHeader/processor").asText());
        assertEquals("2026-11-03T09:15:02.118Z", h.at("/auditStreamHeader/startedAt").asText());
        assertTrue(JSON.readTree(lines[1]).has("eventLogRecord"));
    }

    @Test
    void theLiveTailSendsRecordsNotHeaders() throws Exception {
        queueDir = Files.createTempDirectory("audit-header-tick");
        write(concat(header("pricing-1", "2026-11-03T09:15:02.118Z"), records(0, 2),
                header("pricing-2", "2026-11-03T10:00:00.000Z"), records(2, 1)));
        try (ChronicleQueue q = SingleChronicleQueueBuilder.binary(queueDir.toFile()).build()) {
            var state = new WebAdminService.AuditTailState(q, Executors.newSingleThreadScheduledExecutor());
            ExcerptTailer tailer = q.createTailer().toStart();
            List<String> sent = new ArrayList<>();
            assertTrue(WebAdminService.tick(state, tailer, sent::add, state.lastFlush + 500));
            assertEquals(1, sent.size());
            JsonNode frame = JSON.readTree(sent.get(0));
            assertEquals(3, frame.size(), "three records, no headers: " + frame);
            for (JsonNode rec : frame) assertTrue(rec.has("eventLogRecord"), rec.toString());
            state.exec.shutdownNow();
        }
    }

    @Test
    void headerDetection() {
        assertTrue(AuditRecordProjection.isStreamHeader(header("a", "b")));
        assertTrue(AuditRecordProjection.isStreamHeader("\n  auditStreamHeader:\n    schema: 1"));
        assertFalse(AuditRecordProjection.isStreamHeader(record(0)));
        assertFalse(AuditRecordProjection.isStreamHeader("eventLogRecord:\n  auditStreamHeader: x"));
        assertFalse(AuditRecordProjection.isStreamHeader(null));
        assertFalse(AuditRecordProjection.isStreamHeader(""));
    }
}
