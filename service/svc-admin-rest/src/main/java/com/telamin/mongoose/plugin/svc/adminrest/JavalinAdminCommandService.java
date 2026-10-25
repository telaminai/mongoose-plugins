/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.adminrest;


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

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.ContentType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
                    final AdminCommandRequest adminCommandRequest;
                    try {
                        adminCommandRequest = parseRequest(ctx.body());
                    } catch (BadRequest e) {
                        ctx.status(HttpStatus.BAD_REQUEST).json(new Message(e.getMessage()));
                        return;
                    }
                    log.info("adminCommandRequest: {}", adminCommandRequest);
                    ReplyCollector replies = run(ctx, adminCommandRequest);
                    if (replies != null) {
                        // ctx.json escapes newlines, quotes and control characters: the reply is always valid JSON
                        ctx.status(HttpStatus.OK).json(new Message(replies.joined()));
                    }
                })
                .post("/api/{action}", ctx -> {
                    AdminCommandRequest adminCommandRequest = new AdminCommandRequest();
                    adminCommandRequest.setCommand(ctx.pathParam("action"));
                    log.info("adminCommandRequest: {}", adminCommandRequest);
                    ReplyCollector replies = run(ctx, adminCommandRequest);
                    if (replies == null) {
                        return;
                    }
                    ctx.status(HttpStatus.OK);
                    Object single = replies.singleOutput();
                    if (single instanceof String text) {
                        // the command's text as-is; text/plain, not a JSON content type over a non-JSON body
                        ctx.contentType(ContentType.TEXT_PLAIN).result(text);
                    } else if (single != null) {
                        ctx.json(single);
                    } else {
                        ctx.json(new Message(replies.joined()));
                    }
                })

                .start(host, listenPort);
    }

    /**
     * Runs the command and returns every reply it made during the call, or answers the request itself and returns
     * null: 503 when no admin registry is wired (rather than an empty 200), 404 for a command the registry does not
     * list. Replies arriving after this returns (e.g. a registry completion timeout reporting "cancelled" or
     * "abandoned") are logged and dropped: the HTTP exchange is already answered.
     */
    private ReplyCollector run(Context ctx, AdminCommandRequest adminCommandRequest) {
        if (adminCommandRegistry == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(new Message("admin command registry not available"));
            return null;
        }
        String name = adminCommandRequest.getCommand().trim();
        if (!isKnownCommand(name)) {
            ctx.status(HttpStatus.NOT_FOUND).json(new Message(
                    "unknown command '" + name + "'; run the 'commands' command for the registered commands"));
            return null;
        }
        ReplyCollector replies = new ReplyCollector(name);
        adminCommandRequest.setOutput(replies::output);
        adminCommandRequest.setErrOutput(replies::error);
        try {
            adminCommandRegistry.processAdminCommandRequest(adminCommandRequest);
        } finally {
            replies.close();
        }
        return replies;
    }

    private boolean isKnownCommand(String name) {
        List<String> commands;
        try {
            commands = adminCommandRegistry.commandList();
        } catch (RuntimeException e) {
            log.warn("admin registry cannot list its commands; running '{}' unchecked", name, e);
            return true;
        }
        return commands == null || commands.contains(name);
    }

    /**
     * Reads {@code {"command":"<name>","arguments":[...]}}. Built by hand rather than bound onto
     * {@link AdminCommandRequest}, so an error names the bad field or position in the caller's terms (not Jackson's
     * internal type and field names), and a missing or null {@code arguments} is simply an empty list.
     */
    static AdminCommandRequest parseRequest(String body) throws BadRequest {
        if (body == null || body.isBlank()) {
            throw new BadRequest("empty request body; " + EXPECTED_SHAPE);
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            throw new BadRequest("request body is not valid JSON"
                    + (at == null ? "" : " (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")")
                    + "; " + EXPECTED_SHAPE);
        }
        if (root == null || !root.isObject()) {
            throw new BadRequest("request body must be a JSON object; " + EXPECTED_SHAPE);
        }
        if (root.has("args")) {
            throw new BadRequest("unexpected field 'args'; the argument list field is 'arguments', not 'args'; "
                    + EXPECTED_SHAPE);
        }
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!field.equals("command") && !field.equals("arguments")) {
                throw new BadRequest("unexpected field '" + field + "'; " + EXPECTED_SHAPE);
            }
        }
        JsonNode command = root.get("command");
        if (command == null || command.isNull() || (command.isTextual() && command.asText().isBlank())) {
            throw new BadRequest("missing 'command'; " + EXPECTED_SHAPE);
        }
        if (!command.isTextual()) {
            throw new BadRequest("'command' must be a string; " + EXPECTED_SHAPE);
        }
        List<String> arguments = new ArrayList<>();
        JsonNode args = root.get("arguments");
        if (args != null && !args.isNull()) {
            if (!args.isArray()) {
                throw new BadRequest("'arguments' must be an array of strings; " + EXPECTED_SHAPE);
            }
            for (int i = 0; i < args.size(); i++) {
                JsonNode arg = args.get(i);
                if (!arg.isValueNode() || arg.isNull()) {
                    throw new BadRequest("'arguments[" + i + "]' must be a string; " + EXPECTED_SHAPE);
                }
                arguments.add(arg.asText());
            }
        }
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(command.asText());
        request.setArguments(arguments);
        return request;
    }

    private static final String EXPECTED_SHAPE = "expected {\"command\":\"<name>\",\"arguments\":[...]}";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A 400 whose message is safe to show the caller. */
    static final class BadRequest extends Exception {
        BadRequest(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Collects every output and error reply a command makes while the registry call runs, so the request is
     * answered once, after the call returns. Error replies are marked "Failure - ". Once closed, late replies are
     * logged and dropped.
     */
    static final class ReplyCollector {
        private final String command;
        private final List<Object> outputs = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();
        private boolean errors;
        private boolean closed;

        ReplyCollector(String command) {
            this.command = command;
        }

        synchronized void output(Object out) {
            if (late("output", out)) return;
            outputs.add(out);
            lines.add(String.valueOf(out));
        }

        synchronized void error(Object err) {
            if (late("error", err)) return;
            errors = true;
            lines.add("Failure - " + err);
        }

        private boolean late(String kind, Object value) {
            if (closed) {
                log.warn("dropping {} for admin command '{}' that arrived after the reply was sent: {}",
                        kind, command, value);
            }
            return closed;
        }

        synchronized void close() {
            closed = true;
        }

        /** every reply in order, one per line; empty when the command replied nothing */
        synchronized String joined() {
            return String.join("\n", lines);
        }

        /** the output when the command replied exactly once and without an error, otherwise null */
        synchronized Object singleOutput() {
            return !errors && outputs.size() == 1 ? outputs.get(0) : null;
        }
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

    /**
     * @deprecated the wire contract is {@link AdminCommandRequest}, whose argument list field is {@code arguments}.
     * This type's {@code args} field is a common source of confusion and is not used by the endpoint; kept only for
     * source compatibility.
     */
    @Deprecated
    @Data
    public static class AdminCommand {
        private String command;
        private String[] args = new String[0];
    }
}
