/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminrest;

import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With {@code authMode: NONE} a page in the operator's browser must not be able to run admin commands: not by a
 * cross-origin "simple" POST (text/plain or form-encoded), not with {@code Origin: null}, and not by DNS rebinding a
 * foreign name onto the loopback bind. Scripts (no Origin, JSON body) keep working. Raw HTTP, so Host and Origin are
 * exactly what a browser sends.
 */
class CrossOriginRequestTest {

    private JavalinAdminCommandService svc;
    private int port;
    private final List<String> ran = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() {
        if (svc != null) svc.tearDown();
    }

    private void boot(JavalinAdminCommandService.AuthMode mode, List<String> allowedHosts) throws Exception {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            port = s.getLocalPort();
        }
        svc = new JavalinAdminCommandService();
        svc.setHost("127.0.0.1");
        svc.setListenPort(port);
        svc.setAuthMode(mode);
        svc.setAllowedHosts(allowedHosts);
        if (mode == JavalinAdminCommandService.AuthMode.BASIC) {
            svc.setUsername("u");
            svc.setPassword("p");
        }
        svc.adminRegistry(new AdminCommandRegistry() {
            @Override public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) { }
            @Override public void processAdminCommandRequest(AdminCommandRequest r) {
                ran.add(r.getCommand());
                r.getOutput().accept("ran " + r.getCommand());
            }
            @Override public List<String> commandList() { return List.of("help"); }
        }, "adminRegistry");
        svc.init();
    }

    record Reply(int status, String body) { }

    private Reply post(String path, String host, String body, String... headers) throws Exception {
        StringBuilder req = new StringBuilder("POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
        for (String h : headers) req.append(h).append("\r\n");
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        req.append("Content-Length: ").append(b.length).append("\r\nConnection: close\r\n\r\n");
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            sock.getInputStream().transferTo(buf);
            String text = buf.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            return new Reply(Integer.parseInt(text.split(" ", 3)[1]), split < 0 ? "" : text.substring(split + 4));
        }
    }

    private static final String JSON = "{\"command\":\"help\",\"arguments\":[]}";

    private String local() { return "127.0.0.1:" + port; }

    @Test
    void crossOriginSimplePostsAreRefusedAndRunNothing() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.NONE, List.of());
        String evil = "Origin: http://evil.example";
        List<Reply> replies = List.of(
                post("/admin", local(), JSON, "Content-Type: text/plain", evil),
                post("/admin", local(), "command=help", "Content-Type: application/x-www-form-urlencoded", evil),
                post("/api/help", local(), null, "Content-Type: text/plain", evil),
                post("/api/help", local(), null, evil),
                // even a JSON content type is refused from a foreign origin (preflight-free bypasses exist)
                post("/admin", local(), JSON, "Content-Type: application/json", evil));
        List<Integer> statuses = replies.stream().map(Reply::status).toList();
        assertEquals(List.of(403, 403, 403, 403, 403), statuses, "statuses " + statuses + ", ran " + ran);
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
    }

    @Test
    void aBodyThatIsNotDeclaredJsonIsRefusedWithoutAnOrigin() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.NONE, List.of());
        Reply text = post("/admin", local(), JSON, "Content-Type: text/plain");
        assertEquals(415, text.status(), text.body());
        assertTrue(text.body().contains("Content-Type: application/json"), text.body());
        assertEquals(415, post("/admin", local(), JSON).status(), "no content type with a body");
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
    }

    @Test
    void scriptsAndSameOriginPagesAreAccepted() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.NONE, List.of());
        assertEquals(200, post("/admin", local(), JSON, "Content-Type: application/json").status());
        assertEquals(200, post("/admin", local(), JSON, "Content-Type: application/json; charset=utf-8",
                "Origin: http://" + local()).status());
        assertEquals(200, post("/api/help", local(), null).status(), "curl -X POST with no body");
        assertEquals(200, post("/api/help", "localhost:" + port, null).status());
        assertEquals(List.of("help", "help", "help", "help"), ran);
    }

    @Test
    void nullOriginIsRefused() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.NONE, List.of());
        assertEquals(403, post("/admin", local(), JSON, "Content-Type: application/json", "Origin: null").status());
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
    }

    @Test
    void reboundHostIsRefused() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.NONE, List.of());
        String rebound = "evil.example:" + port;
        assertEquals(403, post("/admin", rebound, JSON, "Content-Type: application/json",
                "Origin: http://" + rebound).status());
        assertEquals(403, post("/api/help", rebound, null).status());
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
    }

    @Test
    void allowedHostsAdmitsANamedHost() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.NONE, List.of("admin.internal"));
        assertEquals(200, post("/api/help", "admin.internal", null).status());
        assertEquals(403, post("/api/help", "evil.example", null).status());
    }

    @Test
    void basicAuthScriptsKeepWorking() throws Exception {
        boot(JavalinAdminCommandService.AuthMode.BASIC, List.of());
        String auth = "Authorization: Basic " + Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, post("/admin", local(), JSON, "Content-Type: application/json", auth).status());
        assertEquals(200, post("/api/help", local(), null, auth).status());
        assertEquals(401, post("/api/help", local(), null).status());
    }
}
