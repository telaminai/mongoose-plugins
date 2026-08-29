/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The UP-MNG-01 discovery file: published while the admin web service is up, mode 600, carrying
 * the agent-brokered dev loop's fields, removed on clean shutdown. The external acceptance is the
 * analyser repo's {@code tools/bench/loop-bench.py --registry <dir>}; this test pins the same
 * contract in-tree.
 */
class ServerRegistryFileTest {

    private WebAdminService svc;

    private static int freePort() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void cleanup() {
        if (svc != null) {
            svc.tearDown();
            svc = null;
        }
    }

    @Test
    void publishesRegistryFileWithContractFieldsAndRemovesOnTearDown(@TempDir Path registry) throws Exception {
        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setRegistryDir(registry.toString());
        svc.setServerName("bench-server");
        svc.setEnvironment("dev");
        svc.init();
        svc.start();

        Path file = registry.resolve("bench-server");
        Assertions.assertTrue(Files.exists(file), "registry file published on start");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        Assertions.assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                perms, "registry file is mode 600");

        JsonNode record = new ObjectMapper().readTree(Files.readString(file));
        // the loop bench's required field set
        for (String field : new String[]{"name", "home", "url", "token", "authMode", "pid", "startedAt", "processors"}) {
            Assertions.assertTrue(record.has(field), "field present: " + field);
        }
        Assertions.assertEquals("bench-server", record.get("name").asText());
        Assertions.assertEquals("dev", record.get("environment").asText(), "environment declared (UP-MNG-03)");
        Assertions.assertEquals("NONE", record.get("authMode").asText());
        Assertions.assertEquals(ProcessHandle.current().pid(), record.get("pid").asLong(), "pid is this JVM");
        Assertions.assertEquals("http://127.0.0.1:" + port, record.get("url").asText());
        Assertions.assertTrue(record.get("processors").isArray());

        svc.tearDown();
        svc = null;
        Assertions.assertFalse(Files.exists(file), "clean shutdown removes the registry file");
    }

    /**
     * N1, pinned as BEHAVIOUR rather than as "the hook does not throw": the first publish happens
     * before the port binds and therefore necessarily carries an EMPTY processors list, because
     * processors register later in boot. The lifecycle calls startComplete only once every
     * processor agent is ACTIVE, so refreshing there is what makes the list complete for a headless
     * consumer that reads the entry the moment it appears.
     */
    @Test
    void startCompleteRefreshesTheEntrySoProcessorsAreNotPermanentlyEmpty(@TempDir Path registry) throws Exception {
        int port = freePort();
        // a controller that already knows a processor — as the real one does by startComplete
        WebAdminServiceTest.FakeServerController controller = new WebAdminServiceTest.FakeServerController();
        controller.addProcessor("main",
                new com.telamin.mongoose.dutycycle.NamedEventProcessor("marketProcessor", null));

        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setRegistryDir(registry.toString());
        svc.setServerName("refresh-me");
        svc.init();
        svc.start();

        Path file = registry.resolve("refresh-me");
        ObjectMapper mapper = new ObjectMapper();
        // BEFORE: start() publishes early — deliberately, so a reader that finds the file can trust
        // the URL answers — and at that point the controller was not yet bound, so the list is empty
        Assertions.assertEquals(0, mapper.readTree(Files.readString(file)).get("processors").size(),
                "the first publish precedes processor registration");

        svc.serverController(controller, "test");
        svc.startComplete();

        // AFTER: the exact processor, its group and its GraphML route are in the entry
        JsonNode processors = mapper.readTree(Files.readString(file)).get("processors");
        Assertions.assertEquals(1, processors.size(), "startComplete refreshed the entry");
        Assertions.assertEquals("marketProcessor", processors.get(0).get("name").asText());
        Assertions.assertEquals("main", processors.get(0).get("group").asText());
        Assertions.assertEquals("/api/processors/main/marketProcessor/graphml",
                processors.get(0).get("graphml").asText(),
                "a consumer can fetch the GraphML straight from the entry — the N1 failure");
    }

    @Test
    void serverNameDefaultsToWorkingDirectoryBasename(@TempDir Path registry) {
        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setRegistryDir(registry.toString());
        svc.init();
        svc.start();

        String expected = Path.of(System.getProperty("user.dir")).toAbsolutePath().getFileName().toString();
        Assertions.assertTrue(Files.exists(registry.resolve(expected)),
                "default registry name is the working-dir basename: " + expected);
    }

    /** Mongoose's clean shutdown calls Service.stop() → Lifecycle.stop(), NEVER tearDown() —
     *  verified against a live server. The file must come off on stop(), or every cleanly
     *  stopped server leaves a stale registry entry. */
    @Test
    void stopRemovesTheRegistryFile(@TempDir Path registry) {
        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setRegistryDir(registry.toString());
        svc.setServerName("stop-path");
        svc.init();
        svc.start();
        Assertions.assertTrue(Files.exists(registry.resolve("stop-path")));

        svc.stop();
        Assertions.assertFalse(Files.exists(registry.resolve("stop-path")),
                "Service.stop() — the server's clean-shutdown hook — removes the file");
    }

    @Test
    void publishRegistryFalseWritesNothing(@TempDir Path registry) {
        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setRegistryDir(registry.toString());
        svc.setPublishRegistry(false);
        svc.init();
        svc.start();

        Assertions.assertEquals(0, registry.toFile().list().length, "no registry file when disabled");
    }
}
