/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.tooling.schemagen;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Version-axis gate (design §8.5): every plugin-index entry that carries a
 * {@code sourceVersion} (the mongoose-core built-ins) must match the actual
 * {@code mongoose-core} dependency — i.e. the parent pom's {@code <mongoose.version>},
 * surfaced here as the {@code mongoose.version} system property by surefire.
 * <p>
 * Stops the core built-in editors from silently describing the wrong mongoose
 * release when the dependency is bumped without updating the index literals.
 */
class CoreSourceVersionGateTest {

    @Test
    void coreSourceVersionsMatchMongooseDependency() throws Exception {
        String mongooseVersion = System.getProperty("mongoose.version");
        // Only enforced under the Maven build (surefire injects the property);
        // a bare IDE run without it is a no-op rather than a false failure.
        assumeTrue(mongooseVersion != null && !mongooseVersion.isBlank(),
                "mongoose.version system property not set (run via Maven)");

        JsonNode index;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin-index.json")) {
            assertNotNull(in, "plugin-index.json missing");
            index = SchemaGenMain.jsonMapper().readTree(in);
        }

        int checked = 0;
        for (JsonNode entry : index.path("plugins")) {
            if (entry.hasNonNull("sourceVersion")) {
                checked++;
                assertEquals(mongooseVersion, entry.get("sourceVersion").asText(),
                        () -> "core built-in '" + entry.path("instanceFqn").asText()
                                + "' sourceVersion drifted from <mongoose.version>="
                                + mongooseVersion + " — update plugin-index.json.");
            }
        }
        assertEquals(4, checked, "expected the 4 mongoose-core built-ins to carry sourceVersion");
    }
}
