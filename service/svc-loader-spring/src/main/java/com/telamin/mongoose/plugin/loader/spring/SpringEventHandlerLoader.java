/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.loader.spring;

import org.agrona.concurrent.YieldingIdleStrategy;
import com.telamin.fluxtion.builder.compile.config.FluxtionCompilerConfig;
import com.telamin.fluxtion.builder.extern.spring.FluxtionSpring;
import com.telamin.fluxtion.builder.extern.spring.FluxtionSpringInterpreter;
import com.telamin.fluxtion.runtime.CloneableDataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Preview;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.partition.LambdaReflection;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Preview
@Log4j2
public class SpringEventHandlerLoader implements Lifecycle {

    private MongooseServerController serverController;
    private AdminCommandRegistry adminCommandRegistry;
    private boolean addEventAuditor = true;
    private EventLogControlEvent.LogLevel traceLogLevel;
    private EventLogControlEvent.LogLevel initialLogLevel = EventLogControlEvent.LogLevel.INFO;
    private static final String DEFAULT_GROUP = "springBeanLoader";
    @Getter
    @Setter
    private Set<EventSpringFile> loadAtStartup = new HashSet<>();

    /** Filesystem directory to emit generated .java sources under.
     *  When null/blank, source is kept in-memory. When set, generated
     *  classes land at
     *  {@code <generatedSourceDir>/<packageName-path>/<className>.java}
     *  so the admin web's source-viewer (sourceRoots) can find them. */
    @Getter @Setter private String generatedSourceDir;
    /** Filesystem directory to emit .graphml + auxiliary resources. */
    @Getter @Setter private String generatedResourcesDir;
    /** Java package for runtime-generated processor classes. */
    @Getter @Setter private String packageName = "com.telamin.mongoose.runtime.loaded.spring";

    /** Filesystem directory backing operator-marked persistent configs.
     *  Opt-in by design — see EventHandlerLoader#persistentConfigDir. */
    @Getter @Setter private String persistentConfigDir;

