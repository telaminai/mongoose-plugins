/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import io.javalin.websocket.WsContext;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WebAdminService#broadcastLogLine} itself, not the helper it delegates to.
 *
 * <p>Review found the gap that extracting {@code fanOutLogLine} left: the test called the helper
 * directly, so reverting the CALL SITE to a silent drop — subscriber removed, session left open — kept
 * the whole suite green. That is the exact defect this branch exists for, on a different socket: a
 * client that stays connected, looks healthy and receives nothing for ever.
 *
 * <p>There is no mocking framework here, and {@code WsContext.send} is final, so the seam is Jetty's
 * {@link Session} — an interface, so a {@link Proxy} can be a session that fails every send and records
 * its own close. {@code WsContext} is abstract with no abstract members, so it subclasses directly.
 */
class LogFanOutCallSiteTest {

    /** A session that is open, throws on every write, and remembers being closed. */
    private static Session failingSession(AtomicBoolean closed) {
        return (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(),
                new Class<?>[]{Session.class},
                (proxy, Method, args) -> {
                    switch (Method.getName()) {
                        case "isOpen":
                            return Boolean.TRUE;
                        case "close":
                        case "disconnect":
                            closed.set(true);
                            return null;
                        case "toString":
                            return "failingSession";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            // every send path fails, which is the condition the fan-out must act on
                            throw new IllegalStateException("client gone");
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Set<WsContext> logClients(WebAdminService svc) throws Exception {
        Field f = WebAdminService.class.getDeclaredField("logClients");
        f.setAccessible(true);
        return (Set<WsContext>) f.get(svc);
    }

    private static void broadcast(WebAdminService svc, Object line) throws Exception {
        Method m = WebAdminService.class.getDeclaredMethod("broadcastLogLine", LogTail.LogLine.class);
        m.setAccessible(true);
        m.invoke(svc, line);
    }

    @Test
    void aSubscriberWhoseSendFailsIsDroppedAndItsSessionClosed() throws Exception {
        WebAdminService svc = new WebAdminService();
        AtomicBoolean closed = new AtomicBoolean();
        WsContext doomed = new WsContext("doomed", failingSession(closed)) {
        };

        Set<WsContext> clients = logClients(svc);
        clients.add(doomed);
        assertEquals(1, clients.size());

        broadcast(svc, new LogTail.LogLine(System.currentTimeMillis(), "INFO", "logger", "hello"));

        assertTrue(clients.isEmpty(), "the failing subscriber must be dropped from the fan-out");
        assertTrue(closed.get(),
                "and its session CLOSED — dropping it silently leaves a client connected, healthy and "
                        + "receiving nothing for ever, which is the defect this branch is about");
    }
}
