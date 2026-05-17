/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.fluxtion.dataflow.serverplugin.svc.adminrest;


import com.telamin.fluxtion.runtime.annotations.Start;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;

import java.util.Map;
import lombok.*;
import lombok.extern.log4j.Log4j2;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Log4j2
public class JavalinAdminCommandService implements EventFlowService<Object>, Lifecycle {

    public enum AuthMode {NONE, BASIC, BEARER}

    private Javalin javalin;
    private EventFlowManager eventFlowManager;
    private AdminCommandRegistry adminCommandRegistry;
    @Getter
    @Setter
    private int listenPort = 8080;
    @Getter
    @Setter
    private String host = "0.0.0.0";
    @Getter
    @Setter
    private String staticDir;

    @Getter
    @Setter
    private AuthMode authMode = AuthMode.NONE;
    @Getter
    @Setter
    private String username;
    @Setter
    private String password;
    @Setter
    private String bearerToken;
    @Getter
    @Setter
    private String realm = "mongoose-admin";

    @Override
    public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
        log.info("set eventFlowManager name:'{}' for Javalin REST", serviceName);
        this.eventFlowManager = eventFlowManager;
    }

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        this.adminCommandRegistry = adminCommandRegistry;
    }

    // EventFlowService -> EventSource contract: this REST admin endpoint
    // does not publish events into the dispatch pipeline; the methods are
    // intentional no-ops.
    @Override
    public void setEventToQueuePublisher(com.telamin.mongoose.dispatch.EventToQueuePublisher<Object> targetQueue) {
        // no-op
    }

    @Override
    public void subscribe(com.telamin.mongoose.service.EventSubscriptionKey<Object> eventSourceKey) {
        // no-op
    }

    @Override
    public void unSubscribe(com.telamin.mongoose.service.EventSubscriptionKey<Object> eventSourceKey) {
        // no-op
    }

    @Override
    public void init() {
        log.info("init Javalin REST service listening on {}:{} auth:{}", host, listenPort, authMode);
        if (authMode == AuthMode.BASIC && (resolvedUsername() == null || resolvedPassword() == null)) {
            throw new IllegalStateException("BASIC auth selected but username/password not configured");
        }
        if (authMode == AuthMode.BEARER && (resolvedBearerToken() == null || resolvedBearerToken().isEmpty())) {
            throw new IllegalStateException("BEARER auth selected but bearerToken not configured");
        }

        javalin = Javalin.create(config -> {
                    if (staticDir != null) {
                        config.staticFiles.add(staticDir, Location.EXTERNAL);
                    }
                })
                .before("/admin", this::enforceAuth)
                .before("/api/*", this::enforceAuth)
                .post("/admin", ctx -> {
                    AdminCommandRequest adminCommandRequest = ctx.bodyAsClass(AdminCommandRequest.class);
                    adminCommandRequest.setOutput(out -> ctx.json(new Message(out.toString())));
                    adminCommandRequest.setErrOutput(out -> ctx.json(new Message("Failure - " + out)));
                    log.info("adminCommandRequest: {}", adminCommandRequest);
                    if (adminCommandRegistry != null) {
                        adminCommandRegistry.processAdminCommandRequest(adminCommandRequest);
                    }
                })
                .post("/api/{action}", ctx -> {
                    String action = ctx.pathParam("action");
                    AdminCommandRequest adminCommandRequest = new AdminCommandRequest();
                    adminCommandRequest.setCommand(action);
                    adminCommandRequest.setOutput(out -> ctx.json(out));
                    adminCommandRequest.setErrOutput(out -> ctx.json(new Message("Failure - " + out)));
                    log.info("adminCommandRequest: {}", adminCommandRequest);
                    if (adminCommandRegistry != null) {
                        adminCommandRegistry.processAdminCommandRequest(adminCommandRequest);
                    }
                })

                .start(host, listenPort);
    }

    private void enforceAuth(Context ctx) {
        if (authMode == AuthMode.NONE) {
            return;
        }
        String header = ctx.header("Authorization");
        if (header == null) {
            reject(ctx);
            return;
        }
        if (authMode == AuthMode.BASIC) {
            if (!header.startsWith("Basic ")) {
                reject(ctx);
                return;
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                reject(ctx);
                return;
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                reject(ctx);
                return;
            }
            String u = decoded.substring(0, colon);
            String p = decoded.substring(colon + 1);
            if (!constantTimeEquals(u, resolvedUsername()) || !constantTimeEquals(p, resolvedPassword())) {
                reject(ctx);
            }
            return;
        }
        if (authMode == AuthMode.BEARER) {
            if (!header.startsWith("Bearer ")) {
                reject(ctx);
                return;
            }
            String presented = header.substring("Bearer ".length()).trim();
            if (!constantTimeEquals(presented, resolvedBearerToken())) {
                reject(ctx);
            }
        }
    }

    private void reject(Context ctx) {
        if (authMode == AuthMode.BASIC) {
            ctx.header("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
        } else if (authMode == AuthMode.BEARER) {
            ctx.header("WWW-Authenticate", "Bearer realm=\"" + realm + "\"");
        }
        throw new UnauthorizedResponse("unauthorized", Map.of());
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

    private String resolvedUsername() {
        return resolveEnv(username);
    }

    private String resolvedPassword() {
        return resolveEnv(password);
    }

    private String resolvedBearerToken() {
        return resolveEnv(bearerToken);
    }

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

    @Start
    public void start() {
        log.info("starting Javalin REST service");
    }

    @Override
    public void tearDown() {
        log.info("tear down Javalin REST service");
        if (javalin != null) {
            try {
                javalin.stop();
            } catch (Exception e) {
                log.warn("error stopping Javalin", e);
            }
            javalin = null;
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Message {
        private String message;
    }

    @Data
    public static class AdminCommand {
        private String command;
        private String[] args = new String[0];
    }
}
