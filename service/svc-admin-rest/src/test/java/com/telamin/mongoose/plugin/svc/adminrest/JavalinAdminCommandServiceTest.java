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

    /**
     * Answers every command with {@link #replies} (or {@link #reply} when no script is set), recording what it was
     * asked. Mirrors the real registry: {@code new ArrayList<>(request.getArguments())} throws on null arguments.
     */
    static class StubRegistry implements AdminCommandRegistry {
        final List<AdminCommandRequest> requests = new ArrayList<>();
        List<String> commands = List.of("help", "cache.keys", "silent", "multi", "obj");
        Object reply = "ok";
        /** a scripted reply sequence: each entry is {"out"|"err", value}; null means reply once with {@link #reply} */
        List<Object[]> replies;

        @Override
        public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) {
        }

        @Override
        public void processAdminCommandRequest(AdminCommandRequest request) {
            requests.add(request);
            List<String> args = new ArrayList<>(request.getArguments()); // as mongoose AdminCommand does
            args.add(0, request.getCommand());
            if ("silent".equals(request.getCommand())) {
                return;
            }
            if (replies == null) {
                request.getOutput().accept(reply);
                return;
            }
            for (Object[] r : replies) {
                ("err".equals(r[0]) ? request.getErrOutput() : request.getOutput()).accept(r[1]);
            }
        }

        @Override
        public List<String> commandList() {
            return commands;
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
        // a single String output is written as-is (text/plain); structured outputs are serialised as JSON
        Assertions.assertEquals("a \"b\"", r.body());
    }

    // ---- post-review: null arguments, multiple replies, unknown commands, 400 wording, /api content type ----

    @Test
    void admin_null_arguments_are_treated_as_an_empty_list() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\",\"arguments\":null}", null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals(List.of(), registry.requests.get(0).getArguments());
        Assertions.assertEquals("{\"message\":\"ok\"}", r.body());
    }

    @Test
    void admin_missing_arguments_are_an_empty_list() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\"}", null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals(List.of(), registry.requests.get(0).getArguments());
    }

    @Test
    void admin_multiple_replies_are_joined_into_one_response_with_failures_marked() throws Exception {
        registry.replies = List.of(new Object[]{"out", "first"}, new Object[]{"out", "second"},
                new Object[]{"err", "broken"});
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"multi\",\"arguments\":[]}", null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("{\"message\":\"first\\nsecond\\nFailure - broken\"}", r.body());
    }

    @Test
    void admin_a_single_error_reply_keeps_the_failure_prefix() throws Exception {
        registry.replies = List.<Object[]>of(new Object[]{"err", "nope"});
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"multi\",\"arguments\":[]}", null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("{\"message\":\"Failure - nope\"}", r.body());
    }

    @Test
    void a_reply_after_the_call_returned_is_ignored() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\",\"arguments\":[]}", null);
        Assertions.assertEquals("{\"message\":\"ok\"}", r.body());

        // a completion timeout ("cancelled"/"abandoned") can reply after the handler returned: it must not throw
        // or touch the finished request
        AdminCommandRequest finished = registry.requests.get(0);
        Assertions.assertDoesNotThrow(() -> finished.getOutput().accept("abandoned"));
        Assertions.assertDoesNotThrow(() -> finished.getErrOutput().accept("cancelled"));
        // the server still serves the next request normally
        HttpResponse<String> next = post("/admin", "{\"command\":\"help\",\"arguments\":[]}", null);
        Assertions.assertEquals("{\"message\":\"ok\"}", next.body());
    }

    @Test
    void admin_unknown_command_is_a_404_and_nothing_is_run() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"no.such\",\"arguments\":[]}", null);

        Assertions.assertEquals(404, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().startsWith("{\"message\":\"unknown command 'no.such'"), r.body());
        Assertions.assertTrue(registry.requests.isEmpty(), "nothing run");
    }

    @Test
    void api_unknown_command_is_a_404() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/api/no.such", null, null);

        Assertions.assertEquals(404, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("unknown command 'no.such'"), r.body());
        Assertions.assertTrue(registry.requests.isEmpty(), "nothing run");
    }

    @Test
    void admin_known_command_with_no_reply_is_an_empty_message() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"silent\",\"arguments\":[]}", null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("{\"message\":\"\"}", r.body());
    }

    @Test
    void api_known_command_with_no_reply_is_an_empty_message() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/api/silent", null, null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("{\"message\":\"\"}", r.body());
    }

    @Test
    void bad_body_400_has_no_jackson_internals_and_no_args_hint_without_args() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{not json", null);

        Assertions.assertEquals(400, r.statusCode(), r.body());
        Assertions.assertFalse(r.body().contains("'args'"), r.body());
        Assertions.assertFalse(r.body().contains("errOutput"), r.body());
        Assertions.assertFalse(r.body().contains("com.fasterxml"), r.body());
        Assertions.assertTrue(r.body().contains("line 1"), "names the position: " + r.body());
    }

    @Test
    void bad_field_400_names_the_field_without_jackson_internals() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\",\"arguments\":{\"a\":1}}", null);

        Assertions.assertEquals(400, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("'arguments'"), r.body());
        Assertions.assertFalse(r.body().contains("'args'"), r.body());
        Assertions.assertFalse(r.body().contains("errOutput"), r.body());
        Assertions.assertTrue(registry.requests.isEmpty(), "nothing run");
    }

    @Test
    void args_400_says_arguments_not_args_without_jackson_internals() throws Exception {
        startNoAuth(true);

        HttpResponse<String> r = post("/admin", "{\"command\":\"help\",\"args\":[\"x\"]}", null);

        Assertions.assertEquals(400, r.statusCode(), r.body());
        Assertions.assertTrue(r.body().contains("'arguments', not 'args'"), r.body());
        Assertions.assertFalse(r.body().contains("errOutput"), r.body());
    }

    @Test
    void api_string_output_is_text_plain() throws Exception {
        registry.reply = "a \"b\"";
        startNoAuth(true);

        HttpResponse<String> r = post("/api/help", null, null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("a \"b\"", r.body());
        Assertions.assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),
                r.headers().map().toString());
    }

    @Test
    void api_structured_output_is_json() throws Exception {
        registry.reply = java.util.Map.of("k", "v");
        startNoAuth(true);

        HttpResponse<String> r = post("/api/obj", null, null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("{\"k\":\"v\"}", r.body());
        Assertions.assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void api_multiple_replies_are_joined_with_failures_marked() throws Exception {
        registry.replies = List.of(new Object[]{"out", "first"}, new Object[]{"out", "second"},
                new Object[]{"err", "broken"});
        startNoAuth(true);

        HttpResponse<String> r = post("/api/multi", null, null);

        Assertions.assertEquals(200, r.statusCode(), r.body());
        Assertions.assertEquals("{\"message\":\"first\\nsecond\\nFailure - broken\"}", r.body());
    }
}
