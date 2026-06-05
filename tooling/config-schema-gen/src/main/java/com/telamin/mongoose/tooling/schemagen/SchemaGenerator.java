/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.tooling.schemagen;

import com.fasterxml.jackson.databind.JsonNode;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.FieldSchema;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.PluginSchema;
import com.telamin.mongoose.tooling.schemagen.SchemaModel.Schema;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Reflects each indexed connector/service config class's JavaBean writable
 * properties into a {@link Schema}, filtering framework-injection setters and
 * merging hand-curated overrides. See design/connector-config-schema.md §3.
 * <p>
 * Pure, deterministic (fields sorted by name); no server boot, no network.
 */
public final class SchemaGenerator {

    public static final int SCHEMA_VERSION = 1;

    /**
     * Single-arg setters whose parameter type lives under one of these packages are
     * framework injection points (EventToQueuePublisher, EventFlowManager, an
     * EventProcessor, AdminCommandRegistry, …), not user config — skipped.
     */
    private static final Set<String> FRAMEWORK_TYPE_PREFIXES = Set.of(
            "com.telamin.mongoose.dispatch.",
            "com.telamin.mongoose.service.",
            "com.telamin.mongoose.internal.",
            "com.telamin.fluxtion.runtime.",
            // never YAML-scalar config: io handles, concurrency primitives.
            "java.io.",
            "java.util.concurrent.");

    /**
     * Property names that are framework plumbing even when scalar-typed: the
     * dispatch hooks, plus identity props that are set at the descriptor entry
     * level (eventFeeds/services {@code name:}) not inside the instance, and the
     * instance-level value/data mappers which are traps — a feed's mapper belongs
     * on EventFeedConfig, the instance setter is overwritten.
     */
    private static final Set<String> FRAMEWORK_PROP_NAMES = Set.of(
            "eventToQueuePublisher", "eventFlowManager", "eventToInvokeStrategy",
            "name", "serviceName", "dataMapper", "valueMapper");

    public Schema generate(JsonNode index, JsonNode overrides, String pluginsVersion) {
        List<PluginSchema> plugins = new ArrayList<>();
        for (JsonNode entry : index.path("plugins")) {
            plugins.add(pluginSchema(entry, overrides));
        }
        return new Schema(SCHEMA_VERSION, pluginsVersion, plugins);
    }

    private PluginSchema pluginSchema(JsonNode entry, JsonNode overrides) {
        String artifactId = entry.path("artifactId").asText();
        String kind = entry.path("kind").asText();
        String fqn = entry.path("instanceFqn").asText();
        String doc = entry.hasNonNull("doc") ? entry.get("doc").asText() : null;
        String sourceVersion = entry.hasNonNull("sourceVersion") ? entry.get("sourceVersion").asText() : null;

        // Override key: explicit `id` when present, else `artifactId:kind`. The id is
        // needed when one artifact exposes >1 entry of the same kind (e.g. mongoose
        // core has file + in-memory sources — both "mongoose:source").
        String ovKey = entry.hasNonNull("id") ? entry.get("id").asText() : artifactId + ":" + kind;
        JsonNode ov = overrides.path("overrides").path(ovKey);
        List<FieldSchema> fields = reflectFields(fqn, ov);

        return new PluginSchema(artifactId, kind, yamlKey(kind), yamlBindKey(kind), fqn, sourceVersion, doc, fields);
    }

