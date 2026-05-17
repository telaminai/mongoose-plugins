/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

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
import java.util.Base64;
import java.util.List;

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
}
