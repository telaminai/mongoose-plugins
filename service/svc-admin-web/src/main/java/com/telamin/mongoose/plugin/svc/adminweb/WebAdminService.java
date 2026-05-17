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
import io.javalin.Javalin;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Browser-based admin & monitoring UI for Mongoose. Presentation layer over
 * {@link AdminCommandRegistry} — the same command surface that
 * {@code svc-admin-telnet} and {@code svc-admin-rest} drive.
 *
 * <p>M1: module skeleton. Binds Javalin on {@code host:listenPort} and serves
 * {@code /healthz} only. Auth, session, command surface, and UI ship in
 * M2–M7. See {@code design/svc-admin-web.md} for the full spec.
 */
@Log4j2
public class WebAdminService implements EventFlowService<Object>, Lifecycle {

    public enum AuthMode {NONE, BASIC, BEARER}

    private Javalin javalin;
    private EventFlowManager eventFlowManager;
    private AdminCommandRegistry adminCommandRegistry;

    // bind config
    @Getter @Setter private int    listenPort = 8181;
    @Getter @Setter private String host       = "127.0.0.1";
    @Getter @Setter private String basePath   = "/";

    // auth config (wiring lands in M2)
    @Getter @Setter private AuthMode authMode = AuthMode.NONE;
    @Getter @Setter private String username;
    @Setter         private String password;
    @Setter         private String bearerToken;
    @Getter @Setter private String realm = "mongoose-admin";

    // monitoring config (wiring lands in M4 / M5)
    @Getter @Setter private int    metricsIntervalMs = 1000;  // clamped >= 250 ms at start()
    @Getter @Setter private int    logTailBuffer     = 500;
    @Getter @Setter private String loaderBaseDir;

    // session config (wiring lands in M2)
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
    }

    @Override
    public void start() {
        log.info("starting web admin UI on http://{}:{}{}", host, listenPort, basePath);
        javalin = Javalin.create().start(host, listenPort);
        // M1: liveness probe only. Auth, session, command surface, UI ship later.
        javalin.get("/healthz", ctx -> ctx.result("OK"));
    }

    @Override
    public void tearDown() {
        if (javalin != null) {
            log.info("stopping web admin UI");
            javalin.stop();
            javalin = null;
        }
    }
}
