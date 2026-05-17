/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.adminweb;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.plugin.svc.adminweb.WebAdminService;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.servercontrol.MongooseServerAdmin;

/**
 * Boots a Mongoose server with {@link WebAdminService} registered, leaving
 * the JVM running so a browser can hit the UI.
 *
 * <p>Status during svc-admin-web development (M1+):
 * <ul>
 *   <li>M1 — only {@code /healthz} responds; visit
 *       <a href="http://127.0.0.1:8181/healthz">http://127.0.0.1:8181/healthz</a>
 *       and expect a plain {@code OK}.</li>
 *   <li>M2 — auth + session land; the login form appears.</li>
 *   <li>M3 — command runner shows the admin command list.</li>
 *   <li>M4+ — dashboard, metrics, log tail, conditional tabs.</li>
 * </ul>
 *
 * <p>Press Ctrl-C to stop.
 */
public class AdminWebExample {

    public static void main(String[] args) {
        WebAdminService adminWeb = new WebAdminService();
        adminWeb.setHost("127.0.0.1");
        adminWeb.setListenPort(8181);
        // AuthMode.NONE is the default — fine for local dev. The UI in later
        // milestones will display a banner when auth is disabled.

        ServiceConfig<WebAdminService> adminWebSvc = ServiceConfig.<WebAdminService>builder()
                .service(adminWeb)
                .serviceClass(WebAdminService.class)
                .name("adminWebService")
                .build();

        // AdminCommandProcessor is the registry implementation. Without it,
        // commandList() is empty and the UI shows "no commands registered".
        ServiceConfig<AdminCommandRegistry> registrySvc = ServiceConfig.<AdminCommandRegistry>builder()
                .service(new AdminCommandProcessor())
                .serviceClass(AdminCommandRegistry.class)
                .name("adminCommandRegistry")
                .build();

        // MongooseServerAdmin publishes server.service.list, server.processors.list,
        // server.processors.stop — gives the UI something to click out-of-the-box.
        ServiceConfig<?> serverAdminSvc = ServiceConfig.builder()
                .service(new MongooseServerAdmin())
                .name("serverAdmin")
                .build();

        MongooseServerConfig serverConfig = MongooseServerConfig.builder()
                .addService(registrySvc)
                .addService(serverAdminSvc)
                .addService(adminWebSvc)
                .build();

        MongooseServer server = MongooseServer.bootServer(serverConfig);

        System.out.println();
        System.out.println("  svc-admin-web example running");
        System.out.println("  → http://127.0.0.1:8181/healthz");
        System.out.println("  Ctrl-C to stop");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("stopping server...");
            server.stop();
        }, "admin-web-example-shutdown"));

        // Keep the JVM alive. The shutdown hook handles clean termination.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
