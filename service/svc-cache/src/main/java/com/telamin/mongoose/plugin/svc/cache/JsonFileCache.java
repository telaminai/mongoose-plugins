/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.cache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agrona.concurrent.Agent;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A {@link Cache} mirrored to a JSON file, reloaded on {@link #init()}.
 * <p>
 * <b>Crash-safe writes.</b> Every write serialises the whole cache, as compact JSON, to a sibling temporary file
 * ({@code <fileName>.tmp}) and then moves it over {@code fileName} atomically. The file is never written in place, so a
 * process killed mid-write (or a write that fails part way) leaves the previous complete file, never a truncated one;
 * a stale temporary file is ignored and replaced by the next write. This survives a process kill; surviving power
 * loss would also need an fsync, which is not done.
 * <p>
 * With {@code asyncWrite=false} (the default) each {@link #put} and {@link #remove} writes before it returns; with
 * {@code asyncWrite=true} writes are left to {@link #doWork()} on the cache's agent thread. {@link #tearDown()} writes
 * the same way. Each write is logged at debug level only.
 */
@Data
@Log4j2
public class JsonFileCache implements Cache, Agent, Lifecycle, EventFlowService<Object> {

    private String fileName;
    private final AtomicBoolean updated = new AtomicBoolean(false);
    @Setter(AccessLevel.NONE)
    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, TypedData> cacheMap = new ConcurrentHashMap<>();
    private static final TypedData TYPED_DATA_NULL = new TypedData();
    private File file;
    private File redoLogFile;
    private String serviceName;
    private AdminCommandRegistry registry;
    /** Registries the admin commands were registered with, by identity; guards against repeat registration (#49). */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Set<AdminCommandRegistry> commandsRegisteredWith =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean asyncWrite = false;
    /**
     * Hard cap on entries. {@code 0} (default) means unbounded. When set, the
     * backing map switches to access-order LRU and evicts the eldest entry on
     * overflow. Eviction also flags {@code updated} so the JSON file reflects
     * the trimmed state.
     */
    private int maxSize = 0;
    @Setter(AccessLevel.NONE)
    private final AtomicLong evictedCount = new AtomicLong();

    @SneakyThrows
    @Override
    public void init() {
        log.info("init maxSize:{}", maxSize);
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalStateException("JsonFileCache has no fileName configured");
        }
        if (maxSize < 0) {
            throw new IllegalStateException("JsonFileCache maxSize must be >= 0, got " + maxSize);
        }
        Map<String, TypedData> loaded = null;
        file = new File(fileName);
        Path staleTemp = tempPath();
        if (Files.deleteIfExists(staleTemp)) {
            log.info("deleted incomplete cache write:{}", staleTemp);
        }
        if (file.exists() && file.length() > 0) {
            log.info("opened cache file:{}", fileName);
            loaded = mapper.readValue(file, new TypeReference<Map<String, TypedData>>() {
            });
        } else {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            log.info("no cache file:{} created:{}", fileName, file.createNewFile());
        }
        cacheMap = buildBackingMap();
        if (loaded != null) {
            cacheMap.putAll(loaded);
            // Materialise the JSON-string values back into the cached `instance` field.
            // Snapshot the key set first — get() touches access order under LRU mode and
            // would otherwise trigger ConcurrentModificationException on live iteration.
            for (String key : new java.util.ArrayList<>(cacheMap.keySet())) {
                get(key);
            }
        }
    }

    private Map<String, TypedData> buildBackingMap() {
        if (maxSize > 0) {
            return Collections.synchronizedMap(new LinkedHashMap<String, TypedData>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TypedData> eldest) {
                    if (size() > maxSize) {
                        evictedCount.incrementAndGet();
                        updated.set(true);
                        return true;
                    }
                    return false;
                }
            });
        }
        return new ConcurrentHashMap<>();
    }

    public long getEvictedCount() {
        return evictedCount.get();
    }

    @ServiceRegistered
    public void register(AdminCommandRegistry registry, String registryName) {
        log.info("Registering admin command registry {}", registry);
        this.registry = registry;
        registerAdminCommands();
    }

    @Override
    public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
        log.info("setEventFlowManager serviceName:{}", serviceName);
        this.serviceName = serviceName;
        registerAdminCommands();
    }

    /**
     * Registers the cache admin commands once both the registry and the service name
     * are known. The server may call {@link #setEventFlowManager} before the
     * {@code @ServiceRegistered} registry callback (issue #49), so each callback tries
     * and only the one that completes the pair registers. Idempotent per registry.
     */
    private synchronized void registerAdminCommands() {
        if (registry == null || serviceName == null || commandsRegisteredWith.contains(registry)) {
            return;
        }
        registry.registerCommand("cache." + serviceName + ".get", this::getCommand);
        registry.registerCommand("cache." + serviceName + ".keys", this::listKeys);
        // Mark only after both succeed, so a failed registration is retried by the next callback.
        commandsRegisteredWith.add(registry);
    }

    // EventFlowService -> EventSource contract: this cache does not push events
    // into the dispatch pipeline, it only services lookups, so the queue
    // publisher and (un)subscribe calls are intentionally no-ops.
    @Override
    public void setEventToQueuePublisher(com.telamin.mongoose.dispatch.EventToQueuePublisher<Object> targetQueue) {
        // no-op
    }

    @Override
    public void subscribe(com.telamin.mongoose.service.EventSubscriptionKey<Object> eventSourceKey) {
        // no-op
    }

    @Override
    public void unSubscribe(com.telamin.mongoose.service.EventSubscriptionKey<Object> eventSourceKey) {
        // no-op
    }

    @Override
    public Collection<String> keys() {
        return cacheMap.keySet();
    }

    @Override
    public void put(String key, Object value) {
        updated.set(true);
        try {
            TypedData typedData = new TypedData();
            typedData.setType(value.getClass());
            typedData.setInstance(value);
            typedData.setData(mapper.writeValueAsString(value));
            cacheMap.put(key, typedData);
            if (!asyncWrite) {
                doWork();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T get(String key) {
        TypedData typeData = cacheMap.getOrDefault(key, new TypedData());
        var data = typeData.getData();
        Class<T> clazz = (Class<T>) typeData.getType();
        if (clazz != null && data != null) {
            if (typeData.instance != null) {
                return (T) typeData.instance;
            }
            try {
                T t = mapper.readValue(data, clazz);
                typeData.setInstance(t);
                return t;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @SneakyThrows
    @Override
    public void remove(String key) {
        updated.set(true);
        cacheMap.remove(key);
        if (!asyncWrite) {
            doWork();
        }
    }

    @Override
    public int doWork() throws Exception {
        if (updated.getAndSet(false)) {
            try {
                writeFile();
            } catch (IOException | RuntimeException e) {
                updated.set(true);   // still dirty: the next doWork retries
                throw e;
            }
            log.debug("cache updated:{} keys:{}", fileName, cacheMap.size());
        }
        return 0;
    }

    /**
     * Writes the whole cache to {@code <fileName>.tmp} and moves it over {@code fileName} atomically, so a reader or a
     * restart sees either the previous file or the new one, never a partial write. Serialised: a synchronous
     * {@code put} and the agent thread never write the temporary file at the same time.
     */
    private synchronized void writeFile() throws IOException {
        Path target = Path.of(fileName).toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = tempPath();
        try {
            if (maxSize > 0) {
                synchronized (cacheMap) {   // Collections.synchronizedMap: iterate under its lock
                    mapper.writeValue(tmp.toFile(), cacheMap);
                }
            } else {
                mapper.writeValue(tmp.toFile(), cacheMap);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private Path tempPath() {
        Path target = Path.of(fileName).toAbsolutePath();
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    @Override
    public String roleName() {
        return "";
    }

    @SneakyThrows
    @Override
    public void tearDown() {
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        writeFile();
        updated.set(false);
    }

    private void getCommand(List<String> args, Consumer<String> out, Consumer<String> err) {
        if (args.size() >= 2) {
            String key = args.get(1);
            Object data = get(key);
            log.debug("key:{} data:{}", key, data);
            out.accept(key + " -> " + data);
        } else {
            err.accept("provide key as first argument");
        }
    }

    private void listKeys(List<String> args, Consumer<String> out, Consumer<String> err) {
        out.accept("keys:\n" + String.join("\n", cacheMap.keySet()));
    }

    @Data
    public static class TypedData {
        private Class<?> type;
        @JsonIgnore
        private Object instance;
        private String data;
    }
}
