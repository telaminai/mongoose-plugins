/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.loader.yaml;

import org.agrona.concurrent.YieldingIdleStrategy;
import com.telamin.fluxtion.Fluxtion;
import com.telamin.fluxtion.FluxtionInterpreter;
import com.telamin.fluxtion.builder.generation.classcompiler.StringCompilation;
import com.telamin.fluxtion.builder.generation.config.EventProcessorConfig;
import com.telamin.fluxtion.builder.compile.config.FluxtionCompilerConfig;
import com.telamin.fluxtion.builder.compile.config.FluxtionGraphBuilder;
import com.telamin.fluxtion.runtime.CloneableDataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.audit.Auditor;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.partition.LambdaReflection;
import com.telamin.fluxtion.runtime.partition.LambdaReflection.SerializableConsumer;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

@Experimental
@Log4j2
public class EventHandlerLoader implements Lifecycle {

    private MongooseServerController serverController;
    private static final String DEFAULT_GROUP_JAVA_SRC = "javaSourceLoader";
    private static final String DEFAULT_GROUP_YAML = "yamlLoader";
    @Getter
    @Setter
    private Set<EventLoadAtStartup> loadAtStartup = new HashSet<>();
    private EventLogControlEvent.LogLevel initialLogLevel = EventLogControlEvent.LogLevel.INFO;
    private EventLogControlEvent.LogLevel traceLogLevel;
    private boolean addEventAuditor = false;

    /** Filesystem directory to emit generated .java sources under.
     *  When null/blank, source is kept in-memory (legacy behaviour).
     *  When set, generated classes land at
     *  {@code <generatedSourceDir>/<packageName-path>/<className>.java}
     *  so the admin web's source-viewer (sourceRoots) can find them. */
    @Getter @Setter private String generatedSourceDir;
    /** Filesystem directory to emit .graphml + auxiliary resources. */
    @Getter @Setter private String generatedResourcesDir;
    /** Java package for runtime-generated processor classes. */
    @Getter @Setter private String packageName = "com.telamin.mongoose.runtime.loaded.yaml";

