/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.feed;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
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
 * Runtime feed loader — adds dynamic event feeds to a running Mongoose
 * server from YAML snippets submitted via the admin interface.
 *
 * <p>Mirrors {@code svc-loader-yaml} / {@code svc-loader-spring} in
 * shape: compile / persist + boot replay / list / remove / view source,
 * with a {@code persistentConfigDir} field that gates the persist
 * commands. Without it set, only one-shot loads are accepted — that's
 * the "operator can experiment but can't permanently install boot-time
 * code via the admin" privilege model.
 *
 * <p>Input format is a single {@link EventFeedConfig} mapping in YAML,
 * e.g.:
 * <pre>{@code
 * name: ticks
 * instance: !!com.example.feed.MyFeed { filename: data/ticks.csv }
 * broadcast: true
 * agentName: feeds-agent
 * idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}
 * }</pre>
 *
 * <p>Agent-hosted feeds (those with {@code agentName} set) require
 * downcasting the {@link MongooseServerController} to {@link MongooseServer}
 * because the controller interface in 1.0.18 doesn't expose
 * {@code registerEventFeedWorker}. The cast is pragmatic — Mongoose
 * ships exactly one controller implementation — and isolated to this
 * loader. If a future Mongoose release adds the agent-variant to the
 * interface, drop the cast in one place.
 */
@Experimental
@Log4j2
public class FeedLoader implements Lifecycle {

    private MongooseServerController serverController;

