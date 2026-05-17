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
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    public void register(AdminCommandRegistry registry, String fileName) {
        log.info("Registering admin command registry {}", registry);
        this.registry = registry;
    }

    @Override
    public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
        log.info("setEventFlowManager serviceName:{}", serviceName);
        this.serviceName = serviceName;
        registry.registerCommand("cache." + serviceName + ".get", this::getCommand);
        registry.registerCommand("cache." + serviceName + ".keys", this::listKeys);
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
        if (updated.get()) {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(fileName), cacheMap);
            if (cacheMap.isEmpty()) {
                log.info("cache updated:{} now empty ", fileName);
            } else {
                log.info("cache updated:{} keys:{}", fileName, cacheMap.keySet());
            }
        }
        updated.set(false);
        return 0;
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
        mapper.writeValue(new File(fileName), cacheMap);
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
