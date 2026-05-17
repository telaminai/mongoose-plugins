/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Test harness that wraps a booted {@link MongooseServer} as an {@link AutoCloseable}
 * with builder-style configuration and a small suite of await helpers. Intended for
 * JUnit and TestNG integration tests against the real dispatcher.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (MongooseTestHarness h = MongooseTestHarness.builder()
 *         .feed("file-feed", fileSource, "file-source-agent")
 *         .sink("file-sink", fileSink)
 *         .processor("processor-agent", "forwarder", new ForwardingHandler())
 *         .start()) {
 *
 *     h.awaitFileLines(outputPath, 3);
 *     assertEquals(expected, Files.readAllLines(outputPath));
 * }
 * }</pre>
 *
 * <p>{@link #close()} is idempotent and always calls {@link MongooseServer#stop()}.
 */
public final class MongooseTestHarness implements AutoCloseable {

    /** Default await timeout. Tuned so a 1-ms idle-strategy server has plenty of cycles. */
    public static final Duration DEFAULT_AWAIT = Duration.ofSeconds(3);

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(20);

    private final MongooseServer server;
    private boolean closed;

    private MongooseTestHarness(MongooseServer server) {
        this.server = server;
    }

    /**
     * Start a builder. Default agent idle strategy is {@code SleepingMillisIdleStrategy(1)} —
     * the same recommendation the catalogue makes for production. Override per-feed or
     * per-sink as needed.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Wrap an already-booted {@link MongooseServer} for tests that own the boot
     * themselves. The harness will still stop the server on {@link #close()}.
     */
    public static MongooseTestHarness wrap(MongooseServer server) {
        return new MongooseTestHarness(server);
    }

    /** The wrapped server. Useful for advanced lifecycle inspection. */
    public MongooseServer server() {
        return server;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            server.stop();
        } catch (Exception ignored) {
            // Mongoose lifecycle exceptions during teardown shouldn't fail the test.
        }
    }

    // ------------------------------------------------------------------
    // Await helpers
    // ------------------------------------------------------------------

    /** Block until {@code condition} returns true, or {@link #DEFAULT_AWAIT} elapses. */
    public void awaitCondition(BooleanSupplier condition) {
        awaitCondition(condition, DEFAULT_AWAIT);
    }

    /**
     * Block until {@code condition} returns true, or {@code timeout} elapses. Polls
     * every 20 ms.
     *
     * @throws AssertionError if the timeout elapses before the condition holds.
     */
    public void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep(DEFAULT_POLL_INTERVAL);
        }
        if (condition.getAsBoolean()) return;
        throw new AssertionError("await condition timed out after " + timeout);
    }

    /**
     * Block until {@code file} exists and contains at least {@code expectedLineCount}
     * lines, or {@link #DEFAULT_AWAIT} elapses.
     */
    public void awaitFileLines(Path file, int expectedLineCount) {
        awaitFileLines(file, expectedLineCount, DEFAULT_AWAIT);
    }

    /**
     * Block until {@code file} contains at least {@code expectedLineCount} lines, or
     * {@code timeout} elapses.
     *
     * @throws AssertionError if the timeout elapses with fewer lines visible.
     */
    public void awaitFileLines(Path file, int expectedLineCount, Duration timeout) {
        awaitCondition(() -> {
            if (!Files.exists(file)) return false;
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8).size() >= expectedLineCount;
            } catch (IOException e) {
                return false;
            }
        }, timeout);
    }

    /**
     * Convenience: read all lines of {@code file} as UTF-8. Throws unchecked on I/O error.
     */
    public List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("failed to read " + file, e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static final class Builder {

        private final MongooseServerConfig.Builder serverBuilder = MongooseServerConfig.builder();
        private IdleStrategy defaultIdleStrategy = new SleepingMillisIdleStrategy(1);

        private Builder() {
        }

        /**
         * Override the default idle strategy used for feeds and sinks where none is
         * supplied explicitly. The default is {@code SleepingMillisIdleStrategy(1)}.
         */
        public Builder defaultIdleStrategy(IdleStrategy idleStrategy) {
            this.defaultIdleStrategy = idleStrategy;
            return this;
        }

        /**
         * Add an event feed with explicit agent name. Defaults to
         * {@code broadcast=true} and the harness's default idle strategy.
         */
        public Builder feed(String name, Object source, String agentName) {
            return feed(name, source, agentName, defaultIdleStrategy, true);
        }

        /**
         * Add an event feed with explicit agent + idle strategy. Defaults to
         * {@code broadcast=true} — handlers receive every event from this feed
         * without an explicit subscription.
         */
        public Builder feed(String name, Object source, String agentName, IdleStrategy idle) {
            return feed(name, source, agentName, idle, true);
        }

        /**
         * Add an event feed with full control. Pass {@code broadcast=false} when
         * multiple feeds coexist and each handler subscribes via
         * {@code getContext().subscribeToNamedFeed(name)} — broadcast=true would
         * otherwise deliver every feed's events to every handler.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Builder feed(String name, Object source, String agentName,
                            IdleStrategy idle, boolean broadcast) {
            var cfg = EventFeedConfig.builder()
                    .instance(source)
                    .name(name)
                    .broadcast(broadcast)
                    .agent(agentName, idle)
                    .build();
            serverBuilder.addEventFeed(cfg);
            return this;
        }

        /** Add an event sink (no agent host — the dispatcher writes directly). */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Builder sink(String name, MessageSink<?> sink) {
            var cfg = EventSinkConfig.builder().instance(sink).name(name).build();
            serverBuilder.addEventSink(cfg);
            return this;
        }

        /** Add a service registered under the concrete instance class. */
        public Builder service(String name, Object service) {
            serverBuilder.addService(new ServiceConfig<>(service, (Class) service.getClass(), name));
            return this;
        }

        /** Add a service registered under an explicit class (typically an interface). */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Builder service(String name, Object service, Class<?> serviceClass) {
            serverBuilder.addService(new ServiceConfig(service, (Class) serviceClass, name));
            return this;
        }

        /** Add a processor — name + handler instance, on the given agent. */
        public Builder processor(String agentName, String handlerName, ObjectEventHandlerNode handler) {
            var cfg = EventProcessorConfig.builder()
                    .customHandler(handler)
                    .name(handlerName)
                    .build();
            serverBuilder.addProcessor(agentName, handlerName, cfg);
            return this;
        }

        /** Add a processor via a plain {@code Consumer<Object>} handler. */
        public Builder processor(String agentName, String handlerName, Consumer<Object> handler) {
            var cfg = EventProcessorConfig.builder()
                    .handlerFunction(handler)
                    .name(handlerName)
                    .build();
            serverBuilder.addProcessor(agentName, handlerName, cfg);
            return this;
        }

        /**
         * Escape hatch: apply arbitrary customisation to the underlying
         * {@link MongooseServerConfig.Builder}. Use this when the convenience
         * methods don't cover your case.
         */
        public Builder customise(Function<MongooseServerConfig.Builder, MongooseServerConfig.Builder> fn) {
            fn.apply(serverBuilder);
            return this;
        }

        /** Build the config + boot the server + wrap in a harness. */
        public MongooseTestHarness start() {
            MongooseServerConfig cfg = serverBuilder.build();
            MongooseServer server = MongooseServer.bootServer(cfg);
            return new MongooseTestHarness(server);
        }
    }
}
