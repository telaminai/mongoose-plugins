/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb.it;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.plugin.svc.adminrest.JavalinAdminCommandService;
import com.telamin.mongoose.plugin.svc.adminweb.WebAdminService;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.servercontrol.MongooseServerAdmin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Harness: a real Mongoose 1.0.33 server with audit capture and performance monitoring on, the
 * shipped {@link WebAdminService} and {@link JavalinAdminCommandService} bound to 127.0.0.1 on free
 * ports, and the admin command surface. Scenario fixtures add processors after boot.
 *
 * <p>Only the processors, the feed and the fault seams (reflection onto the group's capture resources
 * and limits, which {@code MongooseServer} does not expose) are harness. The control plane, the admin
 * commands and both web plugins are the shipped code.
 */
public final class ScenarioServer implements AutoCloseable {

    public final MongooseServer server;
    public final WebAdminService web;
    public final JavalinAdminCommandService rest;
    public final Fixtures.Feed feed = new Fixtures.Feed();
    public final Path auditDir;
    public final Path workDir;
    public final int webPort;
    public final int restPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ScenarioServer(long shutdownTimeoutMillis) throws Exception {
        workDir = Files.createTempDirectory("admin-web-it");
        auditDir = Files.createDirectories(workDir.resolve("audit"));
        webPort = freePort();
        restPort = freePort();

        web = new WebAdminService();
        web.setHost("127.0.0.1");
        web.setListenPort(webPort);
        web.setAuthMode(WebAdminService.AuthMode.NONE);

        rest = new JavalinAdminCommandService();
        rest.setHost("127.0.0.1");
        rest.setListenPort(restPort);

        AuditCaptureConfig audit = new AuditCaptureConfig();
        audit.setEnabled(true);
        audit.setBackend("chronicle");
        audit.setDirectory(auditDir.toString());
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setEnabled(true);
        perf.setAuditCapture(audit);

        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .addService(ServiceConfig.<AdminCommandRegistry>builder()
                        .service(new AdminCommandProcessor()).serviceClass(AdminCommandRegistry.class)
                        .name("adminCommandRegistry").build())
                .addService(ServiceConfig.builder().service(new MongooseServerAdmin()).name("serverAdmin").build())
                .addService(ServiceConfig.<WebAdminService>builder().service(web)
                        .serviceClass(WebAdminService.class).name("adminWebService").build())
                .addService(ServiceConfig.<JavalinAdminCommandService>builder().service(rest)
                        .serviceClass(JavalinAdminCommandService.class).name("adminRestService").build())
                .shutdownTimeoutMillis(shutdownTimeoutMillis)
                .build();
        cfg.setPerformanceMonitoring(perf);
        cfg.setControlWaitMillis(3_000);

        server = MongooseServer.bootServer(cfg);
        server.registerEventSource("feed", feed);
        awaitTrue("web admin up", () -> get("/healthz").statusCode() == 200, 10_000);
    }

    public String webBase() { return "http://127.0.0.1:" + webPort; }
    public String restBase() { return "http://127.0.0.1:" + restPort; }

    /** Add a processor to a group and wait for its outcome. */
    public void add(String group, String name, Supplier<DataFlow> supplier) {
        server.addEventProcessor(name, group, null, supplier);
    }

    // ---------------------------------------------------------------- HTTP

    public HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(webBase() + path)).GET().build());
    }

    /** POST to the web admin with the anonymous-session CSRF token (AuthMode.NONE still checks CSRF). */
    public HttpResponse<String> postWeb(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(webBase() + path))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrf())
                .header("Cookie", cookie == null ? "" : cookie)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    public HttpResponse<String> postRest(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(restBase() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    /** Runs an admin command through the web plugin's {@code POST /api/commands/{name}}. */
    public HttpResponse<String> command(String name, String... args) {
        StringBuilder sb = new StringBuilder("{\"args\":[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(args[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return postWeb("/api/commands/" + name, sb.append("]}").toString());
    }

    private String csrfToken;
    private String cookie;

    private String csrf() {
        if (csrfToken == null) {
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(webBase() + "/api/session/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            r.headers().firstValue("set-cookie").ifPresent(c -> cookie = c.split(";", 2)[0]);
            String body = r.body();
            int i = body.indexOf("\"csrf");
            if (i >= 0) {
                int colon = body.indexOf(':', i);
                int q1 = body.indexOf('"', colon + 1);
                int q2 = body.indexOf('"', q1 + 1);
                csrfToken = body.substring(q1 + 1, q2);
            } else {
                csrfToken = "";
            }
        }
        return csrfToken;
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP " + req.uri() + " failed: " + e, e);
        }
    }

    // ---------------------------------------------------------------- seams

    /** The group object; {@code MongooseServer} keeps its groups private, so the harness reaches in. */
    public ComposingEventProcessorAgent group(String name) {
        try {
            Field f = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
            f.setAccessible(true);
            Object runner = ((java.util.Map<?, ?>) f.get(server)).get(name);
            if (runner == null) throw new IllegalArgumentException("no group " + name);
            Method m = runner.getClass().getDeclaredMethod("group");
            m.setAccessible(true);
            return (ComposingEventProcessorAgent) m.invoke(runner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Wrap the group's capture resources so every physical close throws after really closing. */
    public void failCaptureCloses(String groupName) {
        ComposingEventProcessorAgent g = group(groupName);
        try {
            Field f = ComposingEventProcessorAgent.class.getDeclaredField("captureResources");
            f.setAccessible(true);
            var delegate = (com.telamin.mongoose.service.audit.AuditCaptureResources) f.get(g);
            g.setAuditCaptureResources(new Fixtures.FailingCloseResources(delegate));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void awaitTrue(String what, BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (RuntimeException e) {
                last = e;
            }
            try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        throw new AssertionError("timed out waiting for " + what + (last == null ? "" : " (last error " + last + ")"));
    }

    /** Same as the unit suite: reuse-addr off on 127.0.0.1, or macOS can hand out a port already held there. */
    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        }
    }

    /** The group's non-blocking status once no control request is outstanding (or after {@code millis}). */
    public com.telamin.mongoose.MongooseServer.ControlGroupStatus quietStatus(String group, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        var st = server.controlGroupStatus(group);
        while (st.outstandingRequests() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            st = server.controlGroupStatus(group);
        }
        return st;
    }

    /**
     * Stops the server. A scenario may deliberately leave it incomplete (quarantine, failed close), so
     * the two HTTP plugins are torn down explicitly afterwards: their ports must not outlive the test.
     */
    @Override
    public void close() {
        Fixtures.releaseAll();
        try {
            server.shutdown();
        } catch (RuntimeException ignored) {
            // an already-stopping server; the plugins are still closed below
        }
        try { web.tearDown(); } catch (RuntimeException ignored) { }
        try { rest.tearDown(); } catch (RuntimeException ignored) { }
    }

    public static List<String> lines(String s) {
        return List.of(s.split("\n"));
    }
}
