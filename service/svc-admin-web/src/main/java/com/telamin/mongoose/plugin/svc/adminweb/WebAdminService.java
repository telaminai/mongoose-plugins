/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.introspection.MongooseIntrospectionService;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private MongooseServerController serverController;
    private MongooseIntrospectionService introspection;
    private com.telamin.mongoose.service.counters.MongooseCountersService countersService;
    private com.telamin.mongoose.service.counters.MongooseLatencyService latencyService;
    private com.telamin.mongoose.service.audit.MongooseAuditCaptureService auditCapture;
    private com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService auditIntrospection;
    private byte[] resolvedSessionSecret;
    private final SecureRandom random = new SecureRandom();
    private MonitoringSampler monitoringSampler;
    private LogTail logTail;
    private final java.util.Set<WsContext> monitorClients = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Per-client desired rate (ms). Value {@code 0} means the client has
     * picked "Off" — the sampler skips them, and when every client is off
     * we pause the sampler so it stops allocating snapshots. Re-evaluated
     * whenever this map changes.
     */
    private final java.util.Map<WsContext, Long> monitorClientRates = new java.util.concurrent.ConcurrentHashMap<>();
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

    // source-navigation config — directories searched (in order) when the
    // Processor graph node-tap panel resolves a class FQN to its .java
    // source for the side viewer. Empty = endpoint returns 404 with a
    // configuration hint (feature is opt-in; never exposes the FS unless
    // an operator names a root). Typical: ["src/main/java"], or for the
    // playground download additionally "target/generated-sources/fluxtion".
    @Getter @Setter private List<String> sourceRoots = new ArrayList<>();

    /** Filesystem roots searched for {@code <package>/<Class>.graphml}
     *  when the processor's classloader has no graphml resource. The
     *  yaml + spring runtime loaders emit graphml under their
     *  configured {@code generatedResourcesDir} — point this list at
     *  the same dir(s) to make the Processor graph view work for
     *  runtime-loaded processors. Empty = classpath-only (legacy). */
    @Getter @Setter private List<String> graphmlRoots = new ArrayList<>();

    // session config
    @Setter         private String sessionSecret;
    @Getter @Setter private int    sessionMinutes = 60;

    // server-registry config — the agent-brokered dev loop's discovery file (upstream ask
    // UP-MNG-01): ~/.mongoose/servers/<name>, mode 600, written while this admin service is up,
    // removed on clean shutdown. A crashed server leaves a file with a dead pid — readers check
    // pid liveness; nothing cleans stale files up.
    @Getter @Setter private boolean publishRegistry = true;
    /** Registry file name. Unset → the working directory's basename. */
    @Getter @Setter private String  serverName;
    /** Declared deployment environment (UP-MNG-03) — carried into the registry file so an
     *  exporting agent has an authoritative value. Declared, never inferred. */
    @Getter @Setter private String  environment = "dev";
    /** Override for the registry directory (tests / multi-user hosts). Unset → ~/.mongoose/servers. */
    @Getter @Setter private String  registryDir;
    private ServerRegistryFile registryFile;
    private String registryStartedAt;

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

    @ServiceRegistered
    public void serverController(MongooseServerController serverController, String name) {
        log.info("Server controller: '{}' name: '{}'", serverController, name);
        this.serverController = serverController;
    }

    @ServiceRegistered
    public void introspection(MongooseIntrospectionService introspection, String name) {
        log.info("Introspection service: '{}' name: '{}'", introspection, name);
        this.introspection = introspection;
    }

    @ServiceRegistered
    public void countersService(com.telamin.mongoose.service.counters.MongooseCountersService svc, String name) {
        log.info("Counters service: '{}' name: '{}' operational={}", svc, name, svc.isOperational());
        this.countersService = svc;
    }

    @ServiceRegistered
    public void latencyService(com.telamin.mongoose.service.counters.MongooseLatencyService svc, String name) {
        log.info("Latency service: '{}' name: '{}' operational={}", svc, name, svc.isOperational());
        this.latencyService = svc;
    }

    @ServiceRegistered
    public void auditCaptureService(com.telamin.mongoose.service.audit.MongooseAuditCaptureService svc, String name) {
        log.info("Audit capture service: '{}' name: '{}'", svc, name);
        this.auditCapture = svc;
    }

    @ServiceRegistered
    public void auditIntrospectionService(com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService svc, String name) {
        log.info("Audit introspection service: '{}' name: '{}'", svc, name);
        this.auditIntrospection = svc;
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
        // Publish the discovery file BEFORE the port binds so a reader that finds the file can
        // rely on the URL answering (UP-MNG-01 acceptance ordering). listenPort 0 is corrected
        // by the refresh below once the real port is known.
        if (publishRegistry) {
            registryStartedAt = java.time.format.DateTimeFormatter.ISO_INSTANT
                    .format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            registryFile = new ServerRegistryFile(resolvedRegistryDir(), resolvedServerName());
            registryFile.publish(registryRecord());
        }
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
        javalin.get("/api/config", this::handleConfig);
        // Versions of the deployed artefacts — read from each component's
        // class-package Implementation-Version manifest entry. Drives the
        // Settings view's version panel; also useful in support tickets
        // for "what versions are you running" answers.
        javalin.get("/api/version", this::handleVersion);

        // Audit-log capture endpoints (Phase 2 of the audit-log-viewer
        // plugin spec). Read endpoints back the introspection service;
        // control endpoints drive the capture service. Each path
        // returns 503 with a clear message when the corresponding
        // service is the NoOp (capture disabled in YAML).
        javalin.get("/api/audit/files", this::handleAuditList);
        javalin.get("/api/audit/file/{id}/metadata", this::handleAuditMetadata);
        javalin.get("/api/audit/file/{id}", this::handleAuditRead);
        javalin.get("/api/audit/file/{id}/export", this::handleAuditExport);
        javalin.post("/api/audit/{processor}/start", this::handleAuditStart);
        javalin.post("/api/audit/{processor}/stop", this::handleAuditStop);

        // Runtime audit-log-level control. Dispatches an EventLogControlEvent
        // into the named processor — sets the minimum level the
        // EventLogManager auditor emits at. Use to drop into TRACE for a
        // brief debugging window then bump back to INFO without restarting.
        javalin.post("/api/processors/{group}/{name}/audit/level", this::handleSetAuditLogLevel);

        // Dispatcher introspection. Services/agents are structured JSON sourced
        // by invoking + parsing the server.service.list / server.processors.list
        // admin commands. Queues read the injected EventFlowManager directly —
        // no dependency on the `eventSources` command being registered.
        javalin.get("/api/services", this::handleServices);
        javalin.get("/api/services/{name}/config", this::handleServiceConfig);
        javalin.get("/api/agents", this::handleAgents);
        // Pipes — surfaces MongooseServerController.registeredPipes() so
        // the admin UI can render pipes as one logical entity instead of
        // as two separate Feed + Sink rows that happen to share a name
        // pattern.
        javalin.get("/api/pipes", this::handlePipes);
        javalin.get("/api/queues", this::handleQueues);
        javalin.get("/api/processors/{group}/{name}/graphml", this::handleProcessorGraphml);
        javalin.get("/api/processors/{group}/{name}/compliance", this::handleProcessorCompliance);

        // Conditional file picker for loader forms. Always mounted, but returns
        // 404 when loaderBaseDir is unset so the UI hides the tab automatically.
        javalin.get("/api/files", this::handleListFiles);
        // Drag-and-drop upload target for the Loader tab. POSTs the file
        // text + an X-Filename header; writes to loaderBaseDir/uploads/.
        javalin.post("/api/loader/upload", this::handleLoaderUpload);

        // Source-navigation lookup. Resolves a class FQN to .java text by
        // walking the configured sourceRoots in order, first hit wins.
        // Returns 404 with a clear message when sourceRoots is empty or
        // the class is not found — the Processor graph panel degrades to
        // metadata-only without erroring.
        javalin.get("/api/source", this::handleSourceLookup);

        // WebSocket auth filter MUST be registered before any ws() route so
        // it applies to all of them (Javalin only matches before() filters
        // against routes registered AFTER the filter). CSRF on WS is carried
        // as ?csrf=... query param because browsers can't add headers on
        // the upgrade.
        javalin.before("/ws/*", this::enforceWsUpgradeAuth);
        javalin.ws("/ws/monitor", this::configureMonitorWs);
        javalin.ws("/ws/logs",    this::configureLogsWs);
        javalin.ws("/ws/audit-tail/{processor}", this::configureAuditTailWs);

        // Periodic sampler — broadcasts to all live monitor clients. When the
        // counters service is operational the sampler reads forEachCounter
        // each tick and bundles per-feed / per-group / per-processor /
        // per-node rates into the JvmSnapshot's `throughput` block. The
        // pre-tick hook asks EFM to refresh `queue.{path}.depth` gauges so
        // they reflect current state when the sampler reads them.
        com.telamin.mongoose.service.counters.MongooseCountersService countersForSampler =
                (countersService != null)
                        ? countersService
                        : com.telamin.mongoose.internal.NoOpCountersService.INSTANCE;
        Runnable queueDepthHook = () -> {
            if (eventFlowManager != null && countersService != null && countersService.isOperational()) {
                eventFlowManager.sampleQueueDepths(countersService);
            }
        };
        com.telamin.mongoose.service.counters.MongooseLatencyService latencyForSampler =
                (latencyService != null)
                        ? latencyService
                        : com.telamin.mongoose.internal.NoOpLatencyService.INSTANCE;
        monitoringSampler = new MonitoringSampler(metricsIntervalMs, countersForSampler, latencyForSampler, queueDepthHook);
        monitoringSampler.subscribe(this::broadcastMonitorSnapshot);
        monitoringSampler.start();

        // Log tail — captures j.u.l records into a bounded ring buffer and
        // fans new lines out to subscribed /ws/logs clients.
        logTail = new LogTail(logTailBuffer);
        logTail.subscribe(this::broadcastLogLine);
        logTail.start();

        // Correct the registry entry if the kernel picked the port (listenPort 0) and capture
        // processors registered so far. Later registrations (runtime loaders) are folded in by
        // the refresh on the dashboard poll — see handleServer.
        refreshRegistry();
    }

    private void refreshRegistry() {
        if (registryFile != null) {
            registryFile.publish(registryRecord());
        }
    }

    private java.nio.file.Path resolvedRegistryDir() {
        if (registryDir != null && !registryDir.isBlank()) {
            return java.nio.file.Path.of(registryDir);
        }
        // JVM-wide relocation knob — also how the test suite keeps unit tests out of the
        // developer's real ~/.mongoose/servers (surefire sets it to target/servers).
        String sysProp = System.getProperty("mongoose.servers.dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return java.nio.file.Path.of(sysProp);
        }
        return java.nio.file.Path.of(System.getProperty("user.home"), ".mongoose", "servers");
    }

    private String resolvedServerName() {
        if (serverName != null && !serverName.isBlank()) {
            return serverName;
        }
        java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        java.nio.file.Path base = cwd.getFileName();
        return base != null ? base.toString() : "mongoose-server";
    }

    /** The UP-MNG-01 record. {@code token} carries the bearer token only in BEARER mode — the
     *  file is mode 600, the same posture as the analyser's rest-endpoint file. BASIC credentials
     *  are never written. */
    private Map<String, Object> registryRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", resolvedServerName());
        record.put("home", System.getProperty("user.dir"));
        int port = javalin != null ? javalin.port() : listenPort;
        String path = basePath == null || basePath.equals("/") ? "" : basePath.replaceAll("/+$", "");
        record.put("url", "http://" + host + ":" + port + path);
        record.put("token", authMode == AuthMode.BEARER ? resolvedBearerToken() : "");
        record.put("authMode", authMode.name());
        record.put("environment", environment);
        record.put("pid", ProcessHandle.current().pid());
        record.put("startedAt", registryStartedAt);
        List<Map<String, Object>> processors = new ArrayList<>();
        if (serverController != null) {
            try {
                serverController.registeredProcessors().forEach((group, procs) -> {
                    for (NamedEventProcessor np : procs) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("group", group);
                        row.put("name", np.name());
                        if (np.eventProcessor() != null) {
                            row.put("className", np.eventProcessor().getClass().getName());
                        }
                        row.put("graphml", "/api/processors/" + group + "/" + np.name() + "/graphml");
                        processors.add(row);
                    }
                });
            } catch (Exception e) {
                log.warn("registry: could not enumerate processors: {}", e.toString());
            }
        }
        record.put("processors", processors);
        return record;
    }

    @Override
    public void tearDown() {
        if (registryFile != null) {
            registryFile.remove();       // clean shutdown removes the discovery file (UP-MNG-01);
            registryFile = null;         // a crash leaves it behind with a dead pid, by design
        }
        if (monitoringSampler != null) {
            monitoringSampler.stop();
            monitoringSampler = null;
        }
        if (logTail != null) {
            logTail.stop();
            logTail = null;
        }
        monitorClients.clear();
        monitorClientRates.clear();
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
        boolean adminAvailable = adminCommandRegistry != null;
        List<String> commands = adminAvailable
                ? adminCommandRegistry.commandList()
                : Collections.emptyList();
        // adminAvailable distinguishes "registry registered but no commands yet"
        // from "registry missing entirely" — the latter means the Commands tab
        // (and any view dispatching through admin commands) cannot function.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commands", commands);
        body.put("adminAvailable", adminAvailable);
        ctx.json(body);
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
        // Piggy-back a registry refresh on the dashboard poll: processors registered after
        // service start (runtime loaders) fold into the discovery file without a timer.
        // ServerRegistryFile.publish is a no-op when nothing changed.
        refreshRegistry();
        ctx.json(MonitoringSampler.serverInfo());
    }

    private void handleJvm(Context ctx) {
        ctx.json(MonitoringSampler.snapshot());
    }

    // -------- audit-capture (Phase 2) --------
    //
    // The introspection service catalogues files; the capture service
    // mutates state. Mirror that split in the HTTP surface: GETs hit
    // introspection, POSTs hit capture. Both return 503 with a clear
    // message when the corresponding NoOp service is installed (i.e.
    // performanceMonitoring.auditCapture.enabled = false in YAML).

    private void handleAuditList(Context ctx) {
        if (auditIntrospection == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", "audit introspection service not bound"));
            return;
        }
        java.util.List<com.telamin.mongoose.service.audit.AuditSinkHandle> list = auditIntrospection.listAvailable();
        // The handle record uses java.nio.file.Path which Jackson refuses
        // to render structurally. Project to a small JSON-friendly shape.
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>(list.size());
        for (com.telamin.mongoose.service.audit.AuditSinkHandle h : list) {
            out.add(handleToJson(h));
        }
        ctx.json(out);
    }

    private void handleAuditMetadata(Context ctx) {
        if (auditIntrospection == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", "audit introspection service not bound"));
            return;
        }
        String id = ctx.pathParam("id");
        // For now the {id} from the spec is the processorName — Phase 1's
        // Chronicle impl uses the processor name as the stable file id
        // (one queue dir per processor). A future cycle-aware id can be
        // a richer key without breaking this handler.
        com.telamin.mongoose.service.audit.AuditSinkHandle h = auditIntrospection.currentSink(id);
        if (h == null) {
            // Fall back to a directory-walk match — historical (closed) sinks.
            for (com.telamin.mongoose.service.audit.AuditSinkHandle candidate : auditIntrospection.listAvailable()) {
                if (id.equals(candidate.id())) {
                    h = candidate;
                    break;
                }
            }
        }
        if (h == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "no audit file with id=" + id));
            return;
        }
        ctx.json(handleToJson(h));
    }

    private void handleAuditStart(Context ctx) {
        if (auditCapture == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", "audit capture service not bound"));
            return;
        }
        String processor = ctx.pathParam("processor");
        try {
            auditCapture.start(processor);
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", e.getMessage()));
            return;
        }
        ctx.json(Map.of(
                "processor", processor,
                "recording", auditCapture.isRecording(processor)));
    }

    private void handleAuditStop(Context ctx) {
        if (auditCapture == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", "audit capture service not bound"));
            return;
        }
        String processor = ctx.pathParam("processor");
        auditCapture.stop(processor);
        ctx.json(Map.of(
                "processor", processor,
                "recording", auditCapture.isRecording(processor)));
    }

    /** Runtime audit-log-level control for a named processor. Dispatches
     *  an EventLogControlEvent via DataFlow.setAuditLogLevel — that
     *  sets the MINIMUM level the EventLogManager will trace at, which
     *  governs whether records actually emit (the runtime log level
     *  defaults to INFO; setting the gate to TRACE/DEBUG silently
     *  suppresses emission). Accepts body {level: "TRACE"|"DEBUG"|"INFO"|
     *  "WARN"|"ERROR"|"NONE"}. */
    private void handleSetAuditLogLevel(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        String group = ctx.pathParam("group");
        String name  = ctx.pathParam("name");

        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "body must be JSON object {level: \"...\"}"));
            return;
        }
        Object levelRaw = body != null ? body.get("level") : null;
        if (!(levelRaw instanceof String)) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "level required, e.g. {\"level\": \"INFO\"}"));
            return;
        }
        com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel level;
        try {
            level = com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel
                    .valueOf(((String) levelRaw).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of(
                    "err", "unknown level: " + levelRaw,
                    "allowed", java.util.Arrays.stream(
                            com.telamin.fluxtion.runtime.audit.EventLogControlEvent.LogLevel.values())
                            .map(Enum::name)
                            .toList()));
            return;
        }

        java.util.Collection<NamedEventProcessor> procs =
                serverController.registeredProcessors().get(group);
        if (procs == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "unknown agent group: " + group));
            return;
        }
        NamedEventProcessor match = null;
        for (NamedEventProcessor np : procs) {
            if (name.equals(np.name())) { match = np; break; }
        }
        if (match == null || match.eventProcessor() == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "processor '" + name + "' not registered in group '" + group + "'"));
            return;
        }
        try {
            match.eventProcessor().setAuditLogLevel(level);
        } catch (Throwable t) {
            log.warn("setAuditLogLevel failed for {}/{}: {}", group, name, t.toString());
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "dispatch failed: " + t.getMessage()));
            return;
        }
        ctx.json(Map.of(
                "processor", name,
                "group", group,
                "level", level.name()));
    }

    private void handleAuditRead(Context ctx) {
        if (auditIntrospection == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", "audit introspection service not bound"));
            return;
        }
        String id = ctx.pathParam("id");
        com.telamin.mongoose.service.audit.AuditSinkHandle handle = findHandle(id);
        if (handle == null || handle.path() == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "no audit file with id=" + id));
            return;
        }
        long from = parseLong(ctx.queryParam("from"), 0);
        long limit = parseLong(ctx.queryParam("limit"), 1000);
        ctx.contentType("application/x-ndjson");
        try (net.openhft.chronicle.queue.ChronicleQueue q =
                     net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder
                             .binary(handle.path())
                             .readOnly(true)
                             .build();
             java.io.PrintWriter w = new java.io.PrintWriter(ctx.outputStream())) {
            net.openhft.chronicle.queue.ExcerptTailer tailer = q.createTailer();
            long index = 0;
            long emitted = 0;
            while (emitted < limit) {
                try (net.openhft.chronicle.wire.DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) break;
                    if (index++ < from) continue;
                    String yaml = dc.wire().getValueIn().text();
                    if (yaml == null) continue;
                    String json = AuditRecordProjection.yamlToJson(yaml);
                    w.write(json);
                    w.write('\n');
                    emitted++;
                }
            }
            w.flush();
        } catch (Throwable e) {
            log.warn("audit read failed for {}", id, e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.contentType("application/json");
            ctx.result("{\"err\":\"audit read failed: " + e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
        }
    }

    private void handleAuditExport(Context ctx) {
        if (auditIntrospection == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            ctx.json(Map.of("err", "audit introspection service not bound"));
            return;
        }
        String id = ctx.pathParam("id");
        String fmt = ctx.queryParamAsClass("format", String.class).getOrDefault("yaml");
        com.telamin.mongoose.service.audit.AuditSinkHandle handle = findHandle(id);
        if (handle == null || handle.path() == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "no audit file with id=" + id));
            return;
        }
        boolean json = "jsonl".equalsIgnoreCase(fmt);
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String filename = id + "-audit-" + stamp + (json ? ".jsonl" : ".yaml");
        ctx.contentType(json ? "application/x-ndjson" : "text/yaml");
        ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        try (net.openhft.chronicle.queue.ChronicleQueue q =
                     net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder
                             .binary(handle.path())
                             .readOnly(true)
                             .build();
             java.io.PrintWriter w = new java.io.PrintWriter(ctx.outputStream())) {
            net.openhft.chronicle.queue.ExcerptTailer tailer = q.createTailer();
            boolean first = true;
            while (true) {
                try (net.openhft.chronicle.wire.DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) break;
                    String yaml = dc.wire().getValueIn().text();
                    if (yaml == null) continue;
                    if (json) {
                        // One JSON object per line — drop-in for jq / Loki / Splunk.
                        w.write(AuditRecordProjection.yamlToJson(yaml));
                        w.write('\n');
                    } else {
                        // YAML `---` separated documents — drop-in for the
                        // desktop fluxtion-visualiser's eventlog-parser.
                        if (!first) w.write("\n---\n");
                        w.write(yaml);
                        first = false;
                    }
                }
            }
            w.flush();
        } catch (Throwable e) {
            log.warn("audit export failed for {}", id, e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.contentType("application/json");
            ctx.result("{\"err\":\"audit export failed: " + e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
        }
    }

    /**
     * Live-tail WS — adaptive flush per the Phase 2 spec.
     *
     * <p>Each subscribed client gets:
     * <ul>
     *   <li>A dedicated Chronicle tailer positioned at the tip on connect</li>
     *   <li>A ~25 ms scheduled poll that batches new records</li>
     *   <li>Frame fan-out when buffer ≥ BATCH_THRESHOLD (32) OR
     *       now − lastFlush > MAX_LATENCY_MS (50) — whichever first</li>
     * </ul>
     * Tab-hidden suspension via the {@code {"op":"pause"}} /
     * {@code {"op":"resume"}} opcodes.
     */
    private void configureAuditTailWs(io.javalin.websocket.WsConfig ws) {
        final long MAX_LATENCY_MS = 50;
        final int BATCH_THRESHOLD = 32;
        final long POLL_INTERVAL_MS = 25;
        ws.onConnect(ctx -> {
            try { ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(10)); }
            catch (Throwable t) { log.warn("could not set ws idle timeout", t); }
            String processor = ctx.pathParam("processor");
            if (auditIntrospection == null) {
                ctx.send(java.util.Map.of("err", "audit introspection service not bound"));
                ctx.session.close();
                return;
            }
            com.telamin.mongoose.service.audit.AuditSinkHandle h = auditIntrospection.currentSink(processor);
            if (h == null || h.path() == null) {
                ctx.send(java.util.Map.of("err", "no live capture for processor " + processor));
                ctx.session.close();
                return;
            }
            net.openhft.chronicle.queue.ChronicleQueue q =
                    net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder
                            .binary(h.path()).build();
            net.openhft.chronicle.queue.ExcerptTailer tailer = q.createTailer().toEnd();
            java.util.concurrent.ScheduledExecutorService exec =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "audit-tail-" + processor);
                        t.setDaemon(true);
                        return t;
                    });
            AuditTailState state = new AuditTailState(q, tailer, exec);
            ctx.attribute("audit-tail-state", state);

            exec.scheduleAtFixedRate(() -> {
                if (!ctx.session.isOpen() || state.paused) return;
                try {
                    java.util.List<Object> batch = new java.util.ArrayList<>();
                    while (true) {
                        try (net.openhft.chronicle.wire.DocumentContext dc = tailer.readingDocument()) {
                            if (!dc.isPresent()) break;
                            String yaml = dc.wire().getValueIn().text();
                            if (yaml == null) continue;
                            String json = AuditRecordProjection.yamlToJson(yaml);
                            batch.add(JSON_FRAGMENT_MARK + json);  // sentinel to splice as raw JSON
                            if (batch.size() >= BATCH_THRESHOLD) break;
                        }
                    }
                    long now = System.currentTimeMillis();
                    boolean shouldFlush = !batch.isEmpty()
                            && (batch.size() >= BATCH_THRESHOLD
                            || now - state.lastFlush > MAX_LATENCY_MS);
                    if (shouldFlush) {
                        StringBuilder sb = new StringBuilder("[");
                        for (int i = 0; i < batch.size(); i++) {
                            String raw = (String) batch.get(i);
                            if (i > 0) sb.append(',');
                            sb.append(raw.substring(JSON_FRAGMENT_MARK.length()));
                        }
                        sb.append(']');
                        ctx.send(sb.toString());
                        state.lastFlush = now;
                    }
                } catch (Exception e) {
                    log.debug("audit tail tick failed for {}", processor, e);
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
        ws.onMessage(ctx -> {
            AuditTailState state = ctx.attribute("audit-tail-state");
            if (state == null) return;
            String body = ctx.message();
            if (body == null) return;
            if (body.contains("\"pause\"")) state.paused = true;
            else if (body.contains("\"resume\"")) state.paused = false;
        });
        ws.onClose(ctx -> {
            AuditTailState state = ctx.attribute("audit-tail-state");
            if (state != null) state.close();
        });
        ws.onError(ctx -> {
            AuditTailState state = ctx.attribute("audit-tail-state");
            if (state != null) state.close();
        });
    }

    private static final String JSON_FRAGMENT_MARK = " ";

    /** Per-WS state for the audit-tail subscription. */
    private static final class AuditTailState {
        final net.openhft.chronicle.queue.ChronicleQueue queue;
        final net.openhft.chronicle.queue.ExcerptTailer tailer;
        final java.util.concurrent.ScheduledExecutorService exec;
        volatile boolean paused = false;
        volatile long lastFlush = System.currentTimeMillis();

        AuditTailState(net.openhft.chronicle.queue.ChronicleQueue q,
                       net.openhft.chronicle.queue.ExcerptTailer t,
                       java.util.concurrent.ScheduledExecutorService e) {
            this.queue = q;
            this.tailer = t;
            this.exec = e;
        }

        void close() {
            exec.shutdownNow();
            try {
                queue.close();
            } catch (Exception ignored) {
            }
        }
    }

    private com.telamin.mongoose.service.audit.AuditSinkHandle findHandle(String id) {
        if (auditIntrospection == null) return null;
        com.telamin.mongoose.service.audit.AuditSinkHandle live = auditIntrospection.currentSink(id);
        if (live != null) return live;
        for (com.telamin.mongoose.service.audit.AuditSinkHandle h : auditIntrospection.listAvailable()) {
            if (id.equals(h.id())) return h;
        }
        return null;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Project an AuditSinkHandle to a JSON-friendly map (Path → String). */
    private static Map<String, Object> handleToJson(com.telamin.mongoose.service.audit.AuditSinkHandle h) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", h.id());
        m.put("processorName", h.processorName());
        m.put("path", h.path() == null ? null : h.path().toString());
        m.put("cycle", h.cycle());
        m.put("sizeBytes", h.sizeBytes());
        m.put("recordCount", h.recordCount());
        m.put("startedAt", h.startedAt() == null ? null : h.startedAt().toString());
        m.put("lastWriteAt", h.lastWriteAt() == null ? null : h.lastWriteAt().toString());
        m.put("isLive", h.isLive());
        return m;
    }

    /** Reports deployed-artefact versions for the Settings view's
     *  version panel. Reads {@code META-INF/maven/<groupId>/<artifactId>/pom.properties}
     *  files — Maven embeds these in every dep jar, and unlike
     *  per-package {@code Implementation-Version} manifest entries they
     *  SURVIVE shading (maven-shade-plugin treats them as resources,
     *  not manifest data). Falls back to "unknown" when the artefact
     *  isn't on the classpath. */
    private void handleVersion(Context ctx) {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("mongoose", resolveDependencyVersion("com.telamin", "mongoose"));
        versions.put("svc-admin-web", resolveDependencyVersion("com.telamin", "svc-admin-web"));
        versions.put("svc-micrometer", resolveDependencyVersion("com.telamin", "svc-micrometer"));
        versions.put("fluxtion-runtime", resolveDependencyVersion("com.telamin.fluxtion", "fluxtion-runtime"));
        versions.put("fluxtion-runtime-java8", resolveDependencyVersion("com.telamin.fluxtion", "fluxtion-runtime-java8"));
        versions.put("fluxtion-builder", resolveDependencyVersion("com.telamin.fluxtion", "fluxtion-builder"));
        versions.put("app", resolveAppVersion());
        ctx.json(Map.of("versions", versions));
    }

    /** Resolve a single (groupId, artifactId) version. Reads
     *  pom.properties from the classloader; survives shading because
     *  it's a resource path, not a manifest entry. */
    private static String resolveDependencyVersion(String groupId, String artifactId) {
        String resource = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (java.io.InputStream in = WebAdminService.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) return "absent";
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty("version");
            return (v == null || v.isEmpty()) ? "unknown" : v;
        } catch (java.io.IOException e) {
            return "unknown";
        }
    }

    /** Best-effort app-version lookup. Opens the launched jar (from
     *  {@code sun.java.command}), reads its Main-Class, derives the
     *  expected groupId prefix from the first dot-segment of the
     *  main-class package, and finds a pom.properties under
     *  {@code META-INF/maven/<prefix>.*\/.../pom.properties}.
     *
     *  <p>Returns "<artifactId> <version>" so the UI can distinguish a
     *  versionless 1.0.0-SNAPSHOT from "the app".
     *
     *  <p>Returns "unknown" when the JVM was launched in a way that
     *  doesn't surface a single jar (IDE classpath, modular launch,
     *  jar without a Main-Class entry, etc). */
    private static String resolveAppVersion() {
        try {
            String cmd = System.getProperty("sun.java.command", "");
            if (cmd.isEmpty()) return "unknown";
            String jarPath = cmd.split("\\s")[0];
            if (!jarPath.endsWith(".jar")) return "unknown";
            java.io.File jarFile = new java.io.File(jarPath);
            if (!jarFile.isFile()) return "unknown";
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                java.util.jar.Manifest mf = jar.getManifest();
                if (mf == null) return "unknown";
                String mainClass = mf.getMainAttributes().getValue("Main-Class");
                if (mainClass == null || !mainClass.contains(".")) {
                    // No package on the main class — fall back to
                    // Implementation-Version on the manifest if present.
                    String v = mf.getMainAttributes().getValue("Implementation-Version");
                    return (v != null && !v.isEmpty()) ? v : "unknown";
                }
                String firstSegment = mainClass.split("\\.")[0];
                String matchPrefix = "META-INF/maven/" + firstSegment + ".";

                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith(matchPrefix) && name.endsWith("/pom.properties")) {
                        try (java.io.InputStream in = jar.getInputStream(entry)) {
                            java.util.Properties p = new java.util.Properties();
                            p.load(in);
                            String v = p.getProperty("version", "");
                            String aid = p.getProperty("artifactId", "");
                            if (!v.isEmpty()) {
                                return aid.isEmpty() ? v : (aid + " " + v);
                            }
                        }
                    }
                }
                // No matching pom.properties — try the manifest's
                // Implementation-Version as a last resort.
                String v = mf.getMainAttributes().getValue("Implementation-Version");
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignore) { /* fall through */ }
        return "unknown";
    }

    // Returns the YAML config file the server was booted with as plain text,
    // wrapped in a small JSON envelope { path, content } so the UI can show
    // both. Read once per request — cheap, and operators expect a live read.
    private void handleConfig(Context ctx) {
        String path = System.getProperty("mongooseServer.config.file");
        if (path == null || path.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "mongooseServer.config.file system property not set"));
            return;
        }
        try {
            String content = java.nio.file.Files.readString(java.nio.file.Paths.get(path));
            ctx.json(Map.of("path", path, "content", content));
        } catch (java.io.IOException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("path", path, "err", "read failed: " + e.getMessage()));
        }
    }

    // -------- dispatcher introspection --------
    //
    // /api/services and /api/agents read directly from the injected
    // MongooseServerController — the same registry MongooseServerAdmin walks
    // for its server.* commands, but via the structured object graph rather
    // than parsing toString() text. That also lets us distinguish event feeds
    // (EventSource) and sinks (MessageSink) from plain services, which the
    // flat command output cannot.

    private void handleServices(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        // When the introspection service is wired we use its typed feedTopology
        // (precise per-processor attribution). Otherwise we fall back to parsing
        // EventFlowManager text + group-fanout — backward-compatible behaviour.
        Map<String, List<Map<String, Object>>> consumersByFeed;
        if (introspection != null) {
            consumersByFeed = consumersFromIntrospection(introspection);
        } else {
            List<Map<String, Object>> sources = currentTopologySources();
            Map<String, List<Map<String, Object>>> raw = consumersByFeed(sources);
            java.util.function.Function<String, java.util.Collection<NamedEventProcessor>> procLookup =
                    group -> {
                        java.util.Collection<NamedEventProcessor> v =
                                serverController.registeredProcessors().get(group);
                        return v != null ? v : Collections.emptyList();
                    };
            consumersByFeed = new LinkedHashMap<>();
            raw.forEach((feed, list) -> consumersByFeed.put(feed, expandConsumers(list, procLookup)));
        }

        // Sink → consumers map. Computed by walking every processor's
        // ServiceRegistryQuery: feeds give us the input side, this gives the
        // output side. Together they're the data the Topology view needs to
        // render full feed → processor → sink flow.
        Map<String, List<Map<String, Object>>> consumersBySink = consumersBySink();

        List<Map<String, Object>> services = new ArrayList<>();
        serverController.registeredServices().forEach((name, svc) -> {
            Object instance = svc.instance();
            Class<?> svcClass = svc.serviceClass();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            String type = classifyService(instance);
            entry.put("type", type);
            // Prefer the concrete instance class — that's the actual
            // implementation operators want to see in the "Implementation"
            // column. svcClass is typically the registered interface
            // (MessageSink, EventSource, NamedFeed, ...) which is uninformative
            // for distinguishing "which sink is this?".
            entry.put("className", instance != null
                    ? instance.getClass().getName()
                    : (svcClass != null ? svcClass.getName() : ""));
            if ("feed".equals(type)) {
                entry.put("consumers", consumersByFeed.getOrDefault(name, Collections.emptyList()));
            } else if ("sink".equals(type)) {
                entry.put("consumers", consumersBySink.getOrDefault(name, Collections.emptyList()));
            }
            services.add(entry);
        });
        ctx.json(Map.of("services", services));
    }

    /**
     * Walk every registered processor's {@link com.telamin.fluxtion.runtime.service.ServiceRegistryQuery}
     * and produce a sink-name → consumer-processors map. Each consumer
     * entry: {@code { processor, group, nodes: [...] }}.
     *
     * <p>Falls through silently for processors whose runtime predates
     * fluxtion 1.0.1 (no ServiceRegistryQuery) — those just don't
     * contribute to the sink-attribution view; the rest still do.
     *
     * <p>Time complexity: O(processors × sinks) — both are small in
     * practice (single-digit). Recomputed per request since the values
     * are cheap and the underlying topology can shift at runtime
     * (dynamic node injection, late service registration).
     */
    private Map<String, List<Map<String, Object>>> consumersBySink() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (serverController == null) return out;

        // Snapshot sinks up-front so we don't iterate registeredServices()
        // N times inside the processor loop.
        Map<String, Object> sinkInstances = new LinkedHashMap<>();
        serverController.registeredServices().forEach((name, svc) -> {
            Object instance = svc.instance();
            if (instance != null && "sink".equals(classifyService(instance))) {
                sinkInstances.put(name, instance);
            }
        });
        if (sinkInstances.isEmpty()) return out;

        serverController.registeredProcessors().forEach((group, procs) -> {
            for (NamedEventProcessor np : procs) {
                com.telamin.fluxtion.runtime.DataFlow processor = np.eventProcessor();
                if (processor == null) continue;

                // DSL-published sinks (SinkPublisher fields) — present on
                // every processor regardless of fluxtion-runtime version.
                Set<String> dslSinks = publisherSinkNames(processor);

                java.util.Optional<com.telamin.fluxtion.runtime.service.ServiceRegistryQuery> queryOpt;
                try {
                    queryOpt = processor.serviceRegistryQuery();
                } catch (NoSuchMethodError | AbstractMethodError olderRuntime) {
                    queryOpt = java.util.Optional.empty();
                }
                com.telamin.fluxtion.runtime.service.ServiceRegistryQuery q = queryOpt.orElse(null);

                for (Map.Entry<String, Object> sinkEntry : sinkInstances.entrySet()) {
                    String sinkName = sinkEntry.getKey();
                    Object sinkInstance = sinkEntry.getValue();

                    List<String> nodes = java.util.Collections.emptyList();
                    if (q != null) {
                        try {
                            nodes = consumersForBinding(q, sinkInstance.getClass(), sinkName, sinkInstance);
                        } catch (Throwable t) {
                            log.warn("topology: sink consumer lookup failed for '{}' on processor '{}'",
                                    sinkName, np.name(), t);
                        }
                    }
                    if (nodes.isEmpty() && dslSinks.contains(sinkName)) {
                        // DSL terminator attribution — the SinkPublisher's name
                        // doubles as the consumer label on the topology edge.
                        nodes = java.util.Collections.singletonList(sinkName);
                    }
                    if (nodes.isEmpty()) continue;

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("processor", np.name());
                    entry.put("group", group);
                    entry.put("nodes", nodes);
                    out.computeIfAbsent(sinkName, k -> new ArrayList<>()).add(entry);
                }
            }
        });
        return out;
    }

    /**
     * Convert the introspection service's typed feed topology into the same
     * JSON shape that the legacy text-parse path produces. Keeps the
     * frontend's view of the data stable across introspection-available and
     * fallback modes.
     */
    static Map<String, List<Map<String, Object>>> consumersFromIntrospection(
            MongooseIntrospectionService introspection) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        try {
            Map<String, com.telamin.mongoose.service.introspection.FeedTopology> ft =
                    introspection.feedTopology();
            ft.forEach((feed, topo) -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (com.telamin.mongoose.service.introspection.FeedConsumer c : topo.consumers()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("agentGroup", c.agentGroup());
                    e.put("callback",   c.callback());
                    e.put("path",       c.queuePath());
                    e.put("processors", new ArrayList<>(c.processors()));
                    list.add(e);
                }
                out.put(feed, list);
            });
        } catch (Exception e) {
            log.warn("introspection.feedTopology() failed; falling back to empty topology", e);
        }
        return out;
    }

    /** Returns the server's configured pipes as JSON:
     *  {@code { pipes: [{name, sinkName, agentName, broadcast, cacheEventLog}] }}.
     *  Empty array when no pipes are configured (the common case today).
     *  Each pipe corresponds to exactly one {@code HandlerPipeConfig}
     *  entry on the server config and exactly two service registrations
     *  in {@code registeredServices()} — the feed-side under {@code name}
     *  and the sink-side under {@code sinkName}. */
    private void handlePipes(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        var pipes = serverController.registeredPipes();
        List<Map<String, Object>> out = new java.util.ArrayList<>(pipes.size());
        for (var p : pipes) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", p.name());
            row.put("sinkName", p.sinkName());
            row.put("agentName", p.agentName());
            row.put("broadcast", p.broadcast());
            row.put("cacheEventLog", p.cacheEventLog());
            out.add(row);
        }
        ctx.json(Map.of("pipes", out));
    }

    private void handleAgents(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        List<Map<String, Object>> sources = currentTopologySources();
        Map<String, List<Map<String, Object>>> feedsByAgent = feedsByAgentGroup(sources);

        List<Map<String, Object>> agents = new ArrayList<>();
        serverController.registeredProcessors().forEach((groupName, procs) -> {
            List<Map<String, Object>> members = new ArrayList<>();
            for (NamedEventProcessor np : procs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", np.name());
                m.put("kind", "processor");
                String cls = np.eventProcessor() != null
                        ? np.eventProcessor().getClass().getName() : "";
                m.put("className", cls);
                members.add(m);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("group", groupName);
            group.put("type", "processor");
            group.put("members", members);
            group.put("feeds", feedsByAgent.getOrDefault(groupName, Collections.emptyList()));
            agents.add(group);
        });

        // Enrich with thread/idle config + per-processor subscriptions when
        // the introspection service is wired. Defaults to a no-op when absent
        // so this endpoint still functions against older mongoose builds.
        if (introspection != null) {
            try {
                Map<String, com.telamin.mongoose.service.introspection.AgentGroupSnapshot> snaps =
                        introspection.agentGroups();
                for (Map<String, Object> g : agents) {
                    String name = String.valueOf(g.get("group"));
                    var snap = snaps.get(name);
                    if (snap != null) applyAgentSnapshot(g, snap);
                }
            } catch (Exception e) {
                log.warn("introspection.agentGroups() failed; reporting raw registry view", e);
            }
        }

        ctx.json(Map.of("agents", agents));
    }

    /**
     * Merge an {@link com.telamin.mongoose.service.introspection.AgentGroupSnapshot}
     * into the agent map already built from the controller's registry view. The
     * controller-derived shape stays the same; introspection adds the per-thread
     * detail and rewrites {@code members} with subscription-aware entries.
     */
    static void applyAgentSnapshot(Map<String, Object> group,
                                   com.telamin.mongoose.service.introspection.AgentGroupSnapshot snap) {
        group.put("kind",              snap.kind());
        group.put("idleStrategyClass", snap.idleStrategyClass());
        group.put("thread",            snap.threadName());
        group.put("threadState",       snap.threadState());
        group.put("daemon",            snap.daemon());
        group.put("priority",          snap.priority());

        List<Map<String, Object>> members = new ArrayList<>();
        for (com.telamin.mongoose.service.introspection.ProcessorInfo p : snap.processors()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("kind", "processor");
            m.put("className", p.className() != null ? p.className() : "");
            List<Map<String, Object>> subs = new ArrayList<>();
            for (com.telamin.mongoose.service.introspection.SubscriptionInfo s : p.subscriptions()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("feed", s.feed());
                e.put("callback", s.callback());
                subs.add(e);
            }
            m.put("subscriptions", subs);
            members.add(m);
        }
        group.put("members", members);
    }

    /**
     * Parse the EventFlowManager topology into the same source-list shape that
     * {@code /api/queues} returns. Returns an empty list when no flow manager is
     * wired or its dump fails — callers should treat the absence of topology as
     * "no consumer info available" rather than an error, so cross-linking
     * degrades gracefully.
     */
    List<Map<String, Object>> currentTopologySources() {
        if (eventFlowManager == null) return Collections.emptyList();
        StringBuilder sb = new StringBuilder();
        try {
            eventFlowManager.appendQueueInformation(sb);
        } catch (Exception e) {
            log.warn("topology dump failed (skipping cross-link enrichment)", e);
            return Collections.emptyList();
        }
        return parseEventSources(sb.toString());
    }

    /**
     * Invert the source-keyed topology to a feed-name → list-of-consumer-entries
     * map. Each consumer entry retains the agent group and callback strategy
     * from the queue path.
     */
    static Map<String, List<Map<String, Object>>> consumersByFeed(List<Map<String, Object>> sources) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> source : sources) {
            String name = String.valueOf(source.get("source"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queues = (List<Map<String, Object>>) source.get("queues");
            if (queues == null) continue;
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> q : queues) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("agentGroup", q.get("agentGroup"));
                e.put("callback", q.get("callback"));
                e.put("path", q.get("path"));
                list.add(e);
            }
            out.put(name, list);
        }
        return out;
    }

    /**
     * Group the source-keyed topology by agent group, producing per-group
     * {@code feeds: [{feed, callback, path}, ...]} entries.
     */
    static Map<String, List<Map<String, Object>>> feedsByAgentGroup(List<Map<String, Object>> sources) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> source : sources) {
            String feed = String.valueOf(source.get("source"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queues = (List<Map<String, Object>>) source.get("queues");
            if (queues == null) continue;
            for (Map<String, Object> q : queues) {
                String group = String.valueOf(q.get("agentGroup"));
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("feed", feed);
                e.put("callback", q.get("callback"));
                e.put("path", q.get("path"));
                out.computeIfAbsent(group, g -> new ArrayList<>()).add(e);
            }
        }
        return out;
    }

    /**
     * Expand a consumers list to include the processors in each agent group.
     * Per-processor subscription granularity isn't observable through the
     * current dispatcher introspection, so every processor in the group is
     * reported as a consumer of any feed the group reads — the UI surfaces
     * this as "fanned out via group X" so users don't read it as exact.
     */
    static List<Map<String, Object>> expandConsumers(List<Map<String, Object>> consumers,
                                                     java.util.function.Function<String, java.util.Collection<NamedEventProcessor>> procLookup) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : consumers) {
            String group = String.valueOf(c.get("agentGroup"));
            java.util.Collection<NamedEventProcessor> procs = procLookup == null
                    ? Collections.emptyList()
                    : procLookup.apply(group);
            if (procs == null) procs = Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (NamedEventProcessor np : procs) names.add(np.name());
            Map<String, Object> e = new LinkedHashMap<>(c);
            e.put("processors", names);
            out.add(e);
        }
        return out;
    }

    /**
     * Categorise a service instance for the UI.
     * <p>
     * {@code registerEventFeed} and {@code registerEventSink} both delegate to
     * {@code registerService} in {@code MongooseServer}, so feeds, sinks and
     * services all live in the same {@code registeredServices()} map. We
     * recover the distinction by inspecting the runtime type.
     */
    static String classifyService(Object instance) {
        if (instance == null) return "service";
        if (instance instanceof EventSource<?>) return "feed";
        if (instance instanceof MessageSink<?>) return "sink";
        return "service";
    }

    /**
     * Per-processor graphml view — serves the Fluxtion-generated graphml so
     * the admin UI can render the processor's internal DAG inline. The
     * convention is the same one the {@code fluxtion-builder} writes:
     * the graphml lives next to the generated class on the classpath, named
     * {@code <class-fqn-with-slashes>.graphml}.
     * <p>
     * On 404 the response body includes the expected resource path and the
     * processor's class name so the UI can show a "to enable, copy the
     * generated graphml into {@code src/main/resources/...}" message rather
     * than a silent miss.
     */
    private void handleProcessorGraphml(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        String group = ctx.pathParam("group");
        String name  = ctx.pathParam("name");

        java.util.Collection<NamedEventProcessor> procs =
                serverController.registeredProcessors().get(group);
        if (procs == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "unknown agent group: " + group));
            return;
        }
        NamedEventProcessor match = null;
        for (NamedEventProcessor np : procs) {
            if (name.equals(np.name())) { match = np; break; }
        }
        if (match == null || match.eventProcessor() == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "processor '" + name + "' not registered in group '" + group + "'"));
            return;
        }

        Class<?> cls = match.eventProcessor().getClass();
        String resourcePath = cls.getName().replace('.', '/') + ".graphml";
        ClassLoader loader = cls.getClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();

        byte[] bytes = null;
        try (java.io.InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in != null) bytes = in.readAllBytes();
        } catch (java.io.IOException e) {
            log.warn("graphml read failed for {}", cls.getName(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "graphml read failed: " + e.getMessage()));
            return;
        }

        if (bytes == null) {
            // Runtime-loaded processors (yaml / spring loaders) live in a
            // classloader that doesn't see the file the loader just wrote.
            // Fall back to the configured graphmlRoots — operator points
            // these at each loader's generatedResourcesDir.
            bytes = readFromRoots(graphmlRoots, resourcePath);
        }

        if (bytes == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of(
                    "err", "graphml resource not found on the processor's classloader",
                    "className", cls.getName(),
                    "expectedResource", resourcePath,
                    "searchedRoots", graphmlRoots == null ? List.of() : graphmlRoots,
                    "hint", "Fluxtion writes <ClassName>.graphml alongside the generated source. "
                          + "Either bundle it into the runtime jar at src/main/resources/" + resourcePath
                          + ", or set graphmlRoots on the WebAdminService bean to the loader's "
                          + "generatedResourcesDir so the file is found at runtime."));
            return;
        }

        // Surface the live processor's FQN to the client so the
        // Processor graph UI can offer "view processor source" — the
        // generated dispatcher class doesn't appear as a node inside
        // its own graph, so the panel needs an out-of-band way to
        // pick it up. Header is non-invasive: existing clients ignore
        // it, source-nav clients read it.
        ctx.header("X-Processor-Class", cls.getName());
        ctx.contentType("application/xml; charset=utf-8");
        ctx.result(bytes);
    }

    /** Walks {@code roots} (in order) for {@code relPath}, returning
     *  the first file's bytes. Symlink-escape-guarded. Returns null on
     *  miss. Package-private for unit tests. */
    static byte[] readFromRoots(List<String> roots, String relPath) {
        if (roots == null || roots.isEmpty()) return null;
        for (String rootSpec : roots) {
            java.nio.file.Path root;
            try {
                root = java.nio.file.Paths.get(rootSpec).toRealPath();
            } catch (java.io.IOException missing) {
                continue;
            }
            java.nio.file.Path candidate;
            try {
                candidate = root.resolve(relPath).toRealPath();
            } catch (java.io.IOException missing) {
                continue;
            }
            if (!candidate.startsWith(root)) continue;
            if (!java.nio.file.Files.isRegularFile(candidate)) continue;
            try {
                return java.nio.file.Files.readAllBytes(candidate);
            } catch (java.io.IOException io) {
                return null;
            }
        }
        return null;
    }

    /**
     * Per-processor compliance report — answers the regulator-friendly
     * question "which external systems does this processor read from and
     * write to?" with the actual configured implementation behind each
     * binding. Combines three sources:
     *
     * <ul>
     *   <li>The processor's {@link com.telamin.fluxtion.runtime.service.ServiceRegistryQuery}
     *       — declared {@code @ServiceRegistered} dependencies, per node.</li>
     *   <li>{@code registeredServices()} — the actual feed/sink/service
     *       instances the runtime resolved against.</li>
     *   <li>{@link #summarizeConfig(Object)} — reflective bean-property
     *       extraction of the physical configuration (file paths, kafka
     *       topics, network addresses, …) with sensitive-named values
     *       redacted.</li>
     * </ul>
     *
     * <p>The combined result is the audit-trail evidence: a complete,
     * compiler-guaranteed list of every external system the processor
     * touches, with their bindings and configuration. Suitable for
     * compliance review, refactor-safety pre-flight checks, and any
     * "show me what this thing does" question that today requires
     * reading source.
     */
    private void handleProcessorCompliance(Context ctx) {
        try {
            handleProcessorComplianceImpl(ctx);
        } catch (Throwable t) {
            // Surface the real cause — Javalin's default 500 has no body and
            // the compliance endpoint touches a wide reflection + service-
            // registry surface that's easy to break on version drift. Be
            // generous with the diagnostic.
            log.warn("compliance handler failed for {}/{}",
                    ctx.pathParam("group"), ctx.pathParam("name"), t);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.contentType("application/json");
            String msg = String.valueOf(t.getMessage()).replace("\"", "'");
            ctx.result("{\"err\":\"compliance failed: " + t.getClass().getSimpleName()
                    + ": " + msg + "\"}");
        }
    }

    private void handleProcessorComplianceImpl(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        String group = ctx.pathParam("group");
        String name = ctx.pathParam("name");

        java.util.Collection<NamedEventProcessor> procs =
                serverController.registeredProcessors().get(group);
        if (procs == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "unknown agent group: " + group));
            return;
        }
        NamedEventProcessor match = null;
        for (NamedEventProcessor np : procs) {
            if (name.equals(np.name())) { match = np; break; }
        }
        if (match == null || match.eventProcessor() == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "processor '" + name + "' not registered in group '" + group + "'"));
            return;
        }

        com.telamin.fluxtion.runtime.DataFlow processor = match.eventProcessor();

        // serviceRegistryQuery() is a default method on DataFlow that landed
        // in fluxtion-runtime 1.0.1. Wrap the call so older SEPs / older
        // fluxtion-runtime jars don't take the endpoint down — they just
        // get the degraded "no binding attribution" view.
        java.util.Optional<com.telamin.fluxtion.runtime.service.ServiceRegistryQuery> queryOpt =
                java.util.Optional.empty();
        try {
            queryOpt = processor.serviceRegistryQuery();
        } catch (NoSuchMethodError | AbstractMethodError olderRuntime) {
            // fluxtion-runtime predates ServiceRegistryQuery — fall through.
        }

        List<Map<String, Object>> inputs = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Map<String, Object>> services = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (queryOpt.isEmpty()) {
            warnings.add("ServiceRegistryQuery not available on this processor — "
                    + "compliance view limited to services without binding attribution. "
                    + "Rebuild against fluxtion-runtime ≥ 1.0.1 for full coverage.");
        }

        // Topology data — used to attribute feeds. Feeds bind via
        // @OnEventHandler subscriptions through EventFlowManager, NOT via
        // @ServiceRegistered, so ServiceRegistryQuery can't see them; we
        // have to walk the queue topology to discover which feeds this
        // processor's agent group consumes.
        List<Map<String, Object>> topology = currentTopologySources();

        // Sinks this processor writes to via the DSL .sink("name") terminator
        // — generated as `SinkPublisher` fields on the SEP. This is the
        // mechanism flow-builder graphs use; @ServiceRegistered MessageSink
        // is the imperative-node mechanism. Both apply; either is enough
        // to attribute a sink to this processor.
        Set<String> publishedSinkNames = publisherSinkNames(processor);

        for (Map.Entry<String, com.telamin.fluxtion.runtime.service.Service<?>> e :
                serverController.registeredServices().entrySet()) {
            String svcName = e.getKey();
            Object instance = e.getValue().instance();
            if (instance == null) continue;

            String kind = classifyService(instance);

            List<String> consumerNodes;
            try {
                if ("feed".equals(kind)) {
                    // Feed attribution from the queue topology.
                    consumerNodes = feedConsumersForGroup(svcName, group, topology);
                    // A service can be BOTH a feed (implements EventSource)
                    // and a @ServiceRegistered binding target — common for
                    // infrastructure services like AdminCommandRegistry.
                    // If the topology says this group doesn't subscribe but
                    // the binding graph says it consumes, reclassify so the
                    // entry lands in `services` rather than being dropped.
                    if (consumerNodes.isEmpty()) {
                        List<String> bindingConsumers = queryOpt
                                .map(q -> consumersForBinding(q, instance.getClass(), svcName, instance))
                                .orElse(java.util.Collections.emptyList());
                        if (!bindingConsumers.isEmpty()) {
                            consumerNodes = bindingConsumers;
                            kind = "service";   // reclassify
                        }
                    }
                } else if ("sink".equals(kind)) {
                    // Sink attribution: @ServiceRegistered binding OR DSL
                    // SinkPublisher field. Either one means this processor
                    // writes to the sink.
                    consumerNodes = queryOpt
                            .map(q -> consumersForBinding(q, instance.getClass(), svcName, instance))
                            .orElse(java.util.Collections.emptyList());
                    if (consumerNodes.isEmpty() && publishedSinkNames.contains(svcName)) {
                        // Attribute to the SinkPublisher field's target name;
                        // the field name is the DSL sink id and travels with
                        // the generated source for inspection.
                        consumerNodes = java.util.Collections.singletonList(svcName);
                    }
                } else {
                    // Other services: @ServiceRegistered binding only.
                    consumerNodes = queryOpt
                            .map(q -> consumersForBinding(q, instance.getClass(), svcName, instance))
                            .orElse(java.util.Collections.emptyList());
                }
            } catch (Throwable t) {
                // A buggy single-service lookup shouldn't abort the whole
                // report — log and continue with no attribution.
                log.warn("compliance: consumer lookup failed for service '{}'", svcName, t);
                consumerNodes = java.util.Collections.emptyList();
            }

            // Skip services this processor doesn't bind to. OUTSIDE the
            // try/catch so a thrown lookup (consumerNodes ends up empty)
            // doesn't accidentally include the service in the report.
            // For non-feed/sink categories, when queryOpt is absent we
            // can't tell — fall through and include everything.
            boolean skip;
            if ("feed".equals(kind) || "sink".equals(kind)) {
                skip = consumerNodes.isEmpty();
            } else {
                skip = queryOpt.isPresent() && consumerNodes.isEmpty();
            }

            if (skip) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", svcName);
            entry.put("type", kind);
            entry.put("className", instance.getClass().getName());
            entry.put("config", summarizeConfig(instance));
            entry.put("consumerNodes", consumerNodes);

            switch (kind) {
                case "feed":    inputs.add(entry); break;
                case "sink":    outputs.add(entry); break;
                default:        services.add(entry);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processor", name);
        body.put("group", group);
        body.put("className", processor.getClass().getName());
        body.put("inputs", inputs);
        body.put("outputs", outputs);
        body.put("services", services);
        if (!warnings.isEmpty()) body.put("warnings", warnings);

        ctx.json(body);
    }

    /**
     * Find the node ids inside the processor that declared
     * {@code @ServiceRegistered} for a given service binding.
     *
     * <p>{@code @ServiceRegistered} binds on the declared parameter type
     * (typically an interface like {@code MessageSink} or {@code EventFeed},
     * not the concrete class). Walks the instance's interface + superclass
     * chain so a sink registered as {@code MessageSink<Trade>} still matches
     * a node that declared {@code MessageSink<?>} as its dependency.
     *
     * <p>Two listener shapes are surfaced:
     * <ol>
     *   <li><b>Statically named</b> — {@code @ServiceRegistered("foo") void on(T svc)}.
     *       Matched via {@link com.telamin.fluxtion.runtime.service.ServiceRegistryQuery#findNamedDependency}.</li>
     *   <li><b>Any-name dispatch</b> — {@code @ServiceRegistered void on(T svc, String name)}.
     *       This is mongoose's idiomatic pattern: one method receives every
     *       service of that type and the node dispatches by {@code name}
     *       internally (typically a switch). For compliance purposes the
     *       node IS a code-path consumer of every named service of that
     *       type — the exact runtime branch lives in the node source, not
     *       in the dependency graph. We include these in the consumer list
     *       so the report reflects the full set of nodes that could touch
     *       this service.</li>
     * </ol>
     */
    /**
     * SEP infrastructure node names — added by Fluxtion's codegen on every
     * generated processor regardless of user code. These bind to a wide set
     * of services internally (e.g. {@code subscriptionManager} consumes every
     * {@code NamedFeed}, {@code serviceRegistry} consumes every registered
     * service) but they're not user-meaningful compliance attribution —
     * surfacing them as "consumers" creates the appearance that every
     * processor uses every feed/service. Filter them out.
     */
    private static final Set<String> INFRASTRUCTURE_NODE_NAMES = Set.of(
            "subscriptionManager",
            "nodeNameLookup",
            "serviceRegistry",
            "clock",
            "perfMon",
            "eventLogManager",
            "context"
    );

    private static List<String> consumersForBinding(
            com.telamin.fluxtion.runtime.service.ServiceRegistryQuery q,
            Class<?> instanceType,
            String svcName,
            Object svcInstance) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Class<?> candidate : collectTypeHierarchy(instanceType)) {
            // Statically-named: exact (class, name) match.
            q.findNamedDependency(candidate, svcName).ifPresent(d -> {
                for (com.telamin.fluxtion.runtime.service.ServiceDependency.ConsumerNode cn : d.consumers()) {
                    if (cn.nodeName() != null && !INFRASTRUCTURE_NODE_NAMES.contains(cn.nodeName())) {
                        out.add(cn.nodeName());
                    }
                }
            });
            // Any-name dispatch: nodes that receive every service of this type.
            for (com.telamin.fluxtion.runtime.service.ServiceDependency d :
                    q.serviceDependencies(candidate)) {
                if (d.serviceName() != null) continue; // already covered above
                for (com.telamin.fluxtion.runtime.service.ServiceDependency.ConsumerNode cn : d.consumers()) {
                    if (cn.nodeName() == null) continue;
                    if (INFRASTRUCTURE_NODE_NAMES.contains(cn.nodeName())) continue;
                    Object node = cn.node();
                    // SinkPublisher: declares its target sink name at
                    // construction; only dispatches to the registered service
                    // whose name matches.
                    if (node instanceof com.telamin.fluxtion.runtime.output.SinkPublisher<?>) {
                        String target = ((com.telamin.fluxtion.runtime.output.SinkPublisher<?>) node).getName();
                        if (svcName.equals(target)) out.add(cn.nodeName());
                        continue;
                    }
                    // MessageSink any-name (`void on(MessageSink, String name)`
                    // → switch dispatch) is the common multi-instance case.
                    // The dep graph can't see the switch, so we runtime-verify:
                    // walk the node's fields and check whether the actual
                    // sink instance is stored anywhere. If yes, the node
                    // demonstrably has a code path to this sink. If no,
                    // exclude — over-attribution.
                    boolean isMessageSink = candidate != null
                            && com.telamin.fluxtion.runtime.output.MessageSink.class.isAssignableFrom(candidate);
                    if (isMessageSink && svcInstance != null) {
                        if (nodeStoresInstance(node, svcInstance)) {
                            out.add(cn.nodeName());
                        }
                        // else: node doesn't actually hold this sink — skip
                        continue;
                    }
                    // Other any-name consumers (single-instance services like
                    // SchedulerService, AdminCommandRegistry). Over-attribution
                    // doesn't apply because there's only one instance per
                    // class; include unconditionally.
                    out.add(cn.nodeName());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Runtime-verified field check — walks {@code node}'s declared fields
     * (including inherited) for a value identity-equal to {@code instance}.
     *
     * <p>Used to disambiguate the {@code @ServiceRegistered void on(T, String name)}
     * any-name dispatch pattern. The dependency graph says "this node
     * consumes every T", but the switch inside the method only stores
     * specific ones. After service registration completes, the node's
     * fields hold exactly the instances its switch accepted; identity
     * comparison against each registered instance recovers the actual
     * binding precisely.
     */
    private static boolean nodeStoresInstance(Object node, Object instance) {
        if (node == null || instance == null) return false;
        Class<?> c = node.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(node) == instance) return true;
                } catch (Throwable ignored) {
                    // A non-readable field shouldn't suppress the others.
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * Find the sink names this processor writes to via the DSL
     * {@code .sink("name")} terminator. The fluxtion-builder DSL generates a
     * {@link com.telamin.fluxtion.runtime.output.SinkPublisher} field on the
     * SEP for each terminator — the field's constructor argument is the
     * sink name to publish to. We reflect over the SEP's declared fields
     * to enumerate them.
     *
     * <p>Complementary to {@link #consumersForBinding}, which attributes
     * via {@code @ServiceRegistered MessageSink} listeners on imperative
     * nodes. Either path is sufficient evidence that this processor writes
     * to the named sink.
     *
     * <p>Quiet on any reflection failure — a field that won't yield its
     * value just doesn't contribute to the set; the report still renders.
     */
    private static Set<String> publisherSinkNames(com.telamin.fluxtion.runtime.DataFlow processor) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        Class<?> cls = processor.getClass();
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (!com.telamin.fluxtion.runtime.output.SinkPublisher.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(processor);
                if (v instanceof com.telamin.fluxtion.runtime.output.SinkPublisher<?>) {
                    String n = ((com.telamin.fluxtion.runtime.output.SinkPublisher<?>) v).getName();
                    if (n != null && !n.isEmpty()) out.add(n);
                }
            } catch (Throwable ignored) {
                // One bad field shouldn't suppress the rest.
            }
        }
        return out;
    }

    /**
     * Find the event types this processor's agent group subscribes to from
     * a given feed. Feeds are wired through {@code EventFlowManager} via
     * {@code @OnEventHandler} subscriptions, NOT {@code @ServiceRegistered},
     * so {@link com.telamin.fluxtion.runtime.service.ServiceRegistryQuery}
     * can't attribute them — we walk the queue topology instead.
     *
     * <p>Returns the simple class names of the events delivered to this
     * group's processors (e.g. {@code ["Trade", "MidPrice"]}). Empty list
     * means this processor's group is not a consumer of this feed.
     */
    private static List<String> feedConsumersForGroup(
            String feedName, String group, List<Map<String, Object>> topology) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Map<String, Object> src : topology) {
            if (!feedName.equals(src.get("source"))) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queues = (List<Map<String, Object>>) src.get("queues");
            if (queues == null) continue;
            for (Map<String, Object> q : queues) {
                if (!group.equals(q.get("agentGroup"))) continue;
                Object cb = q.get("callback");
                if (cb != null) out.add(String.valueOf(cb));
            }
        }
        return new ArrayList<>(out);
    }

    /** Collect all classes + interfaces in the type's hierarchy — used to
     *  match {@code @ServiceRegistered} bindings against the abstract types
     *  they were declared on rather than the concrete service class.
     *
     *  <p>Null-safe: {@link Class#getSuperclass()} returns {@code null} for
     *  interfaces and primitives, so we filter at the add site rather than
     *  letting {@link java.util.ArrayDeque#add} blow up on nulls — that NPE
     *  poisoned every non-feed lookup before the fix. */
    private static Set<Class<?>> collectTypeHierarchy(Class<?> type) {
        java.util.LinkedHashSet<Class<?>> out = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<Class<?>> q = new java.util.ArrayDeque<>();
        if (type != null) q.add(type);
        while (!q.isEmpty()) {
            Class<?> c = q.poll();
            if (c == Object.class || !out.add(c)) continue;
            Class<?> sup = c.getSuperclass();
            if (sup != null) q.add(sup);
            for (Class<?> i : c.getInterfaces()) q.add(i);
        }
        return out;
    }

    /**
     * Per-service configuration view — reflects public bean-style getters on
     * the registered instance into a key/value table. Sensitive-named getters
     * (password / secret / token / credential / apikey / privatekey /
     * accesskey) are masked. Complex values (collections, maps, arrays, large
     * strings) are summarised so the panel doesn't blow up on a setting that
     * happens to hold a megabyte of data.
     */
    private void handleServiceConfig(Context ctx) {
        if (serverController == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "MongooseServerController not available"));
            return;
        }
        String name = ctx.pathParam("name");
        com.telamin.fluxtion.runtime.service.Service<?> svc =
                serverController.registeredServices().get(name);
        if (svc == null || svc.instance() == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "service not found: " + name));
            return;
        }
        ctx.json(Map.of(
                "name", name,
                "className", svc.instance().getClass().getName(),
                "config", summarizeConfig(svc.instance())));
    }

    /**
     * Reflective key/value summary of a service instance. Walks public bean
     * accessors ({@code getX()}/{@code isX()}), masks sensitive-named
     * properties, and returns a stable, alpha-sorted list of entries.
     * <p>
     * Safe-by-default: any getter that throws is silently skipped (one bad
     * field shouldn't break the panel); duplicate {@code get/is} pairs are
     * de-duplicated by property name.
     */
    static List<Map<String, Object>> summarizeConfig(Object instance) {
        java.util.LinkedHashMap<String, Map<String, Object>> byName = new java.util.LinkedHashMap<>();
        for (java.lang.reflect.Method m : instance.getClass().getMethods()) {
            if (!isBeanReadAccessor(m)) continue;
            String propertyName = beanPropertyName(m);
            if (propertyName.isEmpty() || byName.containsKey(propertyName)) continue;
            Object value;
            try {
                m.setAccessible(true);
                value = m.invoke(instance);
            } catch (Throwable t) {
                continue; // a buggy/lazy getter shouldn't break the whole panel
            }
            boolean sensitive = isSensitiveName(propertyName);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", propertyName);
            entry.put("value", sensitive && value != null ? "***" : describeValue(value));
            entry.put("sensitive", sensitive);
            byName.put(propertyName, entry);
        }
        List<Map<String, Object>> out = new ArrayList<>(byName.values());
        out.sort(java.util.Comparator.comparing(e -> String.valueOf(e.get("name"))));
        return out;
    }

    private static boolean isBeanReadAccessor(java.lang.reflect.Method m) {
        if (m.getParameterCount() != 0) return false;
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) return false;
        if (m.getDeclaringClass() == Object.class) return false;
        Class<?> ret = m.getReturnType();
        if (ret == void.class) return false;
        String n = m.getName();
        if ("getClass".equals(n)) return false;
        if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) return true;
        return n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2))
                && (ret == boolean.class || ret == Boolean.class);
    }

    private static String beanPropertyName(java.lang.reflect.Method m) {
        String n = m.getName();
        String stripped = n.startsWith("get") ? n.substring(3) : n.substring(2);
        if (stripped.isEmpty()) return stripped;
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    /**
     * Conservative sensitive-name heuristic — masks values for getters whose
     * property name contains a high-confidence sensitive token. We avoid
     * broad terms like "key" alone (false positives on things like
     * {@code partitionKey}) and require either an explicit hit like
     * {@code password} / {@code secret} or a compound like {@code apiKey} /
     * {@code accessKey} / {@code privateKey}.
     */
    static boolean isSensitiveName(String name) {
        String lc = name.toLowerCase();
        return lc.contains("password")
                || lc.contains("passwd")
                || lc.contains("secret")
                || lc.contains("token")
                || lc.contains("credential")
                || lc.contains("apikey")
                || lc.contains("privatekey")
                || lc.contains("accesskey");
    }

    /**
     * Render a property value into something JSON-safe and human-readable.
     * Primitives, enums, and short strings are returned verbatim;
     * collections/maps/arrays are summarised as {@code Type size=N}; long
     * strings are truncated with an ellipsis.
     */
    static Object describeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) return value;
        if (value instanceof CharSequence) {
            String s = value.toString();
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        }
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof java.util.Collection<?> c) {
            return value.getClass().getSimpleName() + " size=" + c.size();
        }
        if (value instanceof Map<?, ?> mp) {
            return value.getClass().getSimpleName() + " size=" + mp.size();
        }
        if (value.getClass().isArray()) {
            return value.getClass().getSimpleName() + " len=" + java.lang.reflect.Array.getLength(value);
        }
        String s = String.valueOf(value);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private void handleQueues(Context ctx) {
        if (eventFlowManager == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "EventFlowManager not available"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        try {
            eventFlowManager.appendQueueInformation(sb);
        } catch (Exception e) {
            log.warn("queue introspection failed", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "queue introspection failed: " + e.getMessage()));
            return;
        }
        ctx.json(Map.of("sources", parseEventSources(sb.toString())));
    }

    /**
     * Parse {@code EventFlowManager.appendQueueInformation} text — {@code
     * eventSource:<name>} blocks each followed by indented
     * {@code <agentGroup>/<feed>/<callback> -> <queue>} read-queue lines — into
     * structured per-source entries. The {@code eventSources} admin command
     * emits the same text, but this endpoint reads the flow manager directly,
     * so it works even when that command is not registered.
     */
    static List<Map<String, Object>> parseEventSources(String text) {
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> queues = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("readQueues:")) continue;
            if (line.startsWith("eventSource:")) {
                queues = new ArrayList<>();
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("source", line.substring("eventSource:".length()).trim());
                source.put("queues", queues);
                sources.add(source);
            } else if (queues != null && line.contains("->")) {
                String path = line.substring(0, line.indexOf("->")).trim();
                String[] parts = path.split("/");
                String callback = parts[parts.length - 1];
                Map<String, Object> queue = new LinkedHashMap<>();
                queue.put("path", path);
                queue.put("agentGroup", parts[0].isEmpty() ? path : parts[0]);
                queue.put("callback", callback.contains(".")
                        ? callback.substring(callback.lastIndexOf('.') + 1) : callback);
                queues.add(queue);
            }
        }
        return sources;
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
                // Absolute baseDir — clients use this to build full paths
                // before submitting to admin commands. The loader services
                // resolve paths against the server's CWD, NOT against
                // loaderBaseDir, so a picker that returned just "file.yaml"
                // wouldn't be found unless the user happened to launch
                // from loaderBaseDir's parent.
                "baseDir", base.toAbsolutePath().toString().replace('\\', '/'),
                "cwd", relForResp,
                "entries", entries));
    }

    /** Drag-and-drop upload from the Loader tab. Writes the request
     *  body (text/plain) to {@code loaderBaseDir/uploads/<filename>}
     *  and returns the absolute saved path so the client can use it
     *  as the {@code path} arg to a follow-up compile / persist
     *  command. {@code X-Filename} carries the operator's filename;
     *  basename-validated against path traversal. */
    private void handleLoaderUpload(Context ctx) {
        if (loaderBaseDir == null || loaderBaseDir.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            ctx.json(Map.of("err", "loaderBaseDir is not configured"));
            return;
        }
        String filename = ctx.header("X-Filename");
        if (filename == null || filename.isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "X-Filename header required"));
            return;
        }
        // Reject anything that could escape the upload dir. Operator-
        // supplied filename can only contain word chars, dot, dash;
        // no slashes, no dot-dot, no NUL.
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (slash >= 0) filename = filename.substring(slash + 1);
        if (filename.contains("..") || filename.isBlank() || !filename.matches("[A-Za-z0-9._-]{1,256}")) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "invalid filename: " + filename));
            return;
        }
        String body = ctx.body();
        if (body == null || body.isEmpty()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "empty body"));
            return;
        }
        java.nio.file.Path uploadsDir;
        try {
            java.nio.file.Path base = java.nio.file.Paths.get(loaderBaseDir).toAbsolutePath();
            uploadsDir = base.resolve("uploads");
            java.nio.file.Files.createDirectories(uploadsDir);
        } catch (java.io.IOException io) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "uploads dir unusable: " + io.getMessage()));
            return;
        }
        java.nio.file.Path dest = uploadsDir.resolve(filename);
        // Belt + braces: after resolve, verify the result stays under
        // uploadsDir. Defends against any pattern that slipped past
        // the regex above.
        if (!dest.normalize().startsWith(uploadsDir.normalize())) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "filename resolves outside uploads dir"));
            return;
        }
        try {
            java.nio.file.Files.writeString(dest, body, StandardCharsets.UTF_8);
        } catch (java.io.IOException io) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "write failed: " + io.getMessage()));
            return;
        }
        log.info("uploaded {} bytes to {}", body.length(), dest);
        ctx.json(Map.of(
                "path", dest.toString(),
                "filename", filename,
                "bytes", body.length()));
    }

    // FQN -> .java text lookup. Two-tier resolution:
    //   1. Filesystem — walks sourceRoots in declared order, first hit wins
    //      (live-edit dev experience beats packaged copy). Gated on a
    //      non-empty sourceRoots — operators must explicitly name which
    //      directories the HTTP surface may read from.
    //   2. Classpath — ClassLoader.getResourceAsStream(<pkg>/<Class>.java).
    //      Always-on. Fluxtion 1.0.2+ packages generated source as a
    //      classpath resource via copySourceToResourcesDirectory, and
    //      anything reachable via the classloader is already in the
    //      deployable artefact (`jar tf` shows it) — gating this tier
    //      adds no real protection. Operators who genuinely need to
    //      disable the endpoint should remove the route registration
    //      (or front-proxy 403 it).
    // The fqn query param must match a strict identifier pattern (letters
    // / digits / underscore / dot, optional `$` for inner classes) — that
    // is the *only* gate against path traversal; we never pass user input
    // into Path.resolve raw. After resolve+toRealPath, the resolved file
    // is verified to live under the root (catches symlink escape).
    private static final java.util.regex.Pattern FQN_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    private void handleSourceLookup(Context ctx) {
        String fqn = ctx.queryParam("fqn");
        if (fqn == null || !FQN_PATTERN.matcher(fqn).matches()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("err", "fqn missing or not a valid class name"));
            return;
        }
        // Inner classes (`Outer$Inner`) live in the Outer.java file — strip
        // anything from the first `$` so the path matches the source file.
        String topLevel = fqn;
        int dollar = topLevel.indexOf('$');
        if (dollar > 0) topLevel = topLevel.substring(0, dollar);
        String relPath = topLevel.replace('.', '/') + ".java";

        // Tier 1 — filesystem sourceRoots. Filesystem first so live edits
        // beat the packaged copy during local development. Skipped when
        // operator hasn't opted in via sourceRoots.
        List<String> roots = sourceRoots == null ? Collections.emptyList() : sourceRoots;
        for (String rootSpec : roots) {
            java.nio.file.Path root;
            try {
                root = java.nio.file.Paths.get(rootSpec).toRealPath();
            } catch (java.io.IOException missing) {
                continue; // configured root doesn't exist — skip silently
            }
            java.nio.file.Path candidate;
            try {
                candidate = root.resolve(relPath).toRealPath();
            } catch (java.io.IOException missing) {
                continue;
            }
            if (!candidate.startsWith(root)) {
                continue; // symlink escape
            }
            if (!java.nio.file.Files.isRegularFile(candidate)) {
                continue;
            }
            String text;
            try {
                text = new String(java.nio.file.Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            } catch (java.io.IOException io) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                ctx.json(Map.of("err", "read failed: " + io.getMessage()));
                return;
            }
            ctx.json(Map.of(
                    "fqn", fqn,
                    "path", relPath,
                    "root", rootSpec,
                    "source", text));
            return;
        }
        // Tier 2 — classpath fallback. Fluxtion 1.0.2+ packages generated
        // .java alongside the .graphml via FluxtionCompilerConfig's
        // copySourceToResourcesDirectory flag (default on). That source
        // travels inside the shaded jar as a classpath resource at the
        // package-mirrored path. This branch makes the lookup work when
        // the runtime is detached from its build tree — containers, prod
        // deployments, downloaded uber-jars moved elsewhere.
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = WebAdminService.class.getClassLoader();
        try (java.io.InputStream in = cl.getResourceAsStream(relPath)) {
            if (in != null) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                ctx.json(Map.of(
                        "fqn", fqn,
                        "path", relPath,
                        "root", "classpath:",
                        "source", text));
                return;
            }
        } catch (java.io.IOException io) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "classpath read failed: " + io.getMessage()));
            return;
        }
        ctx.status(HttpStatus.NOT_FOUND);
        ctx.json(Map.of(
                "err", "no source found for " + fqn,
                "searched", roots,
                "classpathChecked", true,
                "relPath", relPath));
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
        log.info("WS upgrade attempt: path={} origin='{}' boundHost='{}' listenPort={}",
                ctx.path(), origin, host, listenPort);
        if (origin != null && !originAllowed(origin)) {
            log.warn("WS upgrade REJECTED: origin '{}' not allowed (bound host='{}', listenPort={})",
                    origin, host, listenPort);
            throw new UnauthorizedResponse("origin not allowed: " + origin, Map.of());
        }
        log.info("WS upgrade ACCEPTED: path={}", ctx.path());
    }

    private boolean originAllowed(String origin) {
        // Default policy: same port; same host OR loopback-equivalent
        // (localhost ⇄ 127.0.0.1 ⇄ ::1) OR server bound to 0.0.0.0
        // (accept-any). Behind a reverse proxy on a different host this
        // becomes a config knob; deferred. The old policy was exact
        // string equality, which silently rejected every WS upgrade when
        // the bind host and the access host disagreed (e.g. server bound
        // to `localhost`, browser hits `127.0.0.1`).
        try {
            java.net.URI uri = java.net.URI.create(origin);
            if (uri.getPort() != listenPort) return false;
            String originHost = uri.getHost();
            if (originHost == null) return false;
            if (originHost.equalsIgnoreCase(host)) return true;
            if ("0.0.0.0".equals(host)) return true;       // bound to all
            if (isLoopback(originHost) && isLoopback(host)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLoopback(String h) {
        if (h == null) return false;
        return "localhost".equalsIgnoreCase(h)
                || "127.0.0.1".equals(h)
                || "::1".equals(h)
                || "[::1]".equals(h);
    }

    private void configureMonitorWs(WsConfig ws) {
        ws.onConnect(ctx -> {
            try { ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(10)); }
            catch (Throwable t) { log.warn("could not set ws idle timeout", t); }
            monitorClients.add(ctx);
            // Default rate = the operator-configured interval so behaviour
            // matches the pre-control baseline until the client explicitly
            // picks a rate.
            monitorClientRates.put(ctx, (long) metricsIntervalMs);
            reconfigureMonitorSampler();
            // Send a fresh snapshot immediately so the dashboard populates
            // before the first scheduled tick lands.
            try {
                ctx.send(MonitoringSampler.snapshot());
            } catch (Exception e) {
                log.warn("initial monitor snapshot failed", e);
            }
        });
        ws.onMessage(ctx -> {
            // Lightweight protocol: {"op":"rate","ms":<int>}. ms <= 0 means
            // the client has selected "Off" — we mark them paused and stop
            // delivering to them, plus reconfigure the sampler in case every
            // client is now off.
            try {
                MonitorClientMessage msg = ctx.messageAsClass(MonitorClientMessage.class);
                if (msg != null && "rate".equals(msg.op)) {
                    long ms = msg.ms == null ? 0L : msg.ms;
                    // Clamp to the operator-configured minimum so the UI can't
                    // drive the server below policy. ms == 0 keeps the "off"
                    // semantic distinct from a clamped low value.
                    long clamped = ms <= 0 ? 0L : Math.max(ms, metricsIntervalMs);
                    monitorClientRates.put(ctx, clamped);
                    reconfigureMonitorSampler();
                }
            } catch (Exception e) {
                log.debug("ignoring malformed monitor ws message: {}", e.toString());
            }
        });
        ws.onClose(ctx -> {
            monitorClients.remove(ctx);
            monitorClientRates.remove(ctx);
            reconfigureMonitorSampler();
        });
        ws.onError(ctx -> {
            log.warn("monitor ws error", ctx.error());
            monitorClients.remove(ctx);
            monitorClientRates.remove(ctx);
            reconfigureMonitorSampler();
        });
    }

    /**
     * Recompute the sampler's effective period from the current per-client
     * preferences. Pauses the sampler when every connected client is
     * "Off" — that drops the {@code JvmSnapshot} allocation rate to zero
     * (motivating bug: the dashboard's 1 Hz cadence was a measurable GC
     * source when no human was watching).
     */
    private void reconfigureMonitorSampler() {
        if (monitoringSampler == null) return;
        long best = Long.MAX_VALUE;
        for (long ms : monitorClientRates.values()) {
            if (ms > 0 && ms < best) best = ms;
        }
        if (best == Long.MAX_VALUE) {
            monitoringSampler.setPaused(true);
        } else {
            monitoringSampler.setIntervalMs(best);
        }
    }

    /** WS message DTO — public for Jackson. */
    public static class MonitorClientMessage {
        public String op;
        public Long ms;
    }

    private void broadcastMonitorSnapshot(MonitoringSampler.JvmSnapshot snapshot) {
        for (WsContext c : monitorClients) {
            // Honour per-client "Off" by skipping delivery — sampler is
            // already running at the min rate any client wants, so the
            // off-clients just don't see the broadcasts.
            Long rate = monitorClientRates.get(c);
            if (rate != null && rate <= 0) continue;
            try {
                if (c.session.isOpen()) {
                    c.send(snapshot);
                } else {
                    monitorClients.remove(c);
                    monitorClientRates.remove(c);
                }
            } catch (Exception e) {
                log.warn("monitor send failed; dropping client", e);
                monitorClients.remove(c);
                monitorClientRates.remove(c);
            }
        }
    }

    // -------- WebSocket logs --------

    private void configureLogsWs(WsConfig ws) {
        ws.onConnect(ctx -> {
            // Jetty default idle timeout is 30s — after boot the server
            // quiets down and the WS closes silently, so the log-pill
            // flips to "Disconnected" even though everything is healthy.
            // 10 minutes is conservative; clients can opt to reconnect
            // sooner. Combined with client-side auto-reconnect this
            // covers both idle close and transient network drops.
            try {
                ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(10));
            } catch (Throwable t) {
                log.warn("could not set ws idle timeout", t);
            }
            log.info("/ws/logs onConnect — clients now {}", logClients.size() + 1);
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
        ws.onClose(ctx -> {
            log.info("/ws/logs onClose status={} reason={}", ctx.status(), ctx.reason());
            logClients.remove(ctx);
        });
        ws.onError(ctx -> {
            log.warn("/ws/logs onError", ctx.error());
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
