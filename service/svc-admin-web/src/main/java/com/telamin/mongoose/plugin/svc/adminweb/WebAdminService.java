/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Browser-based admin & monitoring UI for Mongoose. Presentation layer over
 * {@link AdminCommandRegistry} — the same command surface that
 * {@code svc-admin-telnet} and {@code svc-admin-rest} drive.
 *
 * <p>M1: {@code /healthz}.<br>
 * M2: BASIC/BEARER auth on {@code /api/*}, HMAC-signed session cookie, CSRF
 * token on state-changing requests, {@code /api/session/{login,logout}}.<br>
 * M3–M7: see {@code design/svc-admin-web.md}.
 */
@Log4j2
public class WebAdminService implements EventFlowService<Object>, Lifecycle {

    public enum AuthMode {NONE, BASIC, BEARER}

    static final String SESSION_COOKIE = "mongoose_admin_session";
    static final String CSRF_HEADER    = "X-CSRF-Token";

    private Javalin javalin;
    private EventFlowManager eventFlowManager;
    private AdminCommandRegistry adminCommandRegistry;
    private byte[] resolvedSessionSecret;
    private final SecureRandom random = new SecureRandom();
    private MonitoringSampler monitoringSampler;
    private LogTail logTail;
    private final java.util.Set<WsContext> monitorClients = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<WsContext> logClients     = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // bind config
    @Getter @Setter private int    listenPort = 8181;
    @Getter @Setter private String host       = "127.0.0.1";
    @Getter @Setter private String basePath   = "/";

    // auth config
    @Getter @Setter private AuthMode authMode = AuthMode.NONE;
    @Getter @Setter private String username;
    @Setter         private String password;
    @Setter         private String bearerToken;
    @Getter @Setter private String realm = "mongoose-admin";

    // monitoring config (wiring lands in M4 / M5)
    @Getter @Setter private int    metricsIntervalMs = 1000;
    @Getter @Setter private int    logTailBuffer     = 500;
    @Getter @Setter private String loaderBaseDir;

    // session config
    @Setter         private String sessionSecret;
    @Getter @Setter private int    sessionMinutes = 60;

    @Override
    public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
        log.info("set eventFlowManager name:'{}' for web admin UI", serviceName);
        this.eventFlowManager = eventFlowManager;
    }

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        this.adminCommandRegistry = adminCommandRegistry;
    }

    // EventFlowService → EventSource contract: this admin endpoint does not
    // publish events into the dispatch pipeline. No-ops mirror svc-admin-rest.
    @Override
    public void setEventToQueuePublisher(EventToQueuePublisher<Object> targetQueue) {
        // no-op
    }

    @Override
    public void subscribe(EventSubscriptionKey<Object> eventSourceKey) {
        // no-op
    }

    @Override
    public void unSubscribe(EventSubscriptionKey<Object> eventSourceKey) {
        // no-op
    }

    @Override
    public void init() {
        if (metricsIntervalMs < 250) {
            log.warn("metricsIntervalMs {} below 250 ms minimum; clamping", metricsIntervalMs);
            metricsIntervalMs = 250;
        }
        if (authMode == AuthMode.BASIC && (resolvedUsername() == null || resolvedPassword() == null)) {
            throw new IllegalStateException("BASIC auth selected but username/password not configured");
        }
        if (authMode == AuthMode.BEARER && (resolvedBearerToken() == null || resolvedBearerToken().isEmpty())) {
            throw new IllegalStateException("BEARER auth selected but bearerToken not configured");
        }

        String resolvedSecret = resolveEnv(sessionSecret);
        if (resolvedSecret == null || resolvedSecret.isEmpty()) {
            // No secret configured → generate one for this JVM. Sessions invalidate on restart.
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            this.resolvedSessionSecret = generated;
            if (authMode != AuthMode.NONE) {
                log.warn("sessionSecret unset; using random per-JVM secret. Sessions will not survive restart.");
            }
        } else {
            this.resolvedSessionSecret = resolvedSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void start() {
        log.info("starting web admin UI on http://{}:{}{} (auth={})", host, listenPort, basePath, authMode);
        javalin = Javalin.create(config -> {
            // Bundled UI assets — htmx + Alpine SPA shell. Served from classpath
            // so a single jar is enough; no node toolchain, no staticDir config.
            config.staticFiles.add(staticFileConfig -> {
                staticFileConfig.hostedPath = "/";
                staticFileConfig.directory = "/web";
                staticFileConfig.location = Location.CLASSPATH;
            });
        }).start(host, listenPort);

        // Liveness probe — never auth-gated.
        javalin.get("/healthz", ctx -> ctx.result("OK"));

        // Auth filter applied to /api/* (session login/logout handled inside).
        javalin.before("/api/*", this::enforceAuthAndCsrf);

        // Session endpoints.
        javalin.post("/api/session/login", this::handleLogin);
        javalin.post("/api/session/logout", this::handleLogout);

        // Admin command surface — list + invoke. Auth filter above gates both.
        javalin.get("/api/commands", this::handleListCommands);
        javalin.post("/api/commands/{name}", this::handleInvokeCommand);

        // Dashboard endpoints.
        javalin.get("/api/server", this::handleServer);
        javalin.get("/api/jvm", this::handleJvm);

        // Conditional file picker for loader forms. Always mounted, but returns
        // 404 when loaderBaseDir is unset so the UI hides the tab automatically.
        javalin.get("/api/files", this::handleListFiles);

        // Monitoring WebSocket. Same auth filter applies to the HTTP upgrade
        // request because Javalin's before() runs on the upgrade. CSRF on WS
        // is carried as ?csrf=... query param (browsers can't add headers).
        javalin.before("/ws/*", this::enforceWsUpgradeAuth);
        javalin.ws("/ws/monitor", this::configureMonitorWs);
        javalin.ws("/ws/logs",    this::configureLogsWs);

        // Periodic sampler — broadcasts to all live monitor clients.
        monitoringSampler = new MonitoringSampler(metricsIntervalMs);
        monitoringSampler.subscribe(this::broadcastMonitorSnapshot);
        monitoringSampler.start();

        // Log tail — captures j.u.l records into a bounded ring buffer and
        // fans new lines out to subscribed /ws/logs clients.
        logTail = new LogTail(logTailBuffer);
        logTail.subscribe(this::broadcastLogLine);
        logTail.start();
    }

    @Override
    public void tearDown() {
        if (monitoringSampler != null) {
            monitoringSampler.stop();
            monitoringSampler = null;
        }
        if (logTail != null) {
            logTail.stop();
            logTail = null;
        }
        monitorClients.clear();
        logClients.clear();
        if (javalin != null) {
            log.info("stopping web admin UI");
            javalin.stop();
            javalin = null;
        }
    }

    // -------- auth filter --------

    private void enforceAuthAndCsrf(Context ctx) {
        String path = ctx.path();
        // The login endpoint itself is the auth bootstrap — it validates
        // credentials inline. Logout is auth-gated (you must be in a session
        // to log out of it).
        boolean isLogin = "/api/session/login".equals(path);

        if (!isLogin && authMode != AuthMode.NONE) {
            boolean authed = hasValidSessionCookie(ctx) || hasValidAuthHeader(ctx);
            if (!authed) {
                reject(ctx);
                return;
            }
        }

        // CSRF: every state-changing request needs the token. Login is exempt
        // because it doesn't have a session yet. GET/HEAD requests don't need
        // CSRF protection per the standard cookie-based threat model.
        if (!isLogin && requiresCsrf(ctx) && authMode != AuthMode.NONE) {
            SessionToken session = currentSession(ctx);
            if (session == null) {
                // Auth header alone (no cookie) is treated as a script client;
                // CSRF doesn't apply because there's no ambient cookie auth to
                // exploit. Skip the check in that case.
                return;
            }
            String presented = ctx.header(CSRF_HEADER);
            if (presented == null || !constantTimeEquals(presented, session.csrfToken)) {
                throw new UnauthorizedResponse("missing or invalid CSRF token", Map.of());
            }
        }
    }

    private boolean requiresCsrf(Context ctx) {
        HandlerType m = ctx.method();
        return m == HandlerType.POST || m == HandlerType.PUT || m == HandlerType.DELETE
                || m == HandlerType.PATCH;
    }

    private boolean hasValidSessionCookie(Context ctx) {
        return currentSession(ctx) != null;
    }

    private SessionToken currentSession(Context ctx) {
        String cookieValue = ctx.cookie(SESSION_COOKIE);
        return SessionToken.decode(cookieValue, resolvedSessionSecret, System.currentTimeMillis());
    }

    private boolean hasValidAuthHeader(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null) return false;
        if (authMode == AuthMode.BASIC) {
            if (!header.startsWith("Basic ")) return false;
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) return false;
            String u = decoded.substring(0, colon);
            String p = decoded.substring(colon + 1);
            return constantTimeEquals(u, resolvedUsername())
                    && constantTimeEquals(p, resolvedPassword());
        }
        if (authMode == AuthMode.BEARER) {
            if (!header.startsWith("Bearer ")) return false;
            return constantTimeEquals(header.substring("Bearer ".length()).trim(),
                    resolvedBearerToken());
        }
        return false;
    }

    private void reject(Context ctx) {
        if (authMode == AuthMode.BASIC) {
            ctx.header("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        } else if (authMode == AuthMode.BEARER) {
            ctx.header("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
        }
        throw new UnauthorizedResponse("unauthorized", Map.of());
    }

    // -------- session endpoints --------

    private void handleLogin(Context ctx) {
        if (authMode == AuthMode.NONE) {
            // No login needed; return a session anyway so the UI can read its CSRF token.
            issueSession(ctx, "anonymous");
            return;
        }

        LoginRequest body;
        try {
            body = ctx.bodyAsClass(LoginRequest.class);
        } catch (Exception e) {
            throw new UnauthorizedResponse("invalid login body", Map.of());
        }

        boolean ok = false;
        String userId = null;
        if (authMode == AuthMode.BASIC) {
            ok = body != null
                    && constantTimeEquals(body.username, resolvedUsername())
                    && constantTimeEquals(body.password, resolvedPassword());
            userId = body == null ? null : body.username;
        } else if (authMode == AuthMode.BEARER) {
            ok = body != null && constantTimeEquals(body.token, resolvedBearerToken());
            userId = "bearer";
        }
        if (!ok) {
            reject(ctx);
            return;
        }
        issueSession(ctx, userId);
    }

    private void issueSession(Context ctx, String userId) {
        long expiry = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sessionMinutes);
        String csrf = randomToken();
        SessionToken token = new SessionToken(userId, expiry, csrf);
        String cookieValue = token.encode(resolvedSessionSecret);

        Cookie cookie = new Cookie(SESSION_COOKIE, cookieValue);
        cookie.setHttpOnly(true);
        cookie.setSameSite(SameSite.STRICT);
        cookie.setPath("/");
        // Secure flag honoured when behind TLS reverse proxy.
        if ("https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto"))
                || "https".equalsIgnoreCase(ctx.scheme())) {
            cookie.setSecure(true);
        }
        cookie.setMaxAge((int) TimeUnit.MINUTES.toSeconds(sessionMinutes));
        ctx.cookie(cookie);

        ctx.json(Map.of(
                "userId", userId,
                "csrfToken", csrf,
                "expiresAt", expiry));
    }

    private void handleLogout(Context ctx) {
        ctx.removeCookie(SESSION_COOKIE, "/");
        ctx.result("OK");
    }

    // -------- admin command surface --------

    private void handleListCommands(Context ctx) {
        List<String> commands = adminCommandRegistry == null
                ? Collections.emptyList()
                : adminCommandRegistry.commandList();
        ctx.json(Map.of("commands", commands));
    }

    private void handleInvokeCommand(Context ctx) {
        if (adminCommandRegistry == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", List.of("AdminCommandRegistry not wired")));
            return;
        }

        String name = ctx.pathParam("name");
        InvokeRequest body;
        try {
            body = ctx.bodyAsClass(InvokeRequest.class);
        } catch (Exception e) {
            body = null;
        }

        AdminCommandRequest req = new AdminCommandRequest();
        req.setCommand(name);
        if (body != null && body.args != null) {
            req.setArguments(new ArrayList<>(body.args));
        }

        // output / errOutput may be called multiple times — buffer all into
        // lists. v1: cancellation and streaming are deferred (§10.1).
        List<String> outBuffer = Collections.synchronizedList(new ArrayList<>());
        List<String> errBuffer = Collections.synchronizedList(new ArrayList<>());
        req.setOutput(o -> outBuffer.add(String.valueOf(o)));
        req.setErrOutput(e -> errBuffer.add(String.valueOf(e)));

        try {
            adminCommandRegistry.processAdminCommandRequest(req);
        } catch (Exception e) {
            log.warn("admin command '{}' threw", name, e);
            errBuffer.add("exception: " + e.getMessage());
        }

        ctx.json(Map.of(
                "command", name,
                "output", new ArrayList<>(outBuffer),
                "err", new ArrayList<>(errBuffer)));
    }

    // -------- dashboard --------

    private void handleServer(Context ctx) {
        ctx.json(MonitoringSampler.serverInfo());
    }

    private void handleJvm(Context ctx) {
        ctx.json(MonitoringSampler.snapshot());
    }

    private void handleListFiles(Context ctx) {
        if (loaderBaseDir == null || loaderBaseDir.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "loaderBaseDir is not configured"));
            return;
        }
        java.nio.file.Path base;
        try {
            base = java.nio.file.Paths.get(loaderBaseDir).toRealPath();
        } catch (java.io.IOException e) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "loaderBaseDir does not exist: " + loaderBaseDir));
            return;
        }
        String rel = ctx.queryParam("path");
        if (rel == null) rel = "";
        // Reject absolute paths and obvious traversal attempts up front.
        if (rel.startsWith("/") || rel.startsWith("\\") || rel.contains("..")) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "path escape rejected"));
            return;
        }
        java.nio.file.Path target;
        try {
            target = base.resolve(rel).toRealPath();
        } catch (java.io.IOException e) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "no such path"));
            return;
        }
        // Belt+braces: after resolve+toRealPath the target must still be under
        // base. This catches symlinks pointing outside the sandbox.
        if (!target.startsWith(base)) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "path escape rejected"));
            return;
        }
        if (!java.nio.file.Files.isDirectory(target)) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "not a directory"));
            return;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(target)) {
            s.sorted().forEach(p -> {
                Map<String, Object> e = new java.util.LinkedHashMap<>();
                e.put("name", p.getFileName().toString());
                e.put("isDir", java.nio.file.Files.isDirectory(p));
                long size = -1;
                try {
                    if (!java.nio.file.Files.isDirectory(p)) size = java.nio.file.Files.size(p);
                } catch (java.io.IOException ignore) { }
                e.put("size", size);
                entries.add(e);
            });
        } catch (java.io.IOException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "list failed: " + e.getMessage()));
            return;
        }
        String relForResp = base.relativize(target).toString().replace('\\', '/');
        ctx.json(Map.of(
                "cwd", relForResp,
                "entries", entries));
    }

    // -------- WebSocket monitor --------

    private void enforceWsUpgradeAuth(Context ctx) {
        // Auth: same as /api/*. Origin allow-list: same-origin only by default
        // (browser cookies aren't sent on cross-origin WS upgrade anyway, but
        // belt+braces). CSRF on WS comes through ?csrf=... query param.
        if (authMode != AuthMode.NONE) {
            boolean authed = hasValidSessionCookie(ctx) || hasValidAuthHeader(ctx);
            if (!authed) {
                reject(ctx);
                return;
            }
            SessionToken session = currentSession(ctx);
            if (session != null) {
                String presented = ctx.queryParam("csrf");
                if (presented == null || !constantTimeEquals(presented, session.csrfToken)) {
                    throw new UnauthorizedResponse("missing or invalid CSRF query token", Map.of());
                }
            }
            // Header-auth (no cookie) clients skip the CSRF check, mirroring
            // the /api/* policy.
        }

        String origin = ctx.header("Origin");
        if (origin != null && !originAllowed(origin)) {
            throw new UnauthorizedResponse("origin not allowed: " + origin, Map.of());
        }
    }

    private boolean originAllowed(String origin) {
        // Default policy: same host:port we're bound to. Behind a reverse
        // proxy on a different host, this becomes a config knob; deferred.
        String expectedHttp = "http://" + host + ":" + listenPort;
        String expectedHttps = "https://" + host + ":" + listenPort;
        return origin.equals(expectedHttp) || origin.equals(expectedHttps);
    }

    private void configureMonitorWs(WsConfig ws) {
        ws.onConnect(ctx -> {
            monitorClients.add(ctx);
            // Send a fresh snapshot immediately so the dashboard populates
            // before the first scheduled tick lands.
            try {
                ctx.send(MonitoringSampler.snapshot());
            } catch (Exception e) {
                log.warn("initial monitor snapshot failed", e);
            }
        });
        ws.onClose(ctx -> monitorClients.remove(ctx));
        ws.onError(ctx -> {
            log.warn("monitor ws error", ctx.error());
            monitorClients.remove(ctx);
        });
    }

    private void broadcastMonitorSnapshot(MonitoringSampler.JvmSnapshot snapshot) {
        for (WsContext c : monitorClients) {
            try {
                if (c.session.isOpen()) {
                    c.send(snapshot);
                } else {
                    monitorClients.remove(c);
                }
            } catch (Exception e) {
                log.warn("monitor send failed; dropping client", e);
                monitorClients.remove(c);
            }
        }
    }

    // -------- WebSocket logs --------

    private void configureLogsWs(WsConfig ws) {
        ws.onConnect(ctx -> {
            logClients.add(ctx);
            // Replay buffered records so the panel populates with recent history
            // instead of starting blank.
            try {
                for (LogTail.LogLine line : logTail.snapshot()) {
                    ctx.send(line);
                }
            } catch (Exception e) {
                log.warn("initial log replay failed", e);
            }
        });
        ws.onClose(ctx -> logClients.remove(ctx));
        ws.onError(ctx -> {
            log.warn("log ws error", ctx.error());
            logClients.remove(ctx);
        });
    }

    private void broadcastLogLine(LogTail.LogLine line) {
        for (WsContext c : logClients) {
            try {
                if (c.session.isOpen()) {
                    c.send(line);
                } else {
                    logClients.remove(c);
                }
            } catch (Exception e) {
                // Best-effort delivery; subscriber list churn is fine.
                logClients.remove(c);
            }
        }
    }


    // -------- helpers --------

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
        return diff == 0;
    }

    private String resolvedUsername()    { return resolveEnv(username); }
    private String resolvedPassword()    { return resolveEnv(password); }
    private String resolvedBearerToken() { return resolveEnv(bearerToken); }

    private static String resolveEnv(String token) {
        if (token == null) return null;
        if (token.startsWith("$ENV.")) {
            String key = token.substring("$ENV.".length());
            String fromEnv = System.getenv(key);
            if (fromEnv != null) return fromEnv;
            return System.getProperty(token);
        }
        return token;
    }

    /** POST /api/session/login body. */
    public static class LoginRequest {
        public String username;
        public String password;
        public String token;
    }

    /** POST /api/commands/{name} body. */
    public static class InvokeRequest {
        public List<String> args;
    }
}
