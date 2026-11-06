/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.Signal;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import com.telamin.mongoose.plugin.svc.cache.Cache;
import com.telamin.mongoose.plugin.svc.cache.JsonFileCache;
import com.telamin.mongoose.service.admin.AdminCommandRequest;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs a generated processor over feed lines in process, with no server, collecting what it publishes to one sink.
 * Usable from JUnit (or any code) through {@link #builder()}, and from the command line through {@link #main}.
 *
 * <p>Each feed runs in a fresh processor instance (init, services, start, lines, stop, tearDown); a second feed
 * ({@link Builder#restart}) runs a new instance on the same cache files over its lines only, as a server restart that
 * resumes the feed does. Each cache file is deleted before the first run. Sink values are formatted as the
 * connector-file {@code JsonLineSink} writes them ({@link StateJson#line}): a String as is, anything else as one JSON
 * line. A feed line {@code #admin <command> [args...]} invokes a signal-routed admin command in the processor's event
 * cycle (as {@code registerSignalCommand} does on a server) and collects its replies; other lines starting with
 * {@code #} are comments.
 *
 * <pre>
 * FixtureRunner.Result result = FixtureRunner.builder()
 *         .processor(MarketProcessor::new)            // or .processor("com.example.generated.MarketProcessor")
 *         .mapper(new AppLineMapper())
 *         .sink("alerts")
 *         .cache("appState", tempDir.resolve("appState.json"))
 *         .feed(Path.of("fixtures/scenario.txt"))
 *         .restart(Path.of("fixtures/restart-B.txt"))
 *         .run();
 * assertEquals(Files.readAllLines(Path.of("fixtures/expected.txt")), result.sinkLines());
 * </pre>
 *
 * <p>Command line (the caller provides the class path: the application's classes, its dependencies and this module):
 * <pre>
 * java -cp &lt;classpath&gt; com.telamin.mongoose.plugin.testsupport.FixtureRunner
 *     --processor com.example.generated.MarketProcessor --mapper com.example.mapper.AppLineMapper --sink alerts
 *     [--cache name=data/run/state.json[,name2=file2]] --feed scenario.txt [--restart restart-B.txt]
 *     [--expect expected.txt] [--actual logs/fixture-actual.txt] [--audit logs/fixture-audit.yaml]
 * </pre>
 * Prints {@code admin> } replies, then PASS, or FAIL with the first differing lines (exit 0 / 1); without
 * {@code --expect} it prints the sink lines. A run that throws prints {@code FIXTURE RUN FAILED} (exit 2).
 * {@code --actual} writes every sink line of the run, {@code --audit} every node's audit records.
 * {@code --processor} names a {@link DataFlow} class or a {@code Supplier<DataFlow>} class, either with a no-arg
 * constructor; {@code --mapper} a {@code Function<Object, Object>} class.
 */
public final class FixtureRunner {

    private FixtureRunner() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** What a run published: the sink's lines in order, and the admin replies. */
    public record Result(List<String> sinkLines, List<String> adminReplies) {

        public Comparison compare(Path expected) throws IOException {
            return compare(Files.readAllLines(expected));
        }

        public Comparison compare(List<String> expected) {
            return new Comparison(sinkLines, List.copyOf(expected));
        }
    }

    /** Sink lines against expected lines. */
    public record Comparison(List<String> actual, List<String> expected) {

        public boolean pass() {
            return actual.equals(expected);
        }

        /** Up to {@code max} differing lines, as {@code line N: got ... / expected ...}. */
        public List<String> differences(int max) {
            List<String> diffs = new ArrayList<>();
            for (int i = 0; i < Math.max(actual.size(), expected.size()) && diffs.size() < max; i++) {
                String a = i < actual.size() ? actual.get(i) : "<none>";
                String e = i < expected.size() ? expected.get(i) : "<none>";
                if (!a.equals(e)) {
                    diffs.add("line " + (i + 1) + ": got      " + a + "\n           expected " + e);
                }
            }
            return diffs;
        }
    }

    /** Configures and runs one fixture. */
    public static final class Builder {
        private Supplier<? extends DataFlow> processor;
        private Function<Object, Object> mapper = x -> x;
        private String sink;
        private final Map<String, Path> caches = new LinkedHashMap<>();
        private final List<List<String>> feeds = new ArrayList<>();
        private Path actual;
        private Path audit;
        private Consumer<String> adminListener = r -> { };
        private ClassLoader classLoader;

        private Builder() {
        }

        /** A new processor instance for each feed, e.g. {@code MarketProcessor::new}. */
        public Builder processor(Supplier<? extends DataFlow> processor) {
            this.processor = Objects.requireNonNull(processor);
            return this;
        }

        /** A {@link DataFlow} class, or a {@code Supplier<DataFlow>} class, with a no-arg constructor. */
        public Builder processor(String className) {
            Objects.requireNonNull(className);
            this.processor = () -> {
                Object o = newInstance(className);
                if (o instanceof DataFlow flow) {
                    return flow;
                }
                if (o instanceof Supplier<?> s && s.get() instanceof DataFlow flow) {
                    return flow;
                }
                throw new IllegalArgumentException(className + " is neither a DataFlow nor a Supplier<DataFlow>");
            };
            return this;
        }

        /** The feed's value mapper; identity when not set. */
        public Builder mapper(Function<Object, Object> mapper) {
            this.mapper = Objects.requireNonNull(mapper);
            return this;
        }

        /** A {@code Function<Object, Object>} class with a no-arg constructor. */
        @SuppressWarnings("unchecked")
        public Builder mapper(String className) {
            this.mapper = (Function<Object, Object>) newInstance(className);
            return this;
        }

        /** The MessageSink service name whose values are collected. */
        public Builder sink(String name) {
            this.sink = name;
            return this;
        }

        /** A {@link JsonFileCache} registered as Cache service {@code name}, on {@code file} (deleted before the run). */
        public Builder cache(String name, Path file) {
            caches.put(name, file);
            return this;
        }

        public Builder feed(Path file) throws IOException {
            return feedLines(Files.readAllLines(file));
        }

        public Builder feedLines(List<String> lines) {
            feeds.add(List.copyOf(lines));
            return this;
        }

        /** A further run on a new processor instance and the same cache files: a server restart. */
        public Builder restart(Path file) throws IOException {
            return feed(file);
        }

        public Builder restartLines(List<String> lines) {
            return feedLines(lines);
        }

        /** Also write every sink line of the run to {@code file}. */
        public Builder actual(Path file) {
            this.actual = file;
            return this;
        }

        /** Write every node's audit records to {@code file}. */
        public Builder audit(Path file) {
            this.audit = file;
            return this;
        }

        /** Called with each admin reply as it is produced. */
        public Builder onAdminReply(Consumer<String> listener) {
            this.adminListener = Objects.requireNonNull(listener);
            return this;
        }

        /** The loader for class names; the thread's context class loader when not set. */
        public Builder classLoader(ClassLoader loader) {
            this.classLoader = loader;
            return this;
        }

        public Result run() throws Exception {
            if (processor == null) {
                throw new IllegalStateException("no processor");
            }
            if (sink == null) {
                throw new IllegalStateException("no sink name");
            }
            for (Path cacheFile : caches.values()) {
                Files.deleteIfExists(cacheFile);
                Files.deleteIfExists(cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp"));
                mkParent(cacheFile);
            }
            List<String> out = new ArrayList<>();
            List<String> replies = new ArrayList<>();
            Consumer<String> reply = r -> {
                replies.add(r);
                adminListener.accept(r);
            };
            try (PrintWriter auditOut = audit == null ? null : new PrintWriter(Files.newBufferedWriter(mkParent(audit)))) {
                for (List<String> feed : feeds) {
                    runOne(feed, out, reply, auditOut);
                }
            }
            if (actual != null) {
                Files.write(mkParent(actual), out);
            }
            return new Result(List.copyOf(out), List.copyOf(replies));
        }

        private void runOne(List<String> lines, List<String> out, Consumer<String> reply, PrintWriter auditOut)
                throws Exception {
            DataFlow flow = processor.get();
            List<JsonFileCache> opened = new ArrayList<>();
            boolean stopped = false;
            try {
                flow.setAuditLogProcessor(r -> {
                    if (auditOut != null) {
                        auditOut.println(r.asCharSequence());
                    }
                });
                flow.init();
                for (Map.Entry<String, Path> c : caches.entrySet()) {
                    JsonFileCache cache = new JsonFileCache();
                    cache.setFileName(c.getValue().toString());
                    cache.init();
                    opened.add(cache);
                    flow.registerService(new Service<>(cache, Cache.class, c.getKey()));
                }
                MessageSink<Object> collect = o -> out.add(StateJson.line(o));
                flow.registerService(new Service<>(collect, MessageSink.class, sink));
                flow.start();
                for (String line : lines) {
                    if (line.startsWith("#admin ")) {
                        admin(flow, line.substring(7).trim(), reply);
                    } else if (!line.startsWith("#")) {
                        flow.onEvent(mapper.apply(line));
                    }
                }
                stopped = true;
                flow.stop();
                flow.tearDown();
            } catch (Exception | Error e) {
                if (!stopped) {
                    try {
                        flow.stop();
                        flow.tearDown();
                    } catch (Throwable suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw e;
            } finally {
                for (JsonFileCache cache : opened) {
                    cache.tearDown();
                }
            }
        }

        private Object newInstance(String className) {
            try {
                ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
                return Class.forName(className, true, loader).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("cannot create " + className + ": " + e, e);
            }
        }
    }

    private static void admin(DataFlow flow, String commandLine, Consumer<String> reply) {
        List<String> words = Arrays.asList(commandLine.split("\\s+"));
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(words.get(0));
        request.setArguments(new ArrayList<>(words.subList(1, words.size())));
        List<Object> replies = new ArrayList<>();
        request.setOutput(replies::add);
        request.setErrOutput(e -> replies.add("ERROR " + e));
        flow.onEvent(new Signal<>("admin:" + words.get(0), request));
        if (replies.isEmpty()) {
            reply.accept(words.get(0) + ": no handler replied");
        }
        replies.forEach(r -> reply.accept(String.valueOf(r)));
    }

    private static Path mkParent(Path file) throws IOException {
        Path p = file.toAbsolutePath();
        Files.createDirectories(p.getParent());
        return p;
    }

    /** Runs the command line and always exits: an application's non-daemon threads must not keep the JVM alive. */
    public static void main(String[] args) {
        int rc = run(System.out, args);
        System.out.flush();
        System.exit(rc);
    }

    /**
     * The command line without exiting: 0 PASS (or no {@code --expect}), 1 FAIL, 2 the run failed or the options are
     * wrong.
     */
    public static int run(PrintStream console, String... args) {
        try {
            return runCommandLine(console, args);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            console.println("FIXTURE RUN FAILED: " + root);
            for (StackTraceElement e : t.getStackTrace()) {
                if (!e.getClassName().startsWith(FixtureRunner.class.getName())) {
                    console.println("  at " + e);
                    break;
                }
            }
            t.printStackTrace();
            return 2;
        }
    }

    private static int runCommandLine(PrintStream console, String[] args) throws Exception {
        Builder b = builder().onAdminReply(r -> console.println("admin> " + r));
        String expect = null, actual = null, audit = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("option " + args[i] + " has no value");
            }
            String v = args[i + 1];
            switch (args[i]) {
                case "--processor" -> b.processor(v);
                case "--mapper" -> b.mapper(v);
                case "--sink" -> b.sink(v);
                case "--cache" -> {
                    for (String c : v.split(",")) {
                        int eq = c.indexOf('=');
                        if (eq < 0) {
                            throw new IllegalArgumentException("--cache wants name=file, got " + c);
                        }
                        b.cache(c.substring(0, eq).trim(), Path.of(c.substring(eq + 1).trim()));
                    }
                }
                case "--feed", "--restart" -> b.feed(Path.of(v));
                case "--expect" -> expect = v;
                case "--actual" -> b.actual(Path.of(actual = v));
                case "--audit" -> b.audit(Path.of(audit = v));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Result result = b.run();
        if (expect == null) {
            result.sinkLines().forEach(console::println);
            return 0;
        }
        Comparison cmp = result.compare(Path.of(expect));
        if (cmp.pass()) {
            console.println("PASS: " + cmp.actual().size() + " lines match " + expect);
            return 0;
        }
        console.println("FAIL: " + cmp.actual().size() + " lines, expected " + cmp.expected().size() + " (" + expect + ")");
        cmp.differences(8).forEach(d -> console.println("  " + d));
        if (actual != null || audit != null) {
            console.println("  full output: " + (actual == null ? "-" : actual) + "   node audit (grep it for an id): "
                    + (audit == null ? "-" : audit));
        }
        return 1;
    }
}
