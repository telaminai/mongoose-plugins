/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.cache;

import lombok.extern.log4j.Log4j2;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reflective, resolved-once access to Mongoose's {@code DeliveryContext} (the source position of the event being
 * handled), so this module compiles and runs against a Mongoose without it. Against such a Mongoose
 * {@link #enabled()} is false and {@link #current()} always null; against one that has
 * {@value #CONTEXT_CLASS}, whose static {@code current()} returns a {@code Delivery} with {@code feed()},
 * {@code epoch()}, {@code position()} and {@code alreadyApplied(long, Object)}, the calls go straight to it through
 * method handles. The delivery-complete hook (Mongoose 1.1.3: static {@code onComplete(Runnable)} and
 * {@code attempt()}) is optional: {@link #hook()} says whether this Mongoose has it.
 */
@Log4j2
final class DeliveryAccess {

    static final String CONTEXT_CLASS = "com.telamin.mongoose.service.delivery.DeliveryContext";
    static final DeliveryAccess OFF = new DeliveryAccess(null, null, null, null, null, null, null);
    /** Resolved once against the Mongoose on this module's class path. */
    static final DeliveryAccess DEFAULT = resolve(CONTEXT_CLASS, DeliveryAccess.class.getClassLoader());

    private final MethodHandle current;          // ()Object
    private final MethodHandle feed;             // (Object)String
    private final MethodHandle epoch;            // (Object)long
    private final MethodHandle position;         // (Object)Object
    private final MethodHandle alreadyApplied;   // (Object,long,Object)boolean
    private final MethodHandle onComplete;       // (Runnable)boolean, null without the hook
    private final MethodHandle attempt;          // ()int, null without the hook

    private DeliveryAccess(MethodHandle current, MethodHandle feed, MethodHandle epoch, MethodHandle position,
                           MethodHandle alreadyApplied, MethodHandle onComplete, MethodHandle attempt) {
        this.onComplete = onComplete;
        this.attempt = attempt;
        this.current = current;
        this.feed = feed;
        this.epoch = epoch;
        this.position = position;
        this.alreadyApplied = alreadyApplied;
    }

    /**
     * Access to the delivery context class {@code className}, or {@link #OFF} when it is absent or does not have the
     * expected shape.
     */
    static DeliveryAccess resolve(String className, ClassLoader loader) {
        Class<?> context;
        try {
            context = Class.forName(className, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            log.debug("no {} on the class path: replay guard off", className);
            return OFF;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> delivery = context.getMethod("current").getReturnType();
            MethodHandle current = lookup.findStatic(context, "current", MethodType.methodType(delivery))
                    .asType(MethodType.methodType(Object.class));
            MethodHandle feed = lookup.findVirtual(delivery, "feed", MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
            MethodHandle epoch = lookup.findVirtual(delivery, "epoch", MethodType.methodType(long.class))
                    .asType(MethodType.methodType(long.class, Object.class));
            MethodHandle position = lookup.findVirtual(delivery, "position", MethodType.methodType(Object.class))
                    .asType(MethodType.methodType(Object.class, Object.class));
            MethodHandle alreadyApplied = lookup.findVirtual(delivery, "alreadyApplied",
                            MethodType.methodType(boolean.class, long.class, Object.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, long.class, Object.class));
            MethodHandle onComplete = null;
            MethodHandle attempt = null;
            try {
                onComplete = lookup.findStatic(context, "onComplete", MethodType.methodType(boolean.class, Runnable.class));
                attempt = lookup.findStatic(context, "attempt", MethodType.methodType(int.class));
            } catch (NoSuchMethodException | IllegalAccessException noHook) {
                onComplete = null;
                attempt = null;
            }
            log.debug("{} found: replay guard on, delivery-complete hook {}", className, onComplete != null ? "on" : "off");
            return new DeliveryAccess(current, feed, epoch, position, alreadyApplied, onComplete, attempt);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.warn("{} found but not usable ({}): replay guard off", className, e.toString());
            return OFF;
        }
    }

    boolean enabled() {
        return current != null;
    }

    /** True when this Mongoose runs callbacks at delivery completion ({@code DeliveryContext.onComplete}). */
    boolean hook() {
        return onComplete != null;
    }

    /**
     * Register {@code callback} for the current delivery's completion: true when registered (or already pending), false
     * when there is no hook or no positioned delivery on this thread (nothing kept).
     */
    boolean onComplete(Runnable callback) {
        if (onComplete == null) {
            return false;
        }
        try {
            return (boolean) onComplete.invokeExact(callback);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Failed dispatch attempts of the current item (0 without the hook). */
    int attempt() {
        if (attempt == null) {
            return 0;
        }
        try {
            return (int) attempt.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The delivery of the event the calling thread is handling, or null (none, or no delivery context). */
    Object current() {
        if (current == null) {
            return null;
        }
        try {
            return (Object) current.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    String feed(Object delivery) {
        try {
            return (String) feed.invokeExact(delivery);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    long epoch(Object delivery) {
        try {
            return (long) epoch.invokeExact(delivery);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    Object position(Object delivery) {
        try {
            return (Object) position.invokeExact(delivery);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    boolean alreadyApplied(Object delivery, long storedEpoch, Object storedPosition) {
        try {
            return (boolean) alreadyApplied.invokeExact(delivery, storedEpoch, storedPosition);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
