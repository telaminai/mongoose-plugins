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

        // Dispatcher introspection. Services/agents are structured JSON sourced
        // by invoking + parsing the server.service.list / server.processors.list
        // admin commands. Queues read the injected EventFlowManager directly —
        // no dependency on the `eventSources` command being registered.
        javalin.get("/api/services", this::handleServices);
        javalin.get("/api/services/{name}/config", this::handleServiceConfig);
        javalin.get("/api/agents", this::handleAgents);
        javalin.get("/api/queues", this::handleQueues);
        javalin.get("/api/processors/{group}/{name}/graphml", this::handleProcessorGraphml);

        // Conditional file picker for loader forms. Always mounted, but returns
        // 404 when loaderBaseDir is unset so the UI hides the tab automatically.
        javalin.get("/api/files", this::handleListFiles);

        // Monitoring WebSocket. Same auth filter applies to the HTTP upgrade
        // request because Javalin's before() runs on the upgrade. CSRF on WS
        // is carried as ?csrf=... query param (browsers can't add headers).
        javalin.before("/ws/*", this::enforceWsUpgradeAuth);
        javalin.ws("/ws/monitor", this::configureMonitorWs);
        javalin.ws("/ws/logs",    this::configureLogsWs);

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

        List<Map<String, Object>> services = new ArrayList<>();
        serverController.registeredServices().forEach((name, svc) -> {
            Object instance = svc.instance();
            Class<?> svcClass = svc.serviceClass();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            String type = classifyService(instance);
            entry.put("type", type);
            entry.put("className", svcClass != null
                    ? svcClass.getName()
                    : (instance != null ? instance.getClass().getName() : ""));
            if ("feed".equals(type)) {
                entry.put("consumers", consumersByFeed.getOrDefault(name, Collections.emptyList()));
            }
            services.add(entry);
        });
        ctx.json(Map.of("services", services));
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

        try (java.io.InputStream in = loader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                // Structured miss: frontend turns this into a friendly guide
                // ("expected /package/Class.graphml — copy the generated file
                //  into src/main/resources/<package>/<Class>.graphml").
                ctx.status(HttpStatus.NOT_FOUND);
                ctx.json(Map.of(
                        "err", "graphml resource not found on the processor's classloader",
                        "className", cls.getName(),
                        "expectedResource", resourcePath,
                        "hint", "Fluxtion writes <ClassName>.graphml alongside the generated source. "
                              + "Copy it into src/main/resources/" + cls.getName().replace('.', '/') + ".graphml "
                              + "(or otherwise add it to the runtime jar) so the admin UI can render the internal DAG."));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ctx.contentType("application/xml; charset=utf-8");
            ctx.result(bytes);
        } catch (java.io.IOException e) {
            log.warn("graphml read failed for {}", cls.getName(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("err", "graphml read failed: " + e.getMessage()));
        }
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
