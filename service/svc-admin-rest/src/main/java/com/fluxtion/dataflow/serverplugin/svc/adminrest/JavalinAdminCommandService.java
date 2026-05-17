/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.fluxtion.dataflow.serverplugin.svc.adminrest;


import com.telamin.fluxtion.runtime.annotations.Start;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventFlowService;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import lombok.*;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class JavalinAdminCommandService implements EventFlowService, Lifecycle {

    private Javalin javalin;
    private EventFlowManager eventFlowManager;
    private AdminCommandRegistry adminCommandRegistry;
    @Getter
    @Setter
    private int listenPort = 8080;
    @Getter
    @Setter
    private String staticDir;

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

    @Override
    public void init() {
        log.info("init Javalin REST service listening on port {}", listenPort);
        javalin = Javalin.create(config -> {
                    if (staticDir != null) {
                        config.staticFiles.add(staticDir, Location.EXTERNAL);
                    }
                })
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

                .start(listenPort);
    }

    @Start
    public void start() {
        log.info("starting Javalin REST service");
    }

    @Override
    public void tearDown() {
        log.info("tear down Javalin REST service");
        javalin.stop();
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
