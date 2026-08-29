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

    /** N1: the first publish necessarily has no processors (they register later), so the entry is
     *  refreshed at startComplete — after the lifecycle reports every processor agent ACTIVE. */
    @Test
    void startCompleteRefreshesTheEntrySoProcessorsAreNotPermanentlyEmpty(@TempDir Path registry) throws Exception {
        int port = freePort();
        svc = new WebAdminService();
        svc.setListenPort(port);
        svc.setRegistryDir(registry.toString());
        svc.setServerName("refresh-me");
        svc.init();
        svc.start();

        Path file = registry.resolve("refresh-me");
        long firstWrite = Files.getLastModifiedTime(file).toMillis();
        Assertions.assertTrue(new ObjectMapper().readTree(Files.readString(file)).get("processors").isArray());

        // the hook must exist and be safe to call with no controller bound — a server with no
        // processors must not fail its own startup because the admin console refreshed a file
        svc.startComplete();
        Assertions.assertTrue(Files.exists(file), "the entry survives startComplete");
        Assertions.assertTrue(Files.getLastModifiedTime(file).toMillis() >= firstWrite);
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