    /** Filesystem directory backing operator-marked persistent configs.
     *  Opt-in by design — see svc-loader-yaml#persistentConfigDir for
     *  the rationale. */
    @Getter @Setter private String persistentConfigDir;

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        adminCommandRegistry.registerCommand("feedLoader.compile",              this::compileCmd);
        adminCommandRegistry.registerCommand("feedLoader.persistAndCompile",    this::persistAndCompileCmd);
        adminCommandRegistry.registerCommand("feedLoader.listPersisted",        this::listPersistedCmd);
        adminCommandRegistry.registerCommand("feedLoader.setPersistedEnabled",  this::setPersistedEnabledCmd);
        adminCommandRegistry.registerCommand("feedLoader.removePersisted",      this::removePersistedCmd);
        adminCommandRegistry.registerCommand("feedLoader.getPersistedSource",   this::getPersistedSourceCmd);
        adminCommandRegistry.registerCommand("feedLoader.removeFeed",           this::removeFeedCmd);
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
        log.info("Start FeedLoader");
        replayPersisted();
    }

    @Override
    public void tearDown() { }

    // -------- core add path --------

    /** Reads + parses + registers a feed from a YAML file. Used both by
     *  the admin command path and the persisted-replay path. */
    private void loadFeed(String yamlPath, Consumer<String> out, Consumer<String> err) {
        Path filePath = Path.of(yamlPath);
        if (!filePath.toFile().exists()) {
            err.accept("File not found: " + yamlPath);
            return;
        }
        EventFeedConfig<?> cfg;
        try {
            cfg = parseFeedYaml(Files.readString(filePath));
        } catch (Exception e) {
            err.accept("YAML parse failed: " + e.getMessage());
            return;
        }
        if (cfg.getName() == null || cfg.getName().isBlank()) {
            err.accept("EventFeedConfig.name is required");
            return;
        }
        if (cfg.getInstance() == null) {
            err.accept("EventFeedConfig.instance is required");
            return;
        }
        try {
            registerFeed(cfg);
            out.accept("registered feed: " + cfg.getName()
                    + (cfg.isAgent() ? " (agent=" + cfg.getAgentName() + ")" : ""));
        } catch (Exception e) {
            err.accept("register failed: " + e.getMessage());
        }
    }

    /** Parses a YAML mapping into an {@link EventFeedConfig}. Uses the
     *  non-safe Constructor with a permissive TagInspector — the input
     *  is operator-supplied and may reference user-defined classes via
     *  {@code !!FQN}. Same gate as the yaml-loader's graph YAML. */
    static EventFeedConfig<?> parseFeedYaml(String yamlText) {
        LoaderOptions opts = new LoaderOptions();
        opts.setTagInspector(tag -> true);
        Constructor ctor = new Constructor(EventFeedConfig.class, opts);
        Yaml yaml = new Yaml(ctor);
        return yaml.loadAs(yamlText, EventFeedConfig.class);
    }

    /** Calls into the running server. Agent-hosted feeds require a
     *  downcast to MongooseServer; non-agent paths use the controller
     *  interface as exposed in Mongoose 1.0.18. */
    private void registerFeed(EventFeedConfig<?> cfg) {
        if (cfg.isAgent()) {
            if (!(serverController instanceof MongooseServer ms)) {
                throw new IllegalStateException(
                        "agent-hosted feeds require a MongooseServer instance; got: "
                                + (serverController == null ? "null" : serverController.getClass().getName()));
            }
            ms.registerEventFeedWorker(cfg.toServiceAgent(), cfg.getValueMapper());
        } else {
            // Non-agent feeds: instance is typically an EventSource, but
            // could be anything that wraps to a Service<NamedFeed>. Use
            // the controller path so we don't unconditionally require a
            // MongooseServer cast.
            serverController.registerService(cfg.toService());
        }
    }

    // -------- admin commands --------

    private void compileCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing argument — usage: feedLoader.compile <feedYamlPath>"); return; }
        loadFeed(args.get(1), out, err);
    }

    private Path persistentBaseOrError(Consumer<String> err) {
        try {
            Path base = PersistedConfigStore.resolveBaseDir(persistentConfigDir);
            if (base == null) {
                err.accept("persistentConfigDir not set on feedLoader — persistence disabled");
            }
            return base;
        } catch (IOException io) {
            err.accept("persistentConfigDir unusable: " + io.getMessage());
            return null;
        }
    }

    private void persistAndCompileCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing argument — usage: feedLoader.persistAndCompile <feedYamlPath>"); return; }
        Path base = persistentBaseOrError(err);
        if (base == null) return;

        String yamlPath = args.get(1);
        // Compile first; only persist on success — same contract as
        // svc-loader-yaml. The group dimension isn't used for feeds
        // (feed names are server-global), so we use a single group
        // "feeds" as a logical container in the persistent dir.
        boolean[] hadError = { false };
        loadFeed(yamlPath, out, msg -> { hadError[0] = true; err.accept(msg); });
        if (hadError[0]) {
            out.accept("compile failed — not persisting");
            return;
        }
        try {
            PersistedConfigStore.Entry persisted = PersistedConfigStore.persist(
                    base, "feeds", Path.of(yamlPath), true);
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

    /** Unregisters a live feed by name. Uses the 1.0.18 removeService
     *  broadcast so SubscriptionManagerNode unbinds on every running
     *  processor. The feed's own teardown (file handles, kafka
     *  consumers, …) is the feed's responsibility — invoked here via
     *  service.stop() inside MongooseServer.removeService. */
    private void removeFeedCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing argument — usage: feedLoader.removeFeed <feedName>"); return; }
        String name = args.get(1);
        if (!serverController.registeredServices().containsKey(name)) {
            err.accept("no service registered with name: " + name);
            return;
        }
        serverController.removeService(name);
        out.accept("removed feed: " + name);
    }

    // -------- boot replay --------

    /** Walks the persistent dir on boot, replaying every enabled entry.
     *  Per-entry try/catch so one bad config doesn't cascade — error
     *  captured in the entry's {@code lastError} for admin-UI surfacing. */
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
                log.info("skipping disabled persistent feed {}/{}", e.group, e.file);
                continue;
            }
            Path src = PersistedConfigStore.resolveAbsoluteSourcePath(base, e.group, e.file);
            log.info("replaying persisted feed: {}/{}", e.group, e.file);
            StringBuilder errSink = new StringBuilder();
            try {
                loadFeed(src.toString(),
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
