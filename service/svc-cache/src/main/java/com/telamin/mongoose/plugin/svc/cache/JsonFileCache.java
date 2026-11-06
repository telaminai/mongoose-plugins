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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * A {@link Cache} mirrored to a JSON file, reloaded on {@link #init()}.
 * <p>
 * <b>Crash-safe writes.</b> Every write serialises the whole cache, as compact JSON, to a sibling temporary file
 * and then moves it over {@code fileName} atomically. The file is never written in place, so a process killed mid-write
 * (or a write that fails part way) leaves the previous complete file, never a truncated one. Each write uses its own
 * temporary file, named {@code .<name>.<16 hex digits>.jfc.tmp} beside the file {@code <name>}: a hidden name that
 * no other cache's file or temporary file can have, so {@link #init()} deletes exactly this file's stale writes and
 * nothing else. A {@code fileName} of that form, one holding a control character or line terminator, or one longer
 * than {@link #MAX_NAME_BYTES} bytes is refused at {@link #init()}. This survives a process kill; surviving power loss would
 * also need an fsync, which is not done.
 * <p>
 * Use one instance per file: two instances writing the same file each replace it with their own map, and one's
 * {@code init()} deletes the other's in-flight temporary files. Distinct files never touch each other's.
 * <p>
 * With {@code asyncWrite=false} (the default) each {@link #put} and {@link #remove} writes before it returns; with
 * {@code asyncWrite=true} writes are left to {@link #doWork()} on the cache's agent thread. A change is made to the map
 * before the cache is marked dirty, so a write that overlaps it either includes it or leaves the cache dirty for the
 * next one. {@link #tearDown()} writes
 * the same way. Each write is logged at debug level only.
 */
@Data
@Log4j2
public class JsonFileCache implements Cache, Agent, Lifecycle, EventFlowService<Object> {

    /** The temporary file name of a write, for a target {@code <name>}: {@code .<name>.<16 hex>.jfc.tmp}. */
    static final String TEMP_SUFFIX = ".jfc.tmp";
    /**
     * The longest {@code fileName} (its last element, in UTF-8 bytes) whose temporary file name still fits the
     * common 255-byte limit: the temporary name adds a dot, a dot, 16 hex digits and the suffix.
     */
    static final int MAX_NAME_BYTES = 255 - 2 - 16 - TEMP_SUFFIX.length();

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
    /** Set once {@link #init()} has loaded (or created) the file: tearDown never writes over a file it never read. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile boolean initialised;

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
        String name = Path.of(fileName).getFileName().toString();
        if (name.codePoints().anyMatch(JsonFileCache::isControlOrLineTerminator)) {
            throw new IllegalStateException("JsonFileCache fileName " + escaped(fileName)
                    + " contains a control character or line terminator: choose another");
        }
        if (isAnyTempName(name)) {
            throw new IllegalStateException("JsonFileCache fileName " + fileName
                    + " has the form of a temporary file (.<name>.<16 hex digits>" + TEMP_SUFFIX + "): choose another");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new IllegalStateException("JsonFileCache fileName " + fileName + " is too long: its last element must be"
                    + " at most " + MAX_NAME_BYTES + " bytes in UTF-8, to leave room for its temporary file name");
        }
        Map<String, TypedData> loaded = null;
        initialised = false;
        file = new File(fileName);
        deleteStaleTempFiles();
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
        initialised = true;
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
        try {
            TypedData typedData = new TypedData();
            typedData.setType(value.getClass());
            typedData.setInstance(value);
            typedData.setData(mapper.writeValueAsString(value));
            cacheMap.put(key, typedData);
            updated.set(true);   // after the change: a write that takes the flag sees the change
            if (!asyncWrite) {
                syncWrite();
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
        cacheMap.remove(key);
        updated.set(true);
        if (!asyncWrite) {
            syncWrite();
        }
    }

    /**
     * The write of a synchronous put or remove. Serialised with every other write, so it returns only once a write that
     * includes this caller's change is on disk: either this one, or another caller's that took the dirty flag after
     * the change and completed before this method could enter.
     */
    private synchronized void syncWrite() throws IOException {
        if (updated.getAndSet(false)) {
            try {
                writeFile();
            } catch (IOException | RuntimeException e) {
                updated.set(true);
                throw e;
            }
            log.debug("cache updated:{} keys:{}", fileName, cacheMap.size());
        }
    }

    @Override
    public int doWork() throws Exception {
        syncWrite();   // a failed write leaves the cache dirty: the next doWork retries
        return 0;
    }

    /**
     * Writes the whole cache to a new temporary file ({@link #tempName}) and moves it over {@code fileName} atomically, so a reader or a
     * restart sees either the previous file or the new one, never a partial write. Serialised: a synchronous
     * {@code put} and the agent thread never write the temporary file at the same time.
     */
    private synchronized void writeFile() throws IOException {
        Path target = Path.of(fileName).toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = newTempFile(target);
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

    /** The name of a write's temporary file for {@code target}: {@code .<name>.<16 hex digits>.jfc.tmp}. */
    static String tempName(Path target, long random) {
        return "." + target.getFileName() + "." + String.format("%016x", random) + TEMP_SUFFIX;
    }

    private static boolean isControlOrLineTerminator(int c) {
        return Character.isISOControl(c) || c == '\u2028' || c == '\u2029';
    }

    private static String escaped(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> b.append(isControlOrLineTerminator(c)
                ? String.format("\\u%04x", c) : Character.toString(c)));
        return b.toString();
    }

    /**
     * True when {@code name} is the temporary file name of some target: {@code .<name>.<16 hex digits>.jfc.tmp} with a
     * non-empty name. The same structure as {@link #isTempFileOf}, so every name that could be some cache's temporary
     * file is refused as a {@code fileName}, whatever characters it holds.
     */
    static boolean isAnyTempName(String name) {
        int hex = name.length() - TEMP_SUFFIX.length() - 16;
        return name.length() >= 2 + 1 + 16 + TEMP_SUFFIX.length() && name.startsWith(".")
                && name.endsWith(TEMP_SUFFIX) && name.charAt(hex - 1) == '.' && isHex16(name, hex);
    }

    private static boolean isHex16(String s, int from) {
        return s.substring(from, from + 16).chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    /**
     * True when {@code candidate} is named as one of {@code target}'s temporary files. Only {@code target}'s own: the
     * name starts with the whole target name and ends with exactly 16 hex digits and the suffix, so no other file's
     * temporary name and no cache file (a {@code fileName} of that form is refused) can match.
     */
    static boolean isTempFileOf(Path target, Path candidate) {
        String n = candidate.getFileName().toString();
        String prefix = "." + target.getFileName() + ".";
        return n.length() == prefix.length() + 16 + TEMP_SUFFIX.length() && n.startsWith(prefix)
                && n.endsWith(TEMP_SUFFIX) && isHex16(n, prefix.length());
    }

    /** A new, empty temporary file for a write of {@code target}, readable by its owner only where POSIX allows. */
    private static Path newTempFile(Path target) throws IOException {
        Path parent = target.getParent();
        boolean posix = parent.getFileSystem().supportedFileAttributeViews().contains("posix");
        for (int attempt = 1; ; attempt++) {
            Path tmp = parent.resolve(tempName(target, ThreadLocalRandom.current().nextLong()));
            try {
                return posix
                        ? Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                        : Files.createFile(tmp);
            } catch (FileAlreadyExistsException e) {
                if (attempt == 8) {
                    throw e;
                }
            }
        }
    }

    /** Deletes this file's temporary files ({@link #isTempFileOf}): writes its previous run did not finish. */
    private void deleteStaleTempFiles() throws IOException {
        Path target = Path.of(fileName).toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(parent,
                p -> isTempFileOf(target, p) && Files.isRegularFile(p))) {
            for (Path stale : files) {
                Files.deleteIfExists(stale);
                log.info("deleted incomplete cache write:{}", stale);
            }
        }
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
        if (!initialised) {
            log.warn("cache {} was never initialised: not writing it", fileName);
            return;
        }
        synchronized (this) {
            updated.set(false);   // before the write: a change made while it runs stays dirty
            try {
                writeFile();
            } catch (IOException | RuntimeException e) {
                updated.set(true);
                throw e;
            }
        }
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
