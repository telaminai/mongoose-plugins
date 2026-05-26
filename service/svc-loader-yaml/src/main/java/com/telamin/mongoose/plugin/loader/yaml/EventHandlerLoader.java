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

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        adminCommandRegistry.registerCommand("javaLoader.compileProcessor", this::compileProcessor);
        adminCommandRegistry.registerCommand("javaLoader.interpretProcessor", this::interpretProcessor);
        adminCommandRegistry.registerCommand("yamlLoader.compileProcessor", this::compileProcessorYaml);
        adminCommandRegistry.registerCommand("yamlLoader.interpretProcessor", this::interpretProcessorYaml);
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
