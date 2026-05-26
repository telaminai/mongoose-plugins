/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.sink;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runtime sink loader — symmetric with {@link com.telamin.mongoose.plugin.loader.feed.FeedLoader
 * FeedLoader} but for {@link EventSinkConfig}.
 *
 * <p>Sink hot-swap is already null-safe in the Fluxtion runtime:
 * {@code SinkPublisher.publish} silently drops when the consumer is
 * absent. So a removed sink doesn't crash publishers, it just stops
 * delivering — the operator's responsibility to drain before removing
 * if data loss matters.
 */
@Experimental
@Log4j2
public class SinkLoader implements Lifecycle {

    private MongooseServerController serverController;

    @Getter @Setter private String persistentConfigDir;

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        adminCommandRegistry.registerCommand("sinkLoader.compile",              this::compileCmd);
        adminCommandRegistry.registerCommand("sinkLoader.persistAndCompile",    this::persistAndCompileCmd);
        adminCommandRegistry.registerCommand("sinkLoader.listPersisted",        this::listPersistedCmd);
        adminCommandRegistry.registerCommand("sinkLoader.setPersistedEnabled",  this::setPersistedEnabledCmd);
        adminCommandRegistry.registerCommand("sinkLoader.removePersisted",      this::removePersistedCmd);
        adminCommandRegistry.registerCommand("sinkLoader.getPersistedSource",   this::getPersistedSourceCmd);
        adminCommandRegistry.registerCommand("sinkLoader.removeSink",           this::removeSinkCmd);
    }

    @ServiceRegistered
    public void fluxtionServer(MongooseServerController serverController, String name) {
        log.info("MongooseServerController name: '{}'", name);
        this.serverController = serverController;
    }

    @Override
    public void init() { }

    @Override
    public void start() {
        log.info("Start SinkLoader");
        replayPersisted();
    }

    @Override
    public void tearDown() { }

    private void loadSink(String yamlPath, Consumer<String> out, Consumer<String> err) {
        Path filePath = Path.of(yamlPath);
        if (!filePath.toFile().exists()) {
            err.accept("File not found: " + yamlPath);
            return;
        }
        EventSinkConfig<?> cfg;
        try {
            cfg = parseSinkYaml(Files.readString(filePath));
        } catch (Exception e) {
            err.accept("YAML parse failed: " + e.getMessage());
            return;
        }
        if (cfg.getName() == null || cfg.getName().isBlank()) {
            err.accept("EventSinkConfig.name is required");
            return;
        }
        if (cfg.getInstance() == null) {
            err.accept("EventSinkConfig.instance is required");
            return;
        }
        try {
            registerSink(cfg);
            out.accept("registered sink: " + cfg.getName()
                    + (cfg.isAgent() ? " (agent=" + cfg.getAgentName() + ")" : ""));
        } catch (Exception e) {
            err.accept("register failed: " + e.getMessage());
        }
    }

    static EventSinkConfig<?> parseSinkYaml(String yamlText) {
        LoaderOptions opts = new LoaderOptions();
        opts.setTagInspector(tag -> true);
        Constructor ctor = new Constructor(EventSinkConfig.class, opts);
        Yaml yaml = new Yaml(ctor);
        return yaml.loadAs(yamlText, EventSinkConfig.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerSink(EventSinkConfig<?> cfg) {
        if (cfg.isAgent()) {
            if (!(serverController instanceof MongooseServer ms)) {
                throw new IllegalStateException(
                        "agent-hosted sinks require a MongooseServer instance; got: "
                                + (serverController == null ? "null" : serverController.getClass().getName()));
            }
            // EventSinkConfig.toServiceAgent isn't on the public surface
            // — sink agents are configured manually. Use the same plumbing
            // MongooseServerConfig boot path uses.
            com.telamin.mongoose.dutycycle.ServiceAgent agent =
                    new com.telamin.mongoose.dutycycle.ServiceAgent(
                            cfg.getAgentName(),
                            cfg.getIdleStrategy(),
                            cfg.toService(),
                            (org.agrona.concurrent.Agent) cfg.getInstance());
            ms.registerEventSinkWorker(agent, cfg.getValueMapper());
        } else {
            serverController.registerService(cfg.toService());
        }
    }

    // -------- admin commands --------

    private void compileCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing argument — usage: sinkLoader.compile <sinkYamlPath>"); return; }
        loadSink(args.get(1), out, err);
    }

    private Path persistentBaseOrError(Consumer<String> err) {
        try {
            Path base = PersistedConfigStore.resolveBaseDir(persistentConfigDir);
            if (base == null) {
                err.accept("persistentConfigDir not set on sinkLoader — persistence disabled");
            }
            return base;
        } catch (IOException io) {
            err.accept("persistentConfigDir unusable: " + io.getMessage());
            return null;
        }
    }

    private void persistAndCompileCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing argument — usage: sinkLoader.persistAndCompile <sinkYamlPath>"); return; }
        Path base = persistentBaseOrError(err);
        if (base == null) return;

        String yamlPath = args.get(1);
        boolean[] hadError = { false };
        loadSink(yamlPath, out, msg -> { hadError[0] = true; err.accept(msg); });
        if (hadError[0]) {
            out.accept("compile failed — not persisting");
            return;
        }
        try {
            PersistedConfigStore.Entry persisted = PersistedConfigStore.persist(
                    base, "sinks", Path.of(yamlPath), true);
            out.accept("persisted as " + persisted.group + "/" + persisted.file);
        } catch (Exception io) {
            err.accept("persist failed: " + io.getMessage());
        }
    }

    private void listPersistedCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        Path base = persistentBaseOrError(err);
        if (base == null) { out.accept("[]"); return; }
        try {
            out.accept(PersistedConfigStore.toJsonArray(PersistedConfigStore.list(base)));
        } catch (IOException io) {
            err.accept("list failed: " + io.getMessage());
        }
    }

    private void setPersistedEnabledCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 4) { err.accept("Missing arguments — usage: setPersistedEnabled <group> <file> <true|false>"); return; }
        Path base = persistentBaseOrError(err);
        if (base == null) return;
        try {
            PersistedConfigStore.setEnabled(base, args.get(1), args.get(2), Boolean.parseBoolean(args.get(3)));
            out.accept("ok");
        } catch (Exception e) {
            err.accept("setEnabled failed: " + e.getMessage());
        }
    }

    private void removePersistedCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 3) { err.accept("Missing arguments — usage: removePersisted <group> <file>"); return; }
        Path base = persistentBaseOrError(err);
        if (base == null) return;
        try {
            PersistedConfigStore.remove(base, args.get(1), args.get(2));
            out.accept("ok");
        } catch (Exception e) {
            err.accept("remove failed: " + e.getMessage());
        }
    }

    private void getPersistedSourceCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 3) { err.accept("Missing arguments — usage: getPersistedSource <group> <file>"); return; }
        Path base = persistentBaseOrError(err);
        if (base == null) return;
        try {
            out.accept(PersistedConfigStore.readSource(base, args.get(1), args.get(2)));
        } catch (Exception e) {
            err.accept("read failed: " + e.getMessage());
        }
    }

    private void removeSinkCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing argument — usage: sinkLoader.removeSink <sinkName>"); return; }
        String name = args.get(1);
        if (!serverController.registeredServices().containsKey(name)) {
            err.accept("no service registered with name: " + name);
            return;
        }
        serverController.removeService(name);
        out.accept("removed sink: " + name);
    }

    private void replayPersisted() {
        Path base;
        try { base = PersistedConfigStore.resolveBaseDir(persistentConfigDir); }
        catch (IOException io) { log.error("persistentConfigDir unreachable: {}", persistentConfigDir, io); return; }
        if (base == null) return;

        List<PersistedConfigStore.Entry> entries;
        try { entries = PersistedConfigStore.list(base); }
        catch (IOException io) { log.error("listing persisted configs failed", io); return; }

        for (PersistedConfigStore.Entry e : entries) {
            if (!e.enabled) {
                log.info("skipping disabled persistent sink {}/{}", e.group, e.file);
                continue;
            }
            Path src = PersistedConfigStore.resolveAbsoluteSourcePath(base, e.group, e.file);
            log.info("replaying persisted sink: {}/{}", e.group, e.file);
            StringBuilder errSink = new StringBuilder();
            try {
                loadSink(src.toString(),
                        log::info,
                        msg -> { errSink.append(msg).append('\n'); log.error(msg); });
                String captured = errSink.length() == 0 ? null : errSink.toString().trim();
                PersistedConfigStore.updateLastError(base, e.group, e.file, captured);
            } catch (Exception ex) {
                log.error("replay failed for {}/{}", e.group, e.file, ex);
                try { PersistedConfigStore.updateLastError(base, e.group, e.file, ex.getMessage()); }
                catch (IOException ignored) {}
            }
        }
    }
}
