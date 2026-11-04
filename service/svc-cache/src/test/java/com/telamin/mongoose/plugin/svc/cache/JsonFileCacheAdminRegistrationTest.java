/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.cache;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.AdminFunction;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #49: the cache's admin commands must be registered once both the
 * {@link AdminCommandRegistry} ({@code @ServiceRegistered}) and the service name
 * ({@code setEventFlowManager}) are known, whichever order the server delivers them.
 */
class JsonFileCacheAdminRegistrationTest {

    @TempDir
    Path tempDir;

    private static final List<String> EXPECTED = List.of("cache.gymCache.get", "cache.gymCache.keys");

    @Test
    void flowManager_before_registry_does_not_npe_and_registers_on_registry_arrival() {
        RecordingRegistry registry = new RecordingRegistry();
        JsonFileCache cache = new JsonFileCache();

        assertDoesNotThrow(() -> cache.setEventFlowManager(null, "gymCache"));
        assertTrue(registry.commands.isEmpty());

        cache.register(registry, "adminCommandRegistry");
        assertEquals(EXPECTED, registry.commands);
    }

    @Test
    void registry_before_flowManager_registers_once() {
        RecordingRegistry registry = new RecordingRegistry();
        JsonFileCache cache = new JsonFileCache();

        cache.register(registry, "adminCommandRegistry");
        assertTrue(registry.commands.isEmpty(), "no service name yet");

        cache.setEventFlowManager(null, "gymCache");
        assertEquals(EXPECTED, registry.commands);
    }

    @Test
    void yaml_alias_workaround_then_service_registered_does_not_double_register() {
        RecordingRegistry registry = new RecordingRegistry();
        JsonFileCache cache = new JsonFileCache();
        cache.setRegistry(registry);                     // registry: *adminCommands

        cache.setEventFlowManager(null, "gymCache");
        cache.register(registry, "adminCommandRegistry");
        cache.setEventFlowManager(null, "gymCache");

        assertEquals(EXPECTED, registry.commands);
    }

    @Test
    void two_registries_each_register_once_despite_repeated_callbacks() {
        RecordingRegistry first = new RecordingRegistry();
        RecordingRegistry second = new RecordingRegistry();
        JsonFileCache cache = new JsonFileCache();
        cache.setEventFlowManager(null, "gymCache");

        // LifecycleManager.init calls register() once per registered service, alternating registries
        cache.register(first, "adminA");
        cache.register(second, "adminB");
        cache.register(first, "adminA");
        cache.register(second, "adminB");

        assertEquals(EXPECTED, first.commands);
        assertEquals(EXPECTED, second.commands);
    }

    @Test
    void registration_guard_is_excluded_from_equals_and_toString() {
        RecordingRegistry registry = new RecordingRegistry();
        JsonFileCache registered = new JsonFileCache();
        registered.setRegistry(registry);
        registered.setEventFlowManager(null, "gymCache");   // registers the commands
        JsonFileCache unregistered = new JsonFileCache();
        unregistered.setRegistry(registry);
        unregistered.setServiceName("gymCache");

        assertEquals(EXPECTED, registry.commands);
        // Instances differ only by per-instance object identities, which are stripped here.
        assertEquals(unregistered.toString().replaceAll("@[0-9a-f]+", ""),
                registered.toString().replaceAll("@[0-9a-f]+", ""));
        assertFalse(registered.toString().contains("commandsRegisteredWith"));
    }

    @Test
    void no_registry_ever_is_harmless() {
        JsonFileCache cache = new JsonFileCache();
        assertDoesNotThrow(() -> cache.setEventFlowManager(null, "gymCache"));
    }

    @Test
    void server_boots_with_jsonFileCache_and_no_registry_property() {
        AdminCommandProcessor admin = new AdminCommandProcessor();
        JsonFileCache cache = new JsonFileCache();
        cache.setFileName(tempDir.resolve("data").resolve("gym-cache.json").toString());

        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .addService(ServiceConfig.<AdminCommandRegistry>builder()
                        .service(admin)
                        .serviceClass(AdminCommandRegistry.class)
                        .name("adminCommandRegistry")
                        .build())
                .addService(ServiceConfig.<Cache>builder()
                        .service(cache)
                        .serviceClass(Cache.class)
                        .name("gymCache")
                        .build())
                .build();

        MongooseServer server = assertDoesNotThrow(() -> MongooseServer.bootServer(cfg));
        try {
            List<String> commands = admin.commandList();
            assertTrue(commands.containsAll(EXPECTED), "admin commands registered: " + commands);
        } finally {
            server.stop();
        }
    }

    private static final class RecordingRegistry implements AdminCommandRegistry {
        final List<String> commands = new ArrayList<>();

        @Override
        public <OUT, ERR> void registerCommand(String name, AdminFunction<OUT, ERR> command) {
            commands.add(name);
        }

        @Override
        public void processAdminCommandRequest(AdminCommandRequest command) {
        }

        @Override
        public List<String> commandList() {
            return commands;
        }
    }
}
