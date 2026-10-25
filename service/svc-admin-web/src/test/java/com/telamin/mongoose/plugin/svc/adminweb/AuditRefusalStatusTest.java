/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mongoose 1.0.33-rc1 refuses capture start/stop with a reason (IllegalStateException for a limit, a failed or
 * timed-out open, a closed group or a cancelled request; IllegalArgumentException for an unknown or ambiguous
 * name). {@code POST /api/audit/{processor}/start|stop} used to turn every IllegalStateException - and every
 * exception from stop - into a bare 500 "Server Error", so the UI, which reads {@code err}, showed "HTTP 500".
 */
class AuditRefusalStatusTest {

    private WebAdminService svc;
    private int port;

    @AfterEach
    void cleanup() {
        if (svc != null) svc.tearDown();
    }

    private void boot() throws Exception {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            port = s.getLocalPort();
        }
        MongooseAuditCaptureService capture = (MongooseAuditCaptureService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{MongooseAuditCaptureService.class}, (proxy, m, args) -> {
                    String name = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
                    switch (m.getName()) {
                        case "start", "stop" -> {
                            if (name.equals("limit")) throw new IllegalStateException(
                                    "audit start for 'limit' FAILED: open failed: physical capture resource limit reached");
                            if (name.equals("pricer")) throw new IllegalArgumentException(
                                    "processor 'pricer' is ambiguous: use one of [gA/pricer, gB/pricer]");
                            return null;
                        }
                        case "isRecording" -> { return false; }
                        case "toString" -> { return "stub-capture"; }
                        case "hashCode" -> { return 1; }
                        case "equals" -> { return proxy == args[0]; }
                        default -> { return null; }
                    }
                });
        svc = new WebAdminService();
        svc.setHost("127.0.0.1");
        svc.setListenPort(port);
        svc.auditCaptureService(capture, MongooseAuditCaptureService.SERVICE_NAME);
        svc.init();
        svc.start();
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        return c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void refusedStartIs409WithTheReason() throws Exception {
        boot();
        HttpResponse<String> r = post("/api/audit/limit/start");
        assertEquals(409, r.statusCode(), r.body());
        assertTrue(r.body().contains("physical capture resource limit reached"), r.body());
    }

    @Test
    void refusedStopIs409WithTheReason() throws Exception {
        boot();
        HttpResponse<String> r = post("/api/audit/limit/stop");
        assertEquals(409, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"err\""), r.body());
    }

    @Test
    void ambiguousNameOnStopNamesTheSelectors() throws Exception {
        boot();
        HttpResponse<String> r = post("/api/audit/pricer/stop");
        assertEquals(404, r.statusCode(), r.body());
        assertTrue(r.body().contains("gA/pricer, gB/pricer"), r.body());
    }
}