    public void init() {
    }

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        this.adminCommandRegistry = adminCommandRegistry;
        adminCommandRegistry.registerCommand("springLoader.compileProcessor", this::compileProcessor);
        adminCommandRegistry.registerCommand("springLoader.interpretProcessor", this::interpretProcessor);
        adminCommandRegistry.registerCommand("springLoader.reloadInterpretProcessor", this::compileReloadProcessor);
        adminCommandRegistry.registerCommand("springLoader.reloadCompileProcessor", this::interpretReloadProcessor);
        adminCommandRegistry.registerCommand("springLoader.listLoaded", this::listProcessors);
        // Persistence surface — mirrors svc-loader-yaml. Guards
        // against persistentConfigDir being unset.
        adminCommandRegistry.registerCommand("springLoader.persistAndCompile",   this::persistAndCompileCmd);
        adminCommandRegistry.registerCommand("springLoader.persistAndInterpret", this::persistAndInterpretCmd);
        adminCommandRegistry.registerCommand("springLoader.listPersisted",       this::listPersistedCmd);
        adminCommandRegistry.registerCommand("springLoader.setPersistedEnabled", this::setPersistedEnabledCmd);
        adminCommandRegistry.registerCommand("springLoader.removePersisted",     this::removePersistedCmd);
        adminCommandRegistry.registerCommand("springLoader.getPersistedSource",  this::getPersistedSourceCmd);
    }

    @ServiceRegistered
    public void fluxtionServer(MongooseServerController serverController, String name) {
        log.info("MongooseServerController name: '{}'", name);
        this.serverController = serverController;
    }

    public void start() {
        log.info("Start Spring EventHandler loader startUpConfig:{}", loadAtStartup);
        loadAtStartup.forEach(cfg -> {
            addEventAuditor = cfg.isAddEventAuditor();
            traceLogLevel = cfg.getTraceLogLevel();
            initialLogLevel = cfg.getInitialLogLevel();
            loadProcessor(cfg.isCompile(),
                    List.of("loadProcessor", cfg.getSpringFile(), cfg.getGroup()),
                    log::info,
                    log::error);
        });
        addEventAuditor = true;
        traceLogLevel = null;
        initialLogLevel = EventLogControlEvent.LogLevel.INFO;

        replayPersisted();
    }

    private void replayPersisted() {
        java.nio.file.Path base;
        try { base = PersistedConfigStore.resolveBaseDir(persistentConfigDir); }
        catch (java.io.IOException io) { log.error("persistentConfigDir unreachable: {}", persistentConfigDir, io); return; }
        if (base == null) return;

        java.util.List<PersistedConfigStore.Entry> entries;
        try { entries = PersistedConfigStore.list(base); }
        catch (java.io.IOException io) { log.error("listing persisted configs failed", io); return; }

        for (PersistedConfigStore.Entry e : entries) {
            if (!e.enabled) {
                log.info("skipping disabled persistent config {}/{}", e.group, e.file);
                continue;
            }
            java.nio.file.Path src = PersistedConfigStore.resolveAbsoluteSourcePath(base, e.group, e.file);
            log.info("replaying persisted spring: {}/{}", e.group, e.file);
            StringBuilder errSink = new StringBuilder();
            try {
                loadProcessor(e.compile,
                        List.of("loadProcessor", src.toString(), e.group),
                        log::info,
                        msg -> { errSink.append(msg).append('\n'); log.error(msg); });
                String captured = errSink.length() == 0 ? null : errSink.toString().trim();
                PersistedConfigStore.updateLastError(base, e.group, e.file, captured);
            } catch (Exception ex) {
                log.error("replay failed for {}/{}", e.group, e.file, ex);
                try { PersistedConfigStore.updateLastError(base, e.group, e.file, ex.getMessage()); }
                catch (java.io.IOException ignored) {}
            }
        }
    }

    public void tearDown() {

    }

    private void interpretProcessor(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadProcessor(false, args, out, err);
    }

    private void compileProcessor(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadProcessor(true, args, out, err);
    }

    private void interpretReloadProcessor(List<String> args, Consumer<String> out, Consumer<String> err) {
        reloadProcessor(false, args, out, err);
    }

    private void compileReloadProcessor(List<String> args, Consumer<String> out, Consumer<String> err) {
        reloadProcessor(true, args, out, err);
    }

    private void listProcessors(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadAtStartup.forEach(config -> {
            out.accept(config.getGroup() + "/" + config.springFile + "\n");
        });
    }


    private void reloadProcessor(boolean compileProcessor, List<String> args, Consumer<String> out, Consumer<String> err) {
        log.info("reloadProcessor");
        if (args.size() < 2) {
            err.accept("Missing arguments provide spring bean file location");
            return;
        }

        String springFile = args.get(1);
        out.accept("stopping processor config from file:" + springFile);
        String[] splitArgs = springFile.split("/");
        serverController.stopProcessor(splitArgs[0], splitArgs[1]);

        loadProcessor(compileProcessor, List.of("loadProcessor", splitArgs[1], splitArgs[0]), out, err);

    }

    private void loadProcessor(boolean compileProcessor, List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) {
            err.accept("Missing arguments provide spring bean file location");
            return;
        }

        String group = args.size() > 2 ? args.get(2) : DEFAULT_GROUP;

        String springFile = args.get(1);
        out.accept("loading config from file:" + springFile);

        Path springFilePath = Path.of(springFile);
        if (!springFilePath.toFile().exists()) {
            err.accept("File not found: " + springFile);
            return;
        }

        CloneableDataFlow<?> eventProcessor;
        if (compileProcessor) {
            Consumer<com.telamin.fluxtion.builder.generation.config.EventProcessorConfig> nodeCfg = cfg -> {
                if (addEventAuditor) {
                    cfg.addEventAudit();
                    cfg.addEventAudit(traceLogLevel);
                }
            };
            // Always take the compileAot overload so our package +
            // className override applies — without it, Fluxtion's
            // default FQN contains `$` (lambda-derived) which the
            // admin web's source endpoint mistakes for inner-class
            // syntax, breaking event-source navigation.
            eventProcessor = FluxtionSpring.compileAot(
                    springFilePath, nodeCfg, compilerConfigFor(springFile, group));
        } else {
            eventProcessor = FluxtionSpringInterpreter.interpret(springFilePath, cfg -> {
                if (addEventAuditor) {
                    cfg.addEventAudit();
                    cfg.addEventAudit(traceLogLevel);
                }
            });
        }

        eventProcessor.init();
        eventProcessor.setAuditLogLevel(initialLogLevel);

        try {
            serverController.addEventProcessor(springFile, group, new YieldingIdleStrategy(), () -> eventProcessor);
            out.accept("compiled and loaded processor" + eventProcessor.toString());
        } catch (IllegalArgumentException e) {
            err.accept("Failed to add event processor: " + e.getMessage());
        }

    }

    /** Compiler-config consumer applied to every AOT compile. ALWAYS
     *  overrides package + className so the generated FQN is
     *  deterministic and free of `$` (Fluxtion's default for
     *  spring-fed compiles is {@code ….fluxtionspring.addNodes.Processor}
     *  but for buildGraph-fed lambdas contains `$`; the admin web's
     *  source endpoint strips on first `$` as inner-class syntax, so
     *  any FQN with `$` breaks event-source navigation). Output dirs
     *  apply only when set. */
    private LambdaReflection.SerializableConsumer<FluxtionCompilerConfig> compilerConfigFor(
            String sourceFile, String group) {
        final String pkg = packageName == null || packageName.isBlank()
                ? "com.telamin.mongoose.runtime.loaded.spring" : packageName;
        final String cls = deriveClassName(sourceFile, group);
        final String srcDir = generatedSourceDir;
        final String resDir = generatedResourcesDir == null ? generatedSourceDir : generatedResourcesDir;
        return cfg -> {
            cfg.setPackageName(pkg);
            cfg.setClassName(cls);
            if (srcDir != null && !srcDir.isBlank()) {
                cfg.setOutputDirectory(srcDir);
                cfg.setWriteSourceToFile(true);
            }
            if (resDir != null && !resDir.isBlank()) {
                cfg.setResourcesOutputDirectory(resDir);
                cfg.setGenerateDescription(true);
            }
        };
    }

    /** Maps an arbitrary config-file path + group into a legal Java
     *  class name, suffixing {@code _Processor}. Exposed for unit tests. */
    static String deriveClassName(String sourceFile, String group) {
        String base = sourceFile == null ? "loaded" : sourceFile;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String groupPart = group == null || group.isBlank() ? "" : "_" + group;
        String raw = base + groupPart + "_Processor";
        String sanitised = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitised.isEmpty() || !Character.isJavaIdentifierStart(sanitised.charAt(0))) {
            sanitised = "P_" + sanitised;
        }
        return sanitised;
    }

    // ---- persistence admin commands ----

    private java.nio.file.Path persistentBaseOrError(Consumer<String> err) {
        try {
            java.nio.file.Path base = PersistedConfigStore.resolveBaseDir(persistentConfigDir);
            if (base == null) {
                err.accept("persistentConfigDir not set on springEventHandlerLoader — persistence disabled");
            }
            return base;
        } catch (java.io.IOException io) {
            err.accept("persistentConfigDir unusable: " + io.getMessage());
            return null;
        }
    }

    private void persistAndCompileCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        persistAndLoad(true, args, out, err);
    }

    private void persistAndInterpretCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        persistAndLoad(false, args, out, err);
    }

    /** Compile/interpret first via the existing loader, persist only
     *  on success — same shape as svc-loader-yaml. */
    private void persistAndLoad(boolean compileProcessor, List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing arguments — usage: persistAndCompile <springPath> [group]"); return; }
        java.nio.file.Path base = persistentBaseOrError(err);
        if (base == null) return;

        String springPath = args.get(1);
        String group = args.size() > 2 ? args.get(2) : DEFAULT_GROUP;
        boolean[] hadError = { false };
        loadProcessor(compileProcessor,
                List.of("loadProcessor", springPath, group),
                out,
                msg -> { hadError[0] = true; err.accept(msg); });
        if (hadError[0]) {
            out.accept("compile failed — not persisting");
            return;
        }
        try {
            PersistedConfigStore.Entry persisted =
                    PersistedConfigStore.persist(base, group, java.nio.file.Path.of(springPath), compileProcessor);
            out.accept("persisted as " + persisted.group + "/" + persisted.file);
        } catch (Exception io) {
            err.accept("persist failed: " + io.getMessage());
        }
    }

    private void listPersistedCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        java.nio.file.Path base = persistentBaseOrError(err);
        if (base == null) { out.accept("[]"); return; }
        try {
            out.accept(PersistedConfigStore.toJsonArray(PersistedConfigStore.list(base)));
        } catch (java.io.IOException io) {
            err.accept("list failed: " + io.getMessage());
        }
    }

    private void setPersistedEnabledCmd(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 4) { err.accept("Missing arguments — usage: setPersistedEnabled <group> <file> <true|false>"); return; }
        java.nio.file.Path base = persistentBaseOrError(err);
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
        java.nio.file.Path base = persistentBaseOrError(err);
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
        java.nio.file.Path base = persistentBaseOrError(err);
        if (base == null) return;
        try {
            out.accept(PersistedConfigStore.readSource(base, args.get(1), args.get(2)));
        } catch (Exception e) {
            err.accept("read failed: " + e.getMessage());
        }
    }

    @Data
    public static final class EventSpringFile {
        private String springFile;
        private String group = DEFAULT_GROUP;
        private boolean addEventAuditor = true;
        private boolean compile = true;
        private EventLogControlEvent.LogLevel traceLogLevel;
        private EventLogControlEvent.LogLevel initialLogLevel = EventLogControlEvent.LogLevel.INFO;
    }
}