    List<FieldSchema> reflectFields(String fqn, JsonNode ov) {
        final Class<?> cls;
        try {
            // initialize=false: reflect properties without running static
            // initialisers (some connectors touch native libs at init).
            cls = Class.forName(fqn, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("config class not on classpath: " + fqn, e);
        }
        Object defaultsInstance = newInstanceOrNull(cls);

        List<FieldSchema> fields = new ArrayList<>();
        for (PropertyDescriptor pd : beanProperties(cls)) {
            if (pd.getWriteMethod() == null || "class".equals(pd.getName())) {
                continue;
            }
            String name = pd.getName();
            Class<?> type = pd.getWriteMethod().getParameterTypes()[0];

            if (isFramework(name, type) || overrideBool(ov, name, "hide", false)) {
                continue;
            }

            String typeName = mapType(type);
            List<String> enumValues = type.isEnum() ? enumConstants(type) : null;
            Object defaultValue = sanitizeDefault(readDefault(defaultsInstance, pd));

            // Element types for collections, read off the array component or the
            // setter's generic signature. Properties (Hashtable<Object,Object> but
            // conventionally String→String) is special-cased.
            java.lang.reflect.Type generic = pd.getWriteMethod().getGenericParameterTypes()[0];
            String elementType = null, keyType = null, valueType = null;
            if ("list".equals(typeName)) {
                elementType = type.isArray() ? mapType(type.getComponentType()) : typeArg(generic, 0);
            } else if ("map".equals(typeName)) {
                if (java.util.Properties.class.isAssignableFrom(type)) {
                    keyType = "string";
                    valueType = "string";
                } else {
                    keyType = typeArg(generic, 0);
                    valueType = typeArg(generic, 1);
                }
            }

            // Optional by default — a null/absent default does NOT imply required
            // (most optional fields default to null). Required is asserted via the
            // override (or, later, a @ConfigField annotation), never inferred.
            boolean required = overrideBool(ov, name, "required", false);

            String format = overrideStr(ov, name, "format");
            String doc = overrideStr(ov, name, "doc");

            fields.add(new FieldSchema(name, typeName, required, defaultValue, enumValues,
                    elementType, keyType, valueType, format, doc));
        }
        fields.sort(Comparator.comparing(FieldSchema::name));
        return fields;
    }

    private static List<PropertyDescriptor> beanProperties(Class<?> cls) {
        try {
            // Stop at Object so inherited Object props (getClass) are excluded; we
            // still want inherited config props from abstract bases.
            BeanInfo info = Introspector.getBeanInfo(cls, Object.class);
            return List.of(info.getPropertyDescriptors());
        } catch (Exception e) {
            throw new IllegalStateException("introspection failed for " + cls.getName(), e);
        }
    }

    private static boolean isFramework(String name, Class<?> type) {
        if (FRAMEWORK_PROP_NAMES.contains(name)) {
            return true;
        }
        String tn = type.getName();
        for (String prefix : FRAMEWORK_TYPE_PREFIXES) {
            if (tn.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Object readDefault(Object instance, PropertyDescriptor pd) {
        if (instance == null || pd.getReadMethod() == null) {
            return null;
        }
        try {
            Object v = pd.getReadMethod().invoke(instance);
            if (v instanceof Enum<?> e) {
                return e.name();
            }
            return v;
        } catch (Exception e) {
            // A getter that throws without prerequisites: report no default rather
            // than failing the whole run (design §8 Q4).
            return null;
        }
    }

    private static Object newInstanceOrNull(Class<?> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null; // no usable no-arg ctor → defaults unavailable, fields still emitted
        }
    }

    private static String mapType(Class<?> t) {
        if (t.isEnum()) return "enum";
        if (t == String.class) return "string";
        if (t == boolean.class || t == Boolean.class) return "boolean";
        if (t == int.class || t == Integer.class) return "int";
        if (t == long.class || t == Long.class) return "long";
        if (t == double.class || t == Double.class || t == float.class || t == Float.class) return "double";
        if (t == Duration.class) return "duration";
        if (java.util.Map.class.isAssignableFrom(t)) return "map";
        if (java.util.Collection.class.isAssignableFrom(t) || t.isArray()) return "list";
        return "ref"; // other non-scalar (idleStrategy, codecs…) — §8 Q2
    }

    /**
     * Null out defaults that captured machine/environment state — an absolute
     * filesystem path, or a value equal to a JVM/system property (user.dir,
     * tmpdir, home, hostname). Such a value must not be baked into a versioned,
     * "deterministic" schema or the docs site (review: HIGH). The field stays;
     * only its default drops to null so the editor shows "no default".
     */
    static Object sanitizeDefault(Object v) {
        if (!(v instanceof String s) || s.isEmpty()) {
            return v;
        }
        if (s.startsWith("/") || s.startsWith("\\") || s.matches("^[A-Za-z]:[\\\\/].*")) {
            return null; // absolute path — environment-specific
        }
        for (String key : new String[]{"user.dir", "java.io.tmpdir", "user.home", "user.name"}) {
            String p = System.getProperty(key);
            if (p != null && !p.isEmpty() && s.contains(p)) {
                return null;
            }
        }
        return v;
    }

    /** The i-th type argument of a parameterised setter param, mapped to a scalar
     *  type name (e.g. {@code List<String>} → "string", {@code Map<String,Integer>}
     *  key → "string"). Returns "ref" when the arg isn't a simple class. */
    private static String typeArg(java.lang.reflect.Type generic, int index) {
        if (generic instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> c) {
                return mapType(c);
            }
        }
        return "ref";
    }

    private static List<String> enumConstants(Class<?> t) {
        List<String> out = new ArrayList<>();
        for (Object c : t.getEnumConstants()) {
            out.add(((Enum<?>) c).name());
        }
        return out;
    }

    static String yamlKey(String kind) {
        return switch (kind) {
            case "source" -> "eventFeeds";
            case "sink" -> "eventSinks";
            case "service" -> "services";
            default -> throw new IllegalArgumentException("unknown kind: " + kind);
        };
    }

    static String yamlBindKey(String kind) {
        // feeds/sinks bind under instance:, services under service: — load-bearing.
        return "service".equals(kind) ? "service" : "instance";
    }

    private static boolean overrideBool(JsonNode ov, String prop, String key, boolean dflt) {
        JsonNode n = ov.path(prop).path(key);
        return n.isBoolean() ? n.asBoolean() : dflt;
    }

    private static String overrideStr(JsonNode ov, String prop, String key) {
        JsonNode n = ov.path(prop).path(key);
        return n.isTextual() ? n.asText() : null;
    }
}
