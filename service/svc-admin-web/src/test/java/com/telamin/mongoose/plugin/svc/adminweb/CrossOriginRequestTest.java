/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With the default {@code authMode: NONE} a page in the operator's browser must not be able to run admin
 * commands: not by a cross-origin "simple" POST (text/plain, no token, no cookie), not with a forged Origin,
 * and not by DNS rebinding a foreign name onto the loopback bind. Requests are raw HTTP so the Host and Origin
 * headers are exactly what a browser would send.
 */
class CrossOriginRequestTest {

    private WebAdminService svc;
    private int port;
    private final List<String> ran = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() {
        if (svc != null) svc.tearDown();
    }

    private void boot(String bindHost, List<String> allowedHosts) throws Exception {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            port = s.getLocalPort();
        }
        AdminCommandRegistry registry = (AdminCommandRegistry) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AdminCommandRegistry.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "processAdminCommandRequest" -> {
                        AdminCommandRequest req = (AdminCommandRequest) args[0];
                        ran.add(req.getCommand() + " " + req.getArguments());
                        req.getOutput().accept("ran " + req.getCommand());
                        yield null;
                    }
                    case "commandList" -> List.of("server.processors.stop");
                    case "toString" -> "stub-registry";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        svc = new WebAdminService();
        svc.setHost(bindHost);
        svc.setListenPort(port);
        svc.setAllowedHosts(allowedHosts);
        svc.adminRegistry(registry, "adminCommandRegistry");
        svc.init();
        svc.start();
    }

    record Reply(int status, String headers, String body) { }

    private Reply raw(String method, String path, String host, String... headerLines) throws Exception {
        String body = method.equals("POST") ? "{\"arguments\":[\"pricing/pricer\"]}" : "";
        StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        req.append("Host: ").append(host).append("\r\n");
        for (String h : headerLines) req.append(h).append("\r\n");
        req.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        req.append("Connection: close\r\n\r\n").append(body);
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(5_000);
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            String text = buf.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String head = split < 0 ? text : text.substring(0, split);
            int status = Integer.parseInt(head.split(" ", 3)[1]);
            return new Reply(status, head, split < 0 ? "" : text.substring(split + 4));
        }
    }

    private String[] session(String host) throws Exception {
        Reply login = raw("POST", "/api/session/login", host, "Content-Type: application/json");
        assertEquals(200, login.status(), login.body());
        Matcher cookie = Pattern.compile("(?im)^set-cookie: *([^;\\r\\n]+)").matcher(login.headers());
        Matcher csrf = Pattern.compile("\"csrfToken\"\\s*:\\s*\"([^\"]+)\"").matcher(login.body());
        assertTrue(cookie.find() && csrf.find(), login.headers() + login.body());
        return new String[]{cookie.group(1), csrf.group(1)};
    }

    @Test
    void crossOriginSimplePostIsRefusedAndRunsNothing() throws Exception {
        boot("127.0.0.1", List.of());
        String host = "127.0.0.1:" + port;
        Reply r = raw("POST", "/api/commands/server.processors.stop", host,
                "Content-Type: text/plain", "Origin: http://evil.example");
        assertEquals(403, r.status(), r.body());
        // no Origin and no session (what the old exemption let through) is refused too
        Reply noSession = raw("POST", "/api/commands/server.processors.stop", host, "Content-Type: text/plain");
        assertEquals(401, noSession.status(), noSession.body());
        assertTrue(noSession.body().contains("POST /api/session/login first"), noSession.body());
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
    }

    @Test
    void sameOriginPostWithSessionAndTokenIsAccepted() throws Exception {
        boot("127.0.0.1", List.of());
        String host = "127.0.0.1:" + port;
        String[] s = session(host);
        Reply r = raw("POST", "/api/commands/server.processors.stop", host, "Content-Type: application/json",
                "Origin: http://" + host, "Cookie: " + s[0], "X-CSRF-Token: " + s[1]);
        assertEquals(200, r.status(), r.body());
        assertEquals(List.of("server.processors.stop [pricing/pricer]"), ran);
        // a script (no Origin) with the session and token is accepted as well
        Reply script = raw("POST", "/api/commands/server.processors.stop", host, "Content-Type: application/json",
                "Cookie: " + s[0], "X-CSRF-Token: " + s[1]);
        assertEquals(200, script.status(), script.body());
    }

    @Test
    void aWrongTokenOrAForeignOriginWithAValidSessionIsRefused() throws Exception {
        boot("127.0.0.1", List.of());
        String host = "127.0.0.1:" + port;
        String[] s = session(host);
        assertEquals(401, raw("POST", "/api/commands/server.processors.stop", host, "Content-Type: application/json",
                "Cookie: " + s[0], "X-CSRF-Token: wrong").status());
        assertEquals(403, raw("POST", "/api/commands/server.processors.stop", host, "Content-Type: application/json",
                "Origin: http://evil.example", "Cookie: " + s[0], "X-CSRF-Token: " + s[1]).status());
        assertEquals(403, raw("POST", "/api/commands/server.processors.stop", host, "Content-Type: application/json",
                "Origin: null", "Cookie: " + s[0], "X-CSRF-Token: " + s[1]).status());
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
    }

    @Test
    void dnsRebindingHostIsRefusedEvenForReadsAndLogin() throws Exception {
        boot("127.0.0.1", List.of());
        String rebound = "evil.example:" + port;
        // the rebound page is same-origin to the browser: Origin and Host agree, only the name is foreign
        assertEquals(403, raw("POST", "/api/session/login", rebound, "Content-Type: application/json",
                "Origin: http://" + rebound).status());
        assertEquals(403, raw("GET", "/api/commands", rebound).status());
        assertEquals(403, raw("POST", "/api/commands/server.processors.stop", rebound,
                "Content-Type: text/plain", "Origin: http://" + rebound).status());
        assertTrue(ran.isEmpty(), "no command may run: " + ran);
        // loopback names stay accepted
        assertEquals(200, raw("GET", "/api/commands", "localhost:" + port).status());
        assertEquals(200, raw("GET", "/api/commands", "[::1]:" + port).status());
    }

    @Test
    void allowedHostsAdmitsANamedProxyHost() throws Exception {
        boot("127.0.0.1", List.of("admin.internal"));
        assertEquals(200, raw("GET", "/api/commands", "admin.internal").status());
        assertEquals(403, raw("GET", "/api/commands", "evil.example").status());
    }

    @Test
    void hostRules() {
        assertTrue(WebAdminService.hostAllowed("127.0.0.1:8181", "127.0.0.1", List.of()));
        assertTrue(WebAdminService.hostAllowed("LOCALHOST", "127.0.0.1", List.of()));
        assertFalse(WebAdminService.hostAllowed("127.0.0.1.evil.example", "127.0.0.1", List.of()));
        assertFalse(WebAdminService.hostAllowed(null, "127.0.0.1", List.of()));
        // off-box bind without an allow-list: the operator chose exposure; the Host header is not checked
        assertTrue(WebAdminService.hostAllowed("10.1.2.3:8181", "0.0.0.0", List.of()));
        assertFalse(WebAdminService.hostAllowed("evil.example", "0.0.0.0", List.of("admin.internal")));
        assertTrue(WebAdminService.originMatchesHost(null, "127.0.0.1:1"));
        assertTrue(WebAdminService.originMatchesHost("http://127.0.0.1:1", "127.0.0.1:1"));
        assertFalse(WebAdminService.originMatchesHost("http://127.0.0.1:2", "127.0.0.1:1"));
        assertFalse(WebAdminService.originMatchesHost("null", "127.0.0.1:1"));
    }
}
