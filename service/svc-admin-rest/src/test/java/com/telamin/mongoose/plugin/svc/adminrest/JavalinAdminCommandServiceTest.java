/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminrest;

import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

class JavalinAdminCommandServiceTest {

    private JavalinAdminCommandService svc;
    private int port;
    private final StubRegistry registry = new StubRegistry();

    /** Answers every command with {@link #reply}, recording what it was asked. */
    static class StubRegistry implements AdminCommandRegistry {
        final List<AdminCommandRequest> requests = new ArrayList<>();
        String reply = "ok";

        @Override
        public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) {
        }

        @Override
        public void processAdminCommandRequest(AdminCommandRequest request) {
            requests.add(request);
            request.getOutput().accept(reply);
        }

        @Override
        public List<String> commandList() {
            return List.of();
        }
    }

    private void startNoAuth(boolean withRegistry) {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        if (withRegistry) {
            svc.adminRegistry(registry, "adminRegistry");
        }
        svc.init();
    }

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

    private HttpResponse<String> post(String path, String body, String authHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (authHeader != null) b.header("Authorization", authHeader);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void no_auth_allows_request() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.init();

        HttpResponse<String> r = post("/api/help", null, null);
        Assertions.assertEquals(200, r.statusCode());
    }

    @Test
    void basic_auth_missing_header_returns_401_with_www_authenticate() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BASIC);
        svc.setUsername("admin");
        svc.setPassword("s3cret");
        svc.init();

        HttpResponse<String> r = post("/api/help", null, null);
        Assertions.assertEquals(401, r.statusCode());
        Assertions.assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("")
                .startsWith("Basic"));
    }

    @Test
    void basic_auth_wrong_password_returns_401() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BASIC);
        svc.setUsername("admin");
        svc.setPassword("s3cret");
        svc.init();

        String bad = "Basic " + Base64.getEncoder().encodeToString("admin:wrong".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = post("/api/help", null, bad);
        Assertions.assertEquals(401, r.statusCode());
    }

    @Test
    void basic_auth_correct_creds_returns_200() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BASIC);
        svc.setUsername("admin");
        svc.setPassword("s3cret");
        svc.init();

        String ok = "Basic " + Base64.getEncoder().encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = post("/api/help", null, ok);
        Assertions.assertEquals(200, r.statusCode());
    }

    @Test
    void bearer_auth_missing_header_returns_401() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BEARER);
        svc.setBearerToken("token-123");
        svc.init();

        HttpResponse<String> r = post("/api/help", null, null);
        Assertions.assertEquals(401, r.statusCode());
        Assertions.assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("")
                .startsWith("Bearer"));
    }

    @Test
    void bearer_auth_correct_token_returns_200() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BEARER);
        svc.setBearerToken("token-123");
        svc.init();

        HttpResponse<String> r = post("/api/help", null, "Bearer token-123");
        Assertions.assertEquals(200, r.statusCode());
    }

    @Test
    void bearer_auth_wrong_token_returns_401() throws Exception {
        port = freePort();
        svc = new JavalinAdminCommandService();
        svc.setListenPort(port);
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BEARER);
        svc.setBearerToken("token-123");
        svc.init();

        HttpResponse<String> r = post("/api/help", null, "Bearer wrong");
        Assertions.assertEquals(401, r.statusCode());
    }

    @Test
    void basic_mode_without_creds_throws_on_init() {
        svc = new JavalinAdminCommandService();
        svc.setListenPort(freePort());
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BASIC);
        // no username/password set
        Assertions.assertThrows(IllegalStateException.class, svc::init);
        svc = null;
    }

    @Test
    void bearer_mode_without_token_throws_on_init() {
        svc = new JavalinAdminCommandService();
        svc.setListenPort(freePort());
        svc.setHost("127.0.0.1");
        svc.adminRegistry(registry, "adminRegistry");
        svc.setAuthMode(JavalinAdminCommandService.AuthMode.BEARER);
        // no bearer token set
        Assertions.assertThrows(IllegalStateException.class, svc::init);
        svc = null;
    }

    @Test
    void tear_down_without_init_is_safe() {
        svc = new JavalinAdminCommandService();
        Assertions.assertDoesNotThrow(svc::tearDown);
        svc = null;
    }

    @Test
    void admin_command_is_run_and_the_reply_is_escaped_json() throws Exception {
        registry.reply = "line one\nsays \"quoted\"\ttabbed";
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"cache.keys\",\"arguments\":[\"a\",\"b\"]}", null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals(1, registry.requests.size());
        Assertions.assertEquals("cache.keys", registry.requests.get(0).getCommand());
        Assertions.assertEquals(List.of("a", "b"), registry.requests.get(0).getArguments());
        Assertions.assertEquals("{\"message\":\"line one\\nsays \\\"quoted\\\"\\ttabbed\"}", r.body());
    }

    @Test
    void admin_body_without_a_command_is_a_400_naming_the_shape() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"arguments\":[]}", null);

        Assertions.assertEquals(400, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("command"), r.body());
        Assertions.assertTrue(registry.requests.isEmpty(), "nothing run");
    }

    @Test
    void admin_body_that_is_not_json_is_a_400() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{not json", null);

        Assertions.assertEquals(400, r.statusCode(), r.body());
        Assertions.assertTrue(registry.requests.isEmpty(), "nothing run");
    }

    @Test
    void admin_body_with_args_instead_of_arguments_is_a_400_that_says_so() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\",\"args\":[\"x\"]}", null);

        Assertions.assertEquals(400, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("arguments"), r.body());
        Assertions.assertTrue(registry.requests.isEmpty(), "nothing run");
    }

    @Test
    void admin_without_a_registry_is_a_503() throws Exception {
        startNoAuth(false);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\",\"arguments\":[]}", null);

        Assertions.assertEquals(503, r.statusCode(), r.body());
    }

    @Test
    void api_action_without_a_registry_is_a_503() throws Exception {
        startNoAuth(false);

        HttpResponse<String> r = post("/api/help", null, null);

        Assertions.assertEquals(503, r.statusCode(), r.body());
    }

    @Test
    void api_action_is_run_and_replies() throws Exception {
        registry.reply = "a \"b\"";
        startNoAuth(true);

        HttpResponse<String> r = post("/api/help", null, null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("help", registry.requests.get(0).getCommand());
        // Javalin's ctx.json writes a String as-is; structured outputs are serialised as JSON
        Assertions.assertEquals("a \"b\"", r.body());
    }
}