    /** Filesystem directory backing operator-marked persistent configs.
     *  When null/blank, the persist commands return an error (opt-in
     *  by design — silent persistence of admin-uploaded code would be
     *  a quiet privilege escalation). When set, configs marked
     *  persistent land at {@code <persistentConfigDir>/<group>/<file>}
     *  and are replayed on next boot. */
    @Getter @Setter private String persistentConfigDir;

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        adminCommandRegistry.registerCommand("javaLoader.compileProcessor", this::compileProcessor);
        adminCommandRegistry.registerCommand("javaLoader.interpretProcessor", this::interpretProcessor);
        adminCommandRegistry.registerCommand("yamlLoader.compileProcessor", this::compileProcessorYaml);
        adminCommandRegistry.registerCommand("yamlLoader.interpretProcessor", this::interpretProcessorYaml);
        // Persistence surface — all guard against persistentConfigDir
        // being unset, returning an error so the admin UI can show
        // "persistence not enabled on this server".
        adminCommandRegistry.registerCommand("yamlLoader.persistAndCompile",   this::persistAndCompileCmd);
        adminCommandRegistry.registerCommand("yamlLoader.persistAndInterpret", this::persistAndInterpretCmd);
        adminCommandRegistry.registerCommand("yamlLoader.listPersisted",       this::listPersistedCmd);
        adminCommandRegistry.registerCommand("yamlLoader.setPersistedEnabled", this::setPersistedEnabledCmd);
        adminCommandRegistry.registerCommand("yamlLoader.removePersisted",     this::removePersistedCmd);
        adminCommandRegistry.registerCommand("yamlLoader.getPersistedSource",  this::getPersistedSourceCmd);
    }

    @ServiceRegistered
    public void fluxtionServer(MongooseServerController serverController, String name) {
        log.info("MongooseServerController name: '{}'", name);
        this.serverController = serverController;
    }

    @Override
    public void init() {
    }

    @Override
    public void start() {
        log.info("Start yaml EventHandler loader startUpConfig:{}", loadAtStartup);
        loadAtStartup.forEach(cfg -> {
            initialLogLevel = cfg.getInitialLogLevel();
            traceLogLevel = cfg.getTraceLogLevel();
            addEventAuditor = cfg.isAddEventAuditor();
            loadProcessorYaml(cfg.isCompile(),
                    List.of("loadProcessor", cfg.getYamlFile(), cfg.getGroup()),
                    log::info,
                    log::error);
        });
        initialLogLevel = EventLogControlEvent.LogLevel.INFO;
        traceLogLevel = null;
        addEventAuditor = false;

        replayPersisted();
    }

    /** Iterates the persistent dir on boot, replaying every enabled
     *  entry. Per-entry try/catch ensures one bad config doesn't
     *  cascade — error captured in the entry's {@code lastError} so
     *  the admin UI can surface it. */
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
                log.info("skipping disabled persistent config {}/{}", e.group, e.file);
                continue;
            }
            Path src = PersistedConfigStore.resolveAbsoluteSourcePath(base, e.group, e.file);
            log.info("replaying persisted yaml: {}/{}", e.group, e.file);
            StringBuilder errSink = new StringBuilder();
            try {
                loadProcessorYaml(e.compile,
                        List.of("loadProcessor", src.toString(), e.group),
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

    @Override
    public void tearDown() {
    }

    private void interpretProcessor(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadProcessor(false, args, out, err);
    }

    private void compileProcessor(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadProcessor(true, args, out, err);
    }

    private void interpretProcessorYaml(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadProcessorYaml(false, args, out, err);
    }

    private void compileProcessorYaml(List<String> args, Consumer<String> out, Consumer<String> err) {
        loadProcessorYaml(true, args, out, err);
    }

    private void loadProcessor(boolean compileProcessor, List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) {
            err.accept("Missing arguments provide java file location");
            return;
        }

        String group = args.size() > 2 ? args.get(2) : DEFAULT_GROUP_JAVA_SRC;

        String javaSourceFiler = args.get(1);
        out.accept("loading java source from file:" + javaSourceFiler);

        Path javaSourceFilePath = Path.of(javaSourceFiler);
        if (!javaSourceFilePath.toFile().exists()) {
            err.accept("File not found: " + javaSourceFiler);
            return;
        }

        try {
            CompilationUnit cu = StaticJavaParser.parse(javaSourceFilePath);
            ClassOrInterfaceDeclaration classDeclaration = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
            if (classDeclaration != null) {
                String className = classDeclaration.getFullyQualifiedName().get();
                out.accept("compiling builder class: " + className);
                Class<FluxtionGraphBuilder> compiledClass = StringCompilation.compile(className, Files.readString(javaSourceFilePath));

                SerializableConsumer<EventProcessorConfig> buildGraph = compiledClass.getDeclaredConstructor().newInstance()::buildGraph;
                CloneableDataFlow<?> eventProcessor = compileProcessor
                        ? Fluxtion.compile(buildGraph, compilerConfigFor(javaSourceFiler, group))
                        : FluxtionInterpreter.interpret(buildGraph);

                eventProcessor.init();
                eventProcessor.setAuditLogLevel(initialLogLevel);

                out.accept("compiled and loaded processor" + eventProcessor.toString());
                serverController.addEventProcessor(javaSourceFiler, group, new YieldingIdleStrategy(), () -> eventProcessor);
            }
        } catch (IOException | ClassNotFoundException | URISyntaxException | InvocationTargetException |
                 InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            err.accept("Failed to compile java source file: " + javaSourceFiler);
            log.error(e);
        }
    }

    private void loadProcessorYaml(boolean compileProcessor, List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) {
            err.accept("Missing arguments provide yaml file location");
            return;
        }

        String group = args.size() > 2 ? args.get(2) : DEFAULT_GROUP_YAML;

        String javaSourceFiler = args.get(1);

        Path javaSourceFilePath = Path.of(javaSourceFiler);
        if (!javaSourceFilePath.toFile().exists()) {
            err.accept("File not found: " + javaSourceFiler);
            return;
        }

        out.accept("loading yaml file:" + javaSourceFiler);

        try {
            // SnakeYAML 2.x rejects all global tags by default via the
            // TagInspector on LoaderOptions — even the non-safe
            // Constructor inherits that gate. The loader's whole
            // contract is "user references node classes by FQN", so
            // install a permissive inspector. Callers who want a
            // tighter policy can wrap this loader and pre-validate.
            org.yaml.snakeyaml.LoaderOptions opts = new org.yaml.snakeyaml.LoaderOptions();
            opts.setTagInspector(tag -> true);
            org.yaml.snakeyaml.constructor.Constructor ctor =
                    new org.yaml.snakeyaml.constructor.Constructor(EventProcessorYamlCfg.class, opts);
            Yaml yaml = new Yaml(ctor);
            EventProcessorYamlCfg yamlCfg = yaml.loadAs(Files.readString(javaSourceFilePath), EventProcessorYamlCfg.class);

            SerializableConsumer<EventProcessorConfig> buildGraph = cfg -> {
                yamlCfg.nodes.forEach(cfg::addNode);

                yamlCfg.namedNodes.entrySet().forEach(entry -> {
                    cfg.addNode(entry.getValue(), entry.getKey());
                });

                cfg.setSupportDirtyFiltering(yamlCfg.isCheckDirtyFlags());

                yamlCfg.auditorMap.entrySet().forEach(entry -> {
                    cfg.addAuditor(entry.getValue(), entry.getKey());
                });

                if (addEventAuditor | yamlCfg.enableAudit) {
                    cfg.addEventAudit();
                    cfg.addEventAudit(traceLogLevel == null ? yamlCfg.traceLogeLevel : traceLogLevel);
                }

            };

            CloneableDataFlow<?> eventProcessor = compileProcessor
                    ? Fluxtion.compile(buildGraph, compilerConfigFor(javaSourceFiler, group))
                    : FluxtionInterpreter.interpret(buildGraph);
            eventProcessor.init();

            out.accept("compiled and loaded processor" + eventProcessor.toString());
            serverController.addEventProcessor(javaSourceFiler, group, new YieldingIdleStrategy(), () -> eventProcessor);
        } catch (Exception e) {
            err.accept("Failed to compile java source file: " + javaSourceFiler);
            log.error(e);
        }
    }

    /** Builds the compiler-config consumer applied to each
     *  {@code Fluxtion.compile} call. ALWAYS overrides package +
     *  className so the generated FQN is deterministic and free of
     *  `$` (Fluxtion's default lambda-derived names like
     *  {@code …lambda$compileForCfg$1.Processor} contain `$`, which
     *  the admin web's source endpoint mistakes for inner-class
     *  syntax and strips, breaking navigation). Output dirs are
     *  applied only when the operator sets them on the bean. */
    private LambdaReflection.SerializableConsumer<FluxtionCompilerConfig> compilerConfigFor(
            String sourceFile, String group) {
        final String pkg = packageName == null || packageName.isBlank()
                ? "com.telamin.mongoose.runtime.loaded.yaml" : packageName;
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
     *  class name, suffixing {@code _Processor}. Guarantees the result
     *  starts with a letter and contains only word chars — anything
     *  else gets squashed to '_'. Exposed for unit tests. */
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

    private Path persistentBaseOrError(Consumer<String> err) {
        try {
            Path base = PersistedConfigStore.resolveBaseDir(persistentConfigDir);
            if (base == null) {
                err.accept("persistentConfigDir not set on yamlEventHandlerLoader — persistence disabled");
            }
            return base;
        } catch (IOException io) {
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

    /** Two-phase: compile/interpret first via the existing loader,
     *  then — only on success — copy the source into the persistent
     *  dir. We never persist a config that wouldn't load, so the
     *  next-boot replay can trust every entry on disk. */
    private void persistAndLoad(boolean compileProcessor, List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() < 2) { err.accept("Missing arguments — usage: persistAndCompile <yamlPath> [group]"); return; }
        Path base = persistentBaseOrError(err);
        if (base == null) return;

        String yamlPath = args.get(1);
        String group = args.size() > 2 ? args.get(2) : DEFAULT_GROUP_YAML;
        boolean[] hadError = { false };
        loadProcessorYaml(compileProcessor,
                List.of("loadProcessor", yamlPath, group),
                out,
                msg -> { hadError[0] = true; err.accept(msg); });
        if (hadError[0]) {
            out.accept("compile failed — not persisting");
            return;
        }
        try {
            PersistedConfigStore.Entry persisted =
                    PersistedConfigStore.persist(base, group, Path.of(yamlPath), compileProcessor);
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

    @Data
    public static class EventProcessorYamlCfg {
        private List<Object> nodes = new ArrayList<>();
        private Map<String, Object> namedNodes = new HashMap<>();
        private boolean enableAudit = false;
        private EventLogControlEvent.LogLevel traceLogeLevel = EventLogControlEvent.LogLevel.NONE;
        private boolean checkDirtyFlags = true;
        private HashMap<String, Auditor> auditorMap = new HashMap<>();
        private FluxtionCompilerConfig compilerConfig;
    }

    @Data
    public static final class EventLoadAtStartup {
        private String yamlFile;
        private String group = DEFAULT_GROUP_YAML;
        private boolean addEventAuditor = false;
        private boolean compile = true;
        private EventLogControlEvent.LogLevel traceLogLevel;
        private EventLogControlEvent.LogLevel initialLogLevel = EventLogControlEvent.LogLevel.INFO;
    }
}
