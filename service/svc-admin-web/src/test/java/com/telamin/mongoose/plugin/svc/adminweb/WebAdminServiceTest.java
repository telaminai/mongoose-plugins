/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

class WebAdminServiceTest {

    private WebAdminService svc;
    private int port;

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void cleanup() {
        if (svc != null) {
            svc.tearDown();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthz_returns_200() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/healthz");
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertEquals("OK", r.body());
    }

    @Test
    void metrics_interval_clamped_to_minimum() {
        svc = new WebAdminService();
        svc.setMetricsIntervalMs(50); // below the 250 ms floor
        svc.init();

        Assertions.assertEquals(250, svc.getMetricsIntervalMs(),
                "metricsIntervalMs below 250 ms must be clamped at init()");
    }

    @Test
    void tearDown_is_idempotent() {
        svc = new WebAdminService();
        svc.setListenPort(freePort());
        svc.init();
        svc.start();

        svc.tearDown();
        svc.tearDown(); // must not throw
        svc = null;     // prevent @AfterEach double-teardown
    }
}
