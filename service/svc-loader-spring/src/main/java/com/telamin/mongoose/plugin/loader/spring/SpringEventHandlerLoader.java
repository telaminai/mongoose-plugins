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
