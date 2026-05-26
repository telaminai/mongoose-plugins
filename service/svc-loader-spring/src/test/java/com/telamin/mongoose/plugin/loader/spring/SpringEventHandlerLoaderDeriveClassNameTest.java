/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringEventHandlerLoaderDeriveClassNameTest {

    @Test
    void strips_directory_and_extension() {
        assertEquals(
                "tech_filter_spring_techGroup_Processor",
                SpringEventHandlerLoader.deriveClassName("configs/tech-filter-spring.xml", "techGroup"));
    }

    @Test
    void handles_absolute_paths() {
        assertEquals(
                "config_g_Processor",
                SpringEventHandlerLoader.deriveClassName("/Users/x/config.xml", "g"));
    }

    @Test
    void empty_group_omits_group_suffix() {
        assertEquals(
                "config_Processor",
                SpringEventHandlerLoader.deriveClassName("config.xml", ""));
    }

    @Test
    void leading_digit_gets_prefixed() {
        String result = SpringEventHandlerLoader.deriveClassName("1stConfig.xml", "g");
        assertTrue(Character.isJavaIdentifierStart(result.charAt(0)));
        assertTrue(result.startsWith("P_"));
    }
}