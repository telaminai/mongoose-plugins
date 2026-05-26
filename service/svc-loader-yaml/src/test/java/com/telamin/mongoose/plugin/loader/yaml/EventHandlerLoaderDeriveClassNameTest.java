/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.yaml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Class-name derivation has to yield a legal Java identifier from
 *  arbitrary filesystem paths + group strings. The emitted .java + .graphml
 *  artefacts depend on this — collisions would silently overwrite, and
 *  a non-identifier name would fail the Fluxtion codegen. */
class EventHandlerLoaderDeriveClassNameTest {

    @Test
    void strips_directory_and_extension() {
        assertEquals(
                "aapl_printer_aaplGroup_Processor",
                EventHandlerLoader.deriveClassName("configs/aapl-printer.yaml", "aaplGroup"));
    }

    @Test
    void handles_absolute_paths() {
        assertEquals(
                "config_techGroup_Processor",
                EventHandlerLoader.deriveClassName("/Users/x/y/config.yaml", "techGroup"));
    }

    @Test
    void empty_group_omits_group_suffix() {
        assertEquals(
                "config_Processor",
                EventHandlerLoader.deriveClassName("config.yaml", ""));
        assertEquals(
                "config_Processor",
                EventHandlerLoader.deriveClassName("config.yaml", null));
    }

    @Test
    void non_identifier_chars_get_squashed() {
        // hyphens, dots-in-stem, spaces — all replaced with `_`
        String result = EventHandlerLoader.deriveClassName("my.cool-config v2.yaml", "g-1");
        // Java identifier check
        assertTrue(Character.isJavaIdentifierStart(result.charAt(0)));
        for (char c : result.toCharArray()) {
            assertTrue(Character.isJavaIdentifierPart(c), "illegal char: " + c);
        }
    }

    @Test
    void leading_digit_gets_prefixed() {
        // a stem starting with a digit would yield a non-identifier
        String result = EventHandlerLoader.deriveClassName("123-config.yaml", "g");
        assertTrue(Character.isJavaIdentifierStart(result.charAt(0)));
        assertTrue(result.startsWith("P_"));
    }

    @Test
    void null_source_file_safe() {
        // Defensive: rather than NPE, fall back to "loaded"
        assertEquals(
                "loaded_g_Processor",
                EventHandlerLoader.deriveClassName(null, "g"));
    }
}