/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private HttpResponse<String> get(String path, String authHeader, CookieManager cm) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (authHeader != null) b.header("Authorization", authHeader);
        return client(cm).send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String authHeader, String csrf, CookieManager cm) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (authHeader != null) b.header("Authorization", authHeader);
        if (csrf != null) b.header("X-CSRF-Token", csrf);
        return client(cm).send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client(CookieManager cm) {
        HttpClient.Builder b = HttpClient.newBuilder();
        if (cm != null) b.cookieHandler(cm);
        return b.build();
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- M1 carry-forward ----------

    @Test
    void healthz_returns_200_unauth() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setAuthMode(WebAdminService.AuthMode.BASIC);
        svc.setUsername("alice");
        svc.setPassword("hunter2");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/healthz", null, null);
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertEquals("OK", r.body());
    }

    @Test
    void metrics_interval_clamped_to_minimum() {
        svc = new WebAdminService();
        svc.setMetricsIntervalMs(50);
        svc.init();
        Assertions.assertEquals(250, svc.getMetricsIntervalMs());
    }

    // ---------- M2 init-time fail-fast ----------

    @Test
    void basic_auth_missing_credentials_fails_init() {
        svc = new WebAdminService();
        svc.setAuthMode(WebAdminService.AuthMode.BASIC);
        Assertions.assertThrows(IllegalStateException.class, () -> svc.init());
    }

    @Test
    void bearer_auth_missing_token_fails_init() {
        svc = new WebAdminService();
        svc.setAuthMode(WebAdminService.AuthMode.BEARER);
        Assertions.assertThrows(IllegalStateException.class, () -> svc.init());
    }

    // ---------- M2 BASIC auth on /api/* ----------

    @Test
    void basic_auth_blocks_unauth_api_request() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        HttpResponse<String> r = post("/api/session/logout", null, null, null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    @Test
    void basic_auth_allows_with_valid_credentials_header() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        HttpResponse<String> r = post("/api/session/logout", null, basic("alice", "hunter2"), null, null);
        Assertions.assertEquals(200, r.statusCode(), "logout reachable with valid BASIC header");
    }

    @Test
    void basic_auth_rejects_wrong_credentials_header() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        HttpResponse<String> r = post("/api/session/logout", null, basic("alice", "wrong"), null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    // ---------- M2 session login flow ----------

    @Test
    void login_with_valid_credentials_returns_session_cookie_and_csrf() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        CookieManager cm = new CookieManager();
        String body = "{\"username\":\"alice\",\"password\":\"hunter2\"}";
        HttpResponse<String> r = post("/api/session/login", body, null, null, cm);

        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"csrfToken\""), "login body carries csrfToken");

        List<HttpCookie> cookies = cm.getCookieStore().getCookies();
        Assertions.assertTrue(
                cookies.stream().anyMatch(c -> WebAdminService.SESSION_COOKIE.equals(c.getName())),
                "login sets session cookie");
    }

    @Test
    void login_with_wrong_credentials_returns_401() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        String body = "{\"username\":\"alice\",\"password\":\"wrong\"}";
        HttpResponse<String> r = post("/api/session/login", body, null, null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    @Test
    void session_cookie_admits_subsequent_post_with_csrf() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        // Step 1: login → cookie + csrfToken
        CookieManager cm = new CookieManager();
        String loginBody = "{\"username\":\"alice\",\"password\":\"hunter2\"}";
        HttpResponse<String> login = post("/api/session/login", loginBody, null, null, cm);
        Assertions.assertEquals(200, login.statusCode());
        String csrf = extractCsrf(login.body());

        // Step 2: logout POST with cookie + CSRF header → 200
        HttpResponse<String> logout = post("/api/session/logout", null, null, csrf, cm);
        Assertions.assertEquals(200, logout.statusCode());
    }

    @Test
    void session_cookie_post_without_csrf_returns_401() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        CookieManager cm = new CookieManager();
        String loginBody = "{\"username\":\"alice\",\"password\":\"hunter2\"}";
        HttpResponse<String> login = post("/api/session/login", loginBody, null, null, cm);
        Assertions.assertEquals(200, login.statusCode());

        // POST without CSRF header → 401
        HttpResponse<String> logout = post("/api/session/logout", null, null, null, cm);
        Assertions.assertEquals(401, logout.statusCode(),
                "POST with session cookie but no CSRF token must be rejected");
    }

    // ---------- M2 BEARER ----------

    @Test
    void bearer_auth_allows_with_valid_token_header() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setAuthMode(WebAdminService.AuthMode.BEARER);
        svc.setBearerToken("secret-token");
        svc.init();
        svc.start();

        HttpResponse<String> r = post("/api/session/logout", null, "Bearer secret-token", null, null);
        Assertions.assertEquals(200, r.statusCode());
    }

    // ---------- M3 admin command surface ----------

    @Test
    void list_commands_returns_registered_commands() throws Exception {
        port = freePort();
        FakeRegistry registry = new FakeRegistry();
        registry.register("ping", (args, out, err) -> out.accept("pong"));
        registry.register("echo", (args, out, err) -> args.forEach(out::accept));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/commands", null, null);
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"ping\""), "body lists ping: " + r.body());
        Assertions.assertTrue(r.body().contains("\"echo\""), "body lists echo: " + r.body());
    }

    @Test
    void list_commands_without_registry_returns_empty() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/commands", null, null);
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"commands\""));
    }

    @Test
    void invoke_command_returns_captured_output() throws Exception {
        port = freePort();
        FakeRegistry registry = new FakeRegistry();
        registry.register("echo", (args, out, err) -> args.forEach(out::accept));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "test");
        svc.init();
        svc.start();

        // NONE auth → bootstrap a session for CSRF
        CookieManager cm = new CookieManager();
        HttpResponse<String> login = post("/api/session/login", "{}", null, null, cm);
        String csrf = extractCsrf(login.body());

        HttpResponse<String> r = post("/api/commands/echo",
                "{\"args\":[\"hello\",\"world\"]}", null, csrf, cm);

        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"hello\""), "output contains hello: " + r.body());
        Assertions.assertTrue(r.body().contains("\"world\""), "output contains world: " + r.body());
        Assertions.assertEquals(List.of("hello", "world"), registry.last("echo"));
    }

    @Test
    void invoke_command_blocked_without_csrf_when_authed() throws Exception {
        port = freePort();
        FakeRegistry registry = new FakeRegistry();
        registry.register("ping", (args, out, err) -> out.accept("pong"));

        svc = newBasicAuthService(port);
        svc.adminRegistry(registry, "test");
        svc.init();
        svc.start();

        // Establish session
        CookieManager cm = new CookieManager();
        HttpResponse<String> login = post("/api/session/login",
                "{\"username\":\"alice\",\"password\":\"hunter2\"}", null, null, cm);
        Assertions.assertEquals(200, login.statusCode());

        // POST without CSRF header
        HttpResponse<String> r = post("/api/commands/ping",
                "{\"args\":[]}", null, null, cm);
        Assertions.assertEquals(401, r.statusCode(), "POST without CSRF must be rejected");
    }

    @Test
    void invoke_command_unauth_returns_401() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        HttpResponse<String> r = post("/api/commands/anything", "{}", null, null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    // ---------- M4 dashboard endpoints ----------

    @Test
    void server_endpoint_returns_identity_json() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/server", null, null);
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"pid\""), "server body has pid: " + r.body());
        Assertions.assertTrue(r.body().contains("\"runtime\""), "server body has runtime: " + r.body());
        Assertions.assertTrue(r.body().contains("\"startTime\""), "server body has startTime: " + r.body());
    }

    @Test
    void jvm_endpoint_returns_snapshot() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/jvm", null, null);
        Assertions.assertEquals(200, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"heapUsed\""), "jvm body has heapUsed: " + r.body());
        Assertions.assertTrue(r.body().contains("\"threads\""), "jvm body has threads: " + r.body());
        Assertions.assertTrue(r.body().contains("\"queues\""), "jvm body has queues array: " + r.body());
    }

    @Test
    void server_endpoint_blocked_unauth() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/server", null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    // ---------- M6 file picker ----------

    @Test
    void files_endpoint_returns_404_when_loader_base_dir_unset() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/files", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void files_endpoint_lists_entries_under_base_dir(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Files.writeString(tmp.resolve("a.yaml"), "yaml: a");
        java.nio.file.Files.writeString(tmp.resolve("b.xml"),  "<b/>");
        java.nio.file.Files.createDirectory(tmp.resolve("sub"));

        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setLoaderBaseDir(tmp.toString());
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/files", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"a.yaml\""), "body lists a.yaml: " + r.body());
        Assertions.assertTrue(r.body().contains("\"b.xml\""),  "body lists b.xml: "  + r.body());
        Assertions.assertTrue(r.body().contains("\"sub\""),    "body lists sub: "    + r.body());
        Assertions.assertTrue(r.body().contains("\"isDir\":true"), "subdir marked isDir");
    }

    @Test
    void files_endpoint_rejects_path_traversal(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setLoaderBaseDir(tmp.toString());
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/files?path=../../etc", null, null);
        Assertions.assertEquals(400, r.statusCode());
    }

    @Test
    void files_endpoint_rejects_absolute_path(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setLoaderBaseDir(tmp.toString());
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/files?path=/etc/passwd", null, null);
        Assertions.assertEquals(400, r.statusCode());
    }

    @Test
    void files_endpoint_blocked_unauth() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.setLoaderBaseDir(System.getProperty("java.io.tmpdir"));
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/files", null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    // ---------- M6.5 source-navigation (/api/source) ----------
    // Two-tier resolution: filesystem sourceRoots (live-edit dev) →
    // classpath fallback (packaged generated source). Empty sourceRoots
    // keeps the panel disabled at the front gate even though the
    // classpath fallback would otherwise be free.

    @Test
    void source_endpoint_classpath_tier_works_without_sourceRoots() throws Exception {
        // Classpath tier is always-on — operators don't need to opt in via
        // sourceRoots for the in-jar source to be reachable (the source is
        // already in the deployable artefact; gating adds no protection).
        // Filesystem tier remains opt-in below.
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        // sourceRoots intentionally unset — defaults to empty list.
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/source?fqn=com.example.fakegen.StubProcessor", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"root\":\"classpath:\""),
                "classpath hit should be flagged via root marker: " + r.body());

        // Miss with empty sourceRoots still returns 404 — but the body says
        // classpath was attempted, not "sourceRoots not configured".
        HttpResponse<String> miss = get("/api/source?fqn=com.example.nothere.Ghost", null, null);
        Assertions.assertEquals(404, miss.statusCode());
        Assertions.assertTrue(miss.body().contains("classpathChecked"),
                "404 should advertise classpath was attempted even with no sourceRoots: " + miss.body());
    }

    @Test
    void source_endpoint_rejects_invalid_fqn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setSourceRoots(List.of(tmp.toString()));
        svc.init();
        svc.start();

        // missing fqn
        Assertions.assertEquals(400, get("/api/source", null, null).statusCode());
        // unqualified (no dot) — fails the FQN regex
        Assertions.assertEquals(400, get("/api/source?fqn=Foo", null, null).statusCode());
        // path-traversal characters fail the regex up front
        Assertions.assertEquals(400, get("/api/source?fqn=../etc", null, null).statusCode());
        Assertions.assertEquals(400, get("/api/source?fqn=com.example/Foo", null, null).statusCode());
    }

    @Test
    void source_endpoint_serves_file_from_configured_root(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path pkgDir = tmp.resolve("com/example/demo");
        java.nio.file.Files.createDirectories(pkgDir);
        java.nio.file.Files.writeString(pkgDir.resolve("Demo.java"),
                "package com.example.demo; public class Demo { /* on disk */ }");

        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setSourceRoots(List.of(tmp.toString()));
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/source?fqn=com.example.demo.Demo", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"path\":\"com/example/demo/Demo.java\""),
                "body should report path: " + r.body());
        Assertions.assertTrue(r.body().contains("on disk"),
                "body should embed source text: " + r.body());
        Assertions.assertTrue(r.body().contains("\"root\":\"" + tmp.toString().replace("\\", "\\\\") + "\""),
                "filesystem hit should report its rootSpec: " + r.body());
    }

    @Test
    void source_endpoint_handles_inner_class_via_outer_file(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // Outer$Inner FQN → maps to Outer.java path (inner classes live in
        // the same file as their declaring outer).
        java.nio.file.Path pkgDir = tmp.resolve("com/example/demo");
        java.nio.file.Files.createDirectories(pkgDir);
        java.nio.file.Files.writeString(pkgDir.resolve("Outer.java"),
                "package com.example.demo; public class Outer { static class Inner { } }");

        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setSourceRoots(List.of(tmp.toString()));
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/source?fqn=com.example.demo.Outer$Inner", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"path\":\"com/example/demo/Outer.java\""),
                "inner-class FQN should resolve to outer .java: " + r.body());
    }

    @Test
    void source_endpoint_falls_back_to_classpath_when_filesystem_misses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // sourceRoots points at an empty tmp dir → filesystem tier misses
        // for every FQN. The classpath tier should still surface the test
        // resource at src/test/resources/com/example/fakegen/StubProcessor.java.
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setSourceRoots(List.of(tmp.toString()));
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/source?fqn=com.example.fakegen.StubProcessor", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"root\":\"classpath:\""),
                "classpath hit should be flagged via root marker: " + r.body());
        Assertions.assertTrue(r.body().contains("stub-processor-marker"),
                "body should embed the stub source text: " + r.body());
    }

    @Test
    void source_endpoint_prefers_filesystem_over_classpath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // Filesystem tier wins when both exist — live-edit dev experience
        // beats packaged copy. Drop a .java at the same coordinates as the
        // test-resource stub, point sourceRoots at it, expect the FS copy
        // (with its distinct marker text) in the response.
        java.nio.file.Path pkgDir = tmp.resolve("com/example/fakegen");
        java.nio.file.Files.createDirectories(pkgDir);
        java.nio.file.Files.writeString(pkgDir.resolve("StubProcessor.java"),
                "package com.example.fakegen; public class StubProcessor { /* FILESYSTEM-WIN-MARKER */ }");

        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setSourceRoots(List.of(tmp.toString()));
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/source?fqn=com.example.fakegen.StubProcessor", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("FILESYSTEM-WIN-MARKER"),
                "filesystem hit should win when both tiers can serve: " + r.body());
        Assertions.assertFalse(r.body().contains("stub-processor-marker"),
                "classpath text must NOT appear when filesystem hit: " + r.body());
        Assertions.assertFalse(r.body().contains("\"root\":\"classpath:\""),
                "filesystem hit must NOT be flagged as classpath: " + r.body());
    }

    @Test
    void source_endpoint_404_when_no_tier_matches(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setSourceRoots(List.of(tmp.toString()));
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/source?fqn=com.example.nothere.Ghost", null, null);
        Assertions.assertEquals(404, r.statusCode());
        Assertions.assertTrue(r.body().contains("classpathChecked"),
                "404 body should advertise that classpath was attempted: " + r.body());
    }

    // ---------- M5 log tail ----------

    @Test
    void log_tail_buffer_size_configurable() {
        svc = new WebAdminService();
        svc.setLogTailBuffer(42);
        svc.init();
        Assertions.assertEquals(42, svc.getLogTailBuffer());
    }

    @Test
    void service_lifecycle_with_log_tail_clean_start_stop() {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.setLogTailBuffer(10);
        svc.init();
        svc.start();
        // No assertion needed — start+tearDown installing+removing the handler
        // without explosions covers the lifecycle wiring.
        svc.tearDown();
        svc = null;
    }

    @Test
    void ws_logs_endpoint_requires_auth() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        // Javalin's before("/ws/*") filter runs on the upgrade request, so a
        // plain GET to the WS path without credentials is rejected the same
        // way a real WS upgrade would be.
        HttpResponse<String> r = get("/ws/logs", null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    // ---------- M8 dispatcher introspection (controller-driven) ----------

    @Test
    void services_endpoint_reads_controller_and_classifies_feed_sink() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addService("adminWebService",
                new com.telamin.fluxtion.runtime.service.Service<>(
                        new Object(), Object.class, "adminWebService"));
        controller.addService("prices",
                new com.telamin.fluxtion.runtime.service.Service<>(
                        new FakeEventSource(), com.telamin.mongoose.service.EventSource.class, "prices"));
        controller.addService("pnl",
                new com.telamin.fluxtion.runtime.service.Service<>(
                        (com.telamin.fluxtion.runtime.output.MessageSink<Object>) o -> { },
                        com.telamin.fluxtion.runtime.output.MessageSink.class, "pnl"));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"name\":\"prices\""), r.body());
        Assertions.assertTrue(r.body().contains("\"type\":\"feed\""),    "feed classified:  " + r.body());
        Assertions.assertTrue(r.body().contains("\"type\":\"sink\""),    "sink classified:  " + r.body());
        Assertions.assertTrue(r.body().contains("\"type\":\"service\""), "plain classified: " + r.body());
    }

    @Test
    void services_endpoint_404_when_no_controller() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services", null, null);
        Assertions.assertEquals(404, r.statusCode(),
                "endpoint 404s without the controller so the UI hides the tab");
    }

    @Test
    void services_endpoint_blocked_unauth() throws Exception {
        port = freePort();
        svc = newBasicAuthService(port);
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services", null, null);
        Assertions.assertEquals(401, r.statusCode());
    }

    @Test
    void agents_endpoint_reads_controller_processors() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addProcessor("core",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("orderHandler", null));
        controller.addProcessor("core",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("riskHandler", null));
        controller.addProcessor("market",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("priceHandler", null));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/agents", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"group\":\"core\""),   r.body());
        Assertions.assertTrue(r.body().contains("\"group\":\"market\""), r.body());
        Assertions.assertTrue(r.body().contains("\"orderHandler\""),     r.body());
        Assertions.assertTrue(r.body().contains("\"riskHandler\""),      r.body());
        Assertions.assertTrue(r.body().contains("\"priceHandler\""),     r.body());
    }

    @Test
    void agents_endpoint_404_when_no_controller() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/agents", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void classify_service_recognises_known_roles() {
        Assertions.assertEquals("feed",    WebAdminService.classifyService(new FakeEventSource()));
        Assertions.assertEquals("sink",    WebAdminService.classifyService(
                (com.telamin.fluxtion.runtime.output.MessageSink<Object>) o -> { }));
        Assertions.assertEquals("service", WebAdminService.classifyService(new Object()));
        Assertions.assertEquals("service", WebAdminService.classifyService(null));
    }

    @Test
    void queues_endpoint_reads_event_flow_manager() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        // EventFlowManager is injected via the EventFlowService contract.
        svc.setEventFlowManager(new EventFlowManager(), "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/queues", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"sources\""), "body has sources array: " + r.body());
    }

    @Test
    void queues_endpoint_404_when_no_flow_manager() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/queues", null, null);
        Assertions.assertEquals(404, r.statusCode(),
                "endpoint 404s without an EventFlowManager so the UI hides the tab");
    }

    @Test
    void parse_event_sources_extracts_topology() {
        var parsed = WebAdminService.parseEventSources(
                "eventSource:prices\n"
                        + "\treadQueues:\n"
                        + "\t\tfeeds-agent/prices/onEventCallBack -> Queue@1\n"
                        + "eventSource:trades\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/trades/onEventCallBack -> Queue@2\n");
        Assertions.assertEquals(2, parsed.size());
        Assertions.assertEquals("prices", parsed.get(0).get("source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> q0 = (List<Map<String, Object>>) parsed.get(0).get("queues");
        Assertions.assertEquals(1, q0.size());
        Assertions.assertEquals("feeds-agent", q0.get(0).get("agentGroup"));
        Assertions.assertEquals("onEventCallBack", q0.get(0).get("callback"));
    }

    @Test
    void parse_event_sources_handles_no_readers() {
        var parsed = WebAdminService.parseEventSources("No event readers registered");
        Assertions.assertTrue(parsed.isEmpty());
    }

    @Test
    void consumers_by_feed_inverts_topology() {
        var parsed = WebAdminService.parseEventSources(
                "eventSource:prices\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/prices/onEventCallBack -> Queue@1\n"
                        + "\t\trisk-agent/prices/onEventCallBack -> Queue@2\n"
                        + "eventSource:trades\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/trades/onEventCallBack -> Queue@3\n");
        var byFeed = WebAdminService.consumersByFeed(parsed);

        Assertions.assertEquals(2, byFeed.size());
        var pricesConsumers = byFeed.get("prices");
        Assertions.assertNotNull(pricesConsumers);
        Assertions.assertEquals(2, pricesConsumers.size(),
                "prices feed has 2 consuming groups (pnl-agent, risk-agent)");
        Assertions.assertEquals("pnl-agent",  pricesConsumers.get(0).get("agentGroup"));
        Assertions.assertEquals("risk-agent", pricesConsumers.get(1).get("agentGroup"));
        Assertions.assertEquals("onEventCallBack", pricesConsumers.get(0).get("callback"));
    }

    @Test
    void feeds_by_agent_group_collates_per_group_feeds() {
        var parsed = WebAdminService.parseEventSources(
                "eventSource:prices\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/prices/onEventCallBack -> Queue@1\n"
                        + "eventSource:trades\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/trades/onEventCallBack -> Queue@2\n");
        var byGroup = WebAdminService.feedsByAgentGroup(parsed);

        Assertions.assertEquals(1, byGroup.size());
        var pnlFeeds = byGroup.get("pnl-agent");
        Assertions.assertNotNull(pnlFeeds);
        Assertions.assertEquals(2, pnlFeeds.size(),
                "pnl-agent consumes both prices and trades feeds");
        Assertions.assertEquals("prices", pnlFeeds.get(0).get("feed"));
        Assertions.assertEquals("trades", pnlFeeds.get(1).get("feed"));
    }

    @Test
    void expand_consumers_fans_out_processors_per_group() {
        var parsed = WebAdminService.parseEventSources(
                "eventSource:prices\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/prices/onEventCallBack -> Queue@1\n");
        var byFeed = WebAdminService.consumersByFeed(parsed);

        Map<String, java.util.Collection<com.telamin.mongoose.dutycycle.NamedEventProcessor>> procs =
                new java.util.LinkedHashMap<>();
        procs.put("pnl-agent", List.of(
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("pnlProcessor",  null),
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("riskProcessor", null)));

        var expanded = WebAdminService.expandConsumers(byFeed.get("prices"),
                g -> procs.getOrDefault(g, List.of()));
        Assertions.assertEquals(1, expanded.size());
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) expanded.get(0).get("processors");
        Assertions.assertEquals(List.of("pnlProcessor", "riskProcessor"), names,
                "every processor in the group is reported as a fanout consumer");
    }

    @Test
    void services_endpoint_includes_consumers_for_feeds() throws Exception {
        port = freePort();
        // Subclass injects fake topology — no real EventFlowManager needed.
        FakeServerController controller = new FakeServerController();
        controller.addService("prices",
                new com.telamin.fluxtion.runtime.service.Service<>(
                        new FakeEventSource(), com.telamin.mongoose.service.EventSource.class, "prices"));
        controller.addProcessor("pnl-agent",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("pnlProcessor", null));

        svc = new TopologyStubWebAdminService(
                "eventSource:prices\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/prices/onEventCallBack -> Queue@1\n");
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"consumers\""), "feed entry exposes consumers: " + r.body());
        Assertions.assertTrue(r.body().contains("\"agentGroup\":\"pnl-agent\""), r.body());
        Assertions.assertTrue(r.body().contains("\"pnlProcessor\""),
                "consumer entry fans out to processors in the group: " + r.body());
    }

    @Test
    void agents_endpoint_includes_feeds_for_groups() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addProcessor("pnl-agent",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("pnlProcessor", null));

        svc = new TopologyStubWebAdminService(
                "eventSource:trades\n"
                        + "\treadQueues:\n"
                        + "\t\tpnl-agent/trades/onEventCallBack -> Queue@1\n");
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/agents", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"group\":\"pnl-agent\""), r.body());
        Assertions.assertTrue(r.body().contains("\"feeds\""), "agent entry exposes feeds list: " + r.body());
        Assertions.assertTrue(r.body().contains("\"feed\":\"trades\""), r.body());
        Assertions.assertTrue(r.body().contains("\"callback\":\"onEventCallBack\""), r.body());
    }

    // ────── Reflective config summariser ──────────────────────────────────

    /** Sample with a mix of typical bean shapes to exercise the summariser. */
    public static class SampleConfig {
        public String getBootstrapServers() { return "localhost:9092"; }
        public int    getPollIntervalMs()   { return 100; }
        public boolean isAutoCommit()       { return true; }
        public String getPassword()         { return "hunter2"; }
        public String getApiKey()           { return "abc-def"; }
        public java.util.List<String> getTopics() { return java.util.List.of("a", "b", "c"); }
        public String getNotes()            { return "x".repeat(300); }
        public String getBroken()           { throw new RuntimeException("never returns"); }
        // duplicated getter pair → should de-dupe by property name
        public boolean isReady()            { return true; }
        public boolean getReady()           { return true; }
    }

    @Test
    void config_summary_extracts_bean_getters_and_skips_object_methods() {
        var props = WebAdminService.summarizeConfig(new SampleConfig());
        java.util.Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        for (var p : props) byName.put(String.valueOf(p.get("name")), p);

        Assertions.assertTrue(byName.containsKey("bootstrapServers"));
        Assertions.assertTrue(byName.containsKey("pollIntervalMs"));
        Assertions.assertTrue(byName.containsKey("autoCommit"));
        Assertions.assertFalse(byName.containsKey("class"), "getClass() must be skipped");
    }

    @Test
    void config_summary_masks_sensitive_values() {
        var props = WebAdminService.summarizeConfig(new SampleConfig());
        var pw = props.stream().filter(p -> "password".equals(p.get("name"))).findFirst().orElseThrow();
        Assertions.assertEquals("***", pw.get("value"));
        Assertions.assertEquals(Boolean.TRUE, pw.get("sensitive"));

        var ak = props.stream().filter(p -> "apiKey".equals(p.get("name"))).findFirst().orElseThrow();
        Assertions.assertEquals("***", ak.get("value"));
    }

    @Test
    void config_summary_summarises_collections_and_truncates_long_strings() {
        var props = WebAdminService.summarizeConfig(new SampleConfig());
        var topics = props.stream().filter(p -> "topics".equals(p.get("name"))).findFirst().orElseThrow();
        Assertions.assertTrue(String.valueOf(topics.get("value")).contains("size=3"),
                "collections summarised as Type size=N: " + topics.get("value"));

        var notes = props.stream().filter(p -> "notes".equals(p.get("name"))).findFirst().orElseThrow();
        String v = String.valueOf(notes.get("value"));
        Assertions.assertTrue(v.endsWith("…") && v.length() == 201,
                "long strings truncated to 200 chars plus ellipsis: len=" + v.length());
    }

    @Test
    void config_summary_swallows_getter_exceptions() {
        var props = WebAdminService.summarizeConfig(new SampleConfig());
        Assertions.assertTrue(props.stream().noneMatch(p -> "broken".equals(p.get("name"))),
                "a throwing getter is silently skipped rather than failing the whole summary");
    }

    @Test
    void config_summary_dedupes_get_is_pair() {
        var props = WebAdminService.summarizeConfig(new SampleConfig());
        long readyCount = props.stream().filter(p -> "ready".equals(p.get("name"))).count();
        Assertions.assertEquals(1, readyCount,
                "duplicate get/is pair collapses to a single 'ready' property");
    }

    @Test
    void sensitive_name_heuristic_catches_compound_names() {
        Assertions.assertTrue(WebAdminService.isSensitiveName("password"));
        Assertions.assertTrue(WebAdminService.isSensitiveName("dbPassword"));
        Assertions.assertTrue(WebAdminService.isSensitiveName("apiKey"));
        Assertions.assertTrue(WebAdminService.isSensitiveName("privateKey"));
        Assertions.assertTrue(WebAdminService.isSensitiveName("accessToken"));
        Assertions.assertFalse(WebAdminService.isSensitiveName("partitionKey"),
                "narrow 'key' alone is too broad — partitionKey shouldn't mask");
        Assertions.assertFalse(WebAdminService.isSensitiveName("bootstrapServers"));
    }

    @Test
    void services_config_endpoint_returns_reflected_properties() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addService("kafkaFeed",
                new com.telamin.fluxtion.runtime.service.Service<>(
                        new SampleConfig(), Object.class, "kafkaFeed"));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services/kafkaFeed/config", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"bootstrapServers\""), r.body());
        Assertions.assertTrue(r.body().contains("\"pollIntervalMs\""), r.body());
        Assertions.assertTrue(r.body().contains("\"value\":\"***\""), "password masked: " + r.body());
    }

    @Test
    void services_config_endpoint_404_unknown_service() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addService("known",
                new com.telamin.fluxtion.runtime.service.Service<>(new Object(), Object.class, "known"));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services/unknown/config", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void services_config_endpoint_404_when_no_controller() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/services/anything/config", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    // ────── Introspection-service driven /api/agents ──────────────────────

    @Test
    void agents_endpoint_uses_introspection_for_thread_and_subscriptions() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addProcessor("pnl-agent",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("pnlProcessor", null));

        // Hand-rolled introspection stub with a single agent group + processor +
        // one subscription. No real EventFlowManager needed for this code path.
        com.telamin.mongoose.service.introspection.MongooseIntrospectionService stub =
                new com.telamin.mongoose.service.introspection.MongooseIntrospectionService() {
            @Override
            public java.util.Map<String, com.telamin.mongoose.service.introspection.AgentGroupSnapshot> agentGroups() {
                var sub = new com.telamin.mongoose.service.introspection.SubscriptionInfo("trades", "onEventCallBack");
                var proc = new com.telamin.mongoose.service.introspection.ProcessorInfo(
                        "pnlProcessor", "com.example.Pnl", java.util.List.of(sub));
                var snap = new com.telamin.mongoose.service.introspection.AgentGroupSnapshot(
                        "pnl-agent", "processor",
                        "org.agrona.concurrent.BusySpinIdleStrategy",
                        "agent/pnl-agent", "RUNNABLE",
                        false, 5, java.util.List.of(proc));
                return java.util.Map.of("pnl-agent", snap);
            }
            @Override
            public java.util.Map<String, com.telamin.mongoose.service.introspection.FeedTopology> feedTopology() {
                return java.util.Map.of();
            }
        };

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.introspection(stub, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/agents", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("\"idleStrategyClass\":\"org.agrona.concurrent.BusySpinIdleStrategy\""), r.body());
        Assertions.assertTrue(r.body().contains("\"thread\":\"agent/pnl-agent\""), r.body());
        Assertions.assertTrue(r.body().contains("\"threadState\":\"RUNNABLE\""), r.body());
        Assertions.assertTrue(r.body().contains("\"subscriptions\""), r.body());
        Assertions.assertTrue(r.body().contains("\"feed\":\"trades\""),
                "per-processor subscription surfaces in members payload: " + r.body());
    }

    // ────── MonitoringSampler dynamic rate ────────────────────────────────

    @Test
    void monitoring_sampler_clamps_below_configured_minimum() {
        MonitoringSampler sampler = new MonitoringSampler(1000);
        sampler.setIntervalMs(250);
        Assertions.assertEquals(1000, sampler.currentIntervalMs(),
                "operator-configured minimum is the floor; UI cannot drive the sampler below it");
    }

    @Test
    void monitoring_sampler_honours_higher_rate() {
        MonitoringSampler sampler = new MonitoringSampler(1000);
        sampler.setIntervalMs(5000);
        Assertions.assertEquals(5000, sampler.currentIntervalMs());
        Assertions.assertFalse(sampler.isPaused());
    }

    @Test
    void monitoring_sampler_can_pause_and_resume() {
        MonitoringSampler sampler = new MonitoringSampler(1000);
        sampler.setPaused(true);
        Assertions.assertTrue(sampler.isPaused());
        // Calling setIntervalMs while paused implicitly resumes — matches the
        // WS protocol where any non-zero ms unpauses the client.
        sampler.setIntervalMs(2000);
        Assertions.assertFalse(sampler.isPaused());
        Assertions.assertEquals(2000, sampler.currentIntervalMs());
    }

    @Test
    void monitoring_sampler_no_tick_when_paused_and_started() throws Exception {
        // Use a very short floor so the test runs quickly. Subscribe a
        // counter, pause before start, wait three intervals — no ticks.
        MonitoringSampler sampler = new MonitoringSampler(50);
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        sampler.subscribe(s -> count.incrementAndGet());
        sampler.setPaused(true);
        sampler.start();
        Thread.sleep(220);
        sampler.stop();
        Assertions.assertEquals(0, count.get(),
                "paused sampler must not generate snapshots — that's the whole point of Off");
    }

    // ────── /api/processors/{group}/{name}/graphml ────────────────────────

    @Test
    void graphml_endpoint_returns_xml_when_resource_on_classpath() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addProcessor("core",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("stub", new GraphmlDataFlowStub()));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/processors/core/stub/graphml", null, null);
        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("<graphml"), "served graphml content: " + r.body());
        Assertions.assertTrue(r.body().contains("alpha"), r.body());
        Assertions.assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("application/xml"),
                "served as application/xml");
    }

    @Test
    void graphml_endpoint_404_with_hint_when_resource_missing() throws Exception {
        port = freePort();
        // Use the SampleConfig class (defined for the config tests) as the
        // DataFlow stand-in — its package has no .graphml resource, so the
        // endpoint must 404 with a structured body.
        FakeServerController controller = new FakeServerController();
        // SampleConfig isn't a DataFlow; build a different stub whose graphml
        // we deliberately don't ship.
        controller.addProcessor("core",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("missing",
                        new com.telamin.fluxtion.runtime.DataFlow() {
                            @Override public void onEvent(Object e) { }
                        }));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/processors/core/missing/graphml", null, null);
        Assertions.assertEquals(404, r.statusCode());
        Assertions.assertTrue(r.body().contains("\"expectedResource\""),
                "404 carries the resource path so the UI can render a friendly hint: " + r.body());
        Assertions.assertTrue(r.body().contains("\"hint\""), r.body());
    }

    @Test
    void graphml_endpoint_404_unknown_group() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/processors/no-such-group/anything/graphml", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void graphml_endpoint_404_unknown_processor_in_known_group() throws Exception {
        port = freePort();
        FakeServerController controller = new FakeServerController();
        controller.addProcessor("core",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("known", new GraphmlDataFlowStub()));
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.serverController(controller, "test");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/processors/core/unknown/graphml", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void graphml_endpoint_404_when_no_controller() throws Exception {
        port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.init();
        svc.start();

        HttpResponse<String> r = get("/api/processors/core/stub/graphml", null, null);
        Assertions.assertEquals(404, r.statusCode());
    }

    @Test
    void consumers_from_introspection_maps_feed_topology_to_consumer_shape() {
        com.telamin.mongoose.service.introspection.MongooseIntrospectionService stub =
                new com.telamin.mongoose.service.introspection.MongooseIntrospectionService() {
            @Override
            public java.util.Map<String, com.telamin.mongoose.service.introspection.AgentGroupSnapshot> agentGroups() {
                return java.util.Map.of();
            }
            @Override
            public java.util.Map<String, com.telamin.mongoose.service.introspection.FeedTopology> feedTopology() {
                var cons = new com.telamin.mongoose.service.introspection.FeedConsumer(
                        "pnl-agent", "onEventCallBack",
                        "pnl-agent/trades/onEventCallBack",
                        java.util.List.of("pnlProcessor"));
                return java.util.Map.of("trades",
                        new com.telamin.mongoose.service.introspection.FeedTopology("trades", java.util.List.of(cons)));
            }
        };
        var out = WebAdminService.consumersFromIntrospection(stub);
        Assertions.assertEquals(1, out.size());
        var trades = out.get("trades");
        Assertions.assertNotNull(trades);
        Assertions.assertEquals(1, trades.size());
        var c = trades.get(0);
        Assertions.assertEquals("pnl-agent", c.get("agentGroup"));
        Assertions.assertEquals("onEventCallBack", c.get("callback"));
        Assertions.assertEquals("pnl-agent/trades/onEventCallBack", c.get("path"));
        @SuppressWarnings("unchecked")
        var procs = (List<String>) c.get("processors");
        Assertions.assertEquals(List.of("pnlProcessor"), procs);
    }

    // ---------- helpers ----------

    private static WebAdminService newBasicAuthService(int port) {
        WebAdminService s = new WebAdminService();
        s.setListenPort(port);
        s.setHost("127.0.0.1");
        s.setAuthMode(WebAdminService.AuthMode.BASIC);
        s.setUsername("alice");
        s.setPassword("hunter2");
        return s;
    }

    private static String extractCsrf(String json) {
        int i = json.indexOf("\"csrfToken\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        return json.substring(q1 + 1, q2);
    }

    /** Minimal {@code EventSource} stub for classifyService tests. */
    static class FakeEventSource implements com.telamin.mongoose.service.EventSource<Object> {
        @Override public void subscribe(com.telamin.mongoose.service.EventSubscriptionKey<Object> k) { }
        @Override public void unSubscribe(com.telamin.mongoose.service.EventSubscriptionKey<Object> k) { }
        @Override public void setEventToQueuePublisher(
                com.telamin.mongoose.dispatch.EventToQueuePublisher<Object> q) { }
    }

    /** Minimal in-memory {@code MongooseServerController} for tests. */
    static class FakeServerController implements com.telamin.mongoose.service.servercontrol.MongooseServerController {
        private final Map<String, com.telamin.fluxtion.runtime.service.Service<?>> services = new java.util.LinkedHashMap<>();
        private final Map<String, java.util.Collection<com.telamin.mongoose.dutycycle.NamedEventProcessor>> procs = new java.util.LinkedHashMap<>();

        void addService(String name, com.telamin.fluxtion.runtime.service.Service<?> svc) {
            services.put(name, svc);
        }
        void addProcessor(String group, com.telamin.mongoose.dutycycle.NamedEventProcessor p) {
            procs.computeIfAbsent(group, g -> new ArrayList<>()).add(p);
        }

        @Override public Map<String, com.telamin.fluxtion.runtime.service.Service<?>> registeredServices() { return services; }
        @Override public Map<String, java.util.Collection<com.telamin.mongoose.dutycycle.NamedEventProcessor>> registeredProcessors() { return procs; }
        @Override public void addEventProcessor(String n, String g, org.agrona.concurrent.IdleStrategy i,
                java.util.function.Supplier<com.telamin.fluxtion.runtime.DataFlow> f) { }
        @Override public void stopService(String n)  { }
        @Override public void startService(String n) { }
        @Override public void stopProcessor(String g, String n) { }
        // Mongoose 1.0.18 added these to MongooseServerController. The
        // admin-web stub doesn't exercise them yet — no-op overrides
        // keep the test double satisfying the interface.
        @Override public void registerService(com.telamin.fluxtion.runtime.service.Service<?> service) { }
        @Override public <T> void registerEventSource(String sourceName, com.telamin.mongoose.service.EventSource<T> eventSource) { }
        @Override public void removeService(String serviceName) { }
    }

    /**
     * Test-only subclass that injects a canned topology dump instead of reading
     * a live {@link EventFlowManager}. Lets us exercise the cross-link
     * enrichment without standing up real event sources.
     */
    static class TopologyStubWebAdminService extends WebAdminService {
        private final String topologyDump;
        TopologyStubWebAdminService(String topologyDump) { this.topologyDump = topologyDump; }
        @Override
        List<Map<String, Object>> currentTopologySources() {
            return WebAdminService.parseEventSources(topologyDump);
        }
    }

    /** Minimal in-memory AdminCommandRegistry for tests. */
    static class FakeRegistry implements AdminCommandRegistry {
        private final Map<String, AdminFunction<Object, Object>> commands = new HashMap<>();
        private final Map<String, List<String>> lastArgs = new HashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) {
            commands.put(name, (AdminFunction<Object, Object>) command);
        }

        @Override
        public void processAdminCommandRequest(AdminCommandRequest command) {
            AdminFunction<Object, Object> fn = commands.get(command.getCommand());
            if (fn == null) {
                command.getErrOutput().accept("unknown command: " + command.getCommand());
                return;
            }
            lastArgs.put(command.getCommand(), new ArrayList<>(command.getArguments()));
            fn.processAdminCommand(command.getArguments(), command.getOutput(), command.getErrOutput());
        }

        @Override
        public List<String> commandList() {
            return new ArrayList<>(commands.keySet());
        }

        void register(String name, AdminFunction<Object, Object> fn) {
            registerCommand(name, fn);
        }

        List<String> last(String cmd) {
            return lastArgs.get(cmd);
        }
    }
}
