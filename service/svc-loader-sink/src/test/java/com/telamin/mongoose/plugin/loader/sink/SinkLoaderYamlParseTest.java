/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.sink;

import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.fluxtion.runtime.output.MessageSink;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** YAML-snippet → EventSinkConfig round-trip. */
class SinkLoaderYamlParseTest {

    /** Public so SnakeYAML can instantiate via JavaBean rules. */
    public static class FakeSink implements MessageSink<Object> {
        public String filename;
        public String getFilename() { return filename; }
        public void setFilename(String v) { this.filename = v; }
        @Override public void accept(Object o) { }
        @Override public void setValueMapper(Function<Object, ?> mapper) { }
    }

    @Test
    void parses_name_and_instance() {
        String yaml = ""
                + "name: trades-out\n"
                + "instance: !!" + FakeSink.class.getName() + "\n"
                + "  filename: out.csv\n";

        EventSinkConfig<?> cfg = SinkLoader.parseSinkYaml(yaml);

        assertEquals("trades-out", cfg.getName());
        assertInstanceOf(FakeSink.class, cfg.getInstance());
        assertEquals("out.csv", ((FakeSink) cfg.getInstance()).filename);
        assertFalse(cfg.isAgent());
    }

    @Test
    void agent_section_flags_agent_hosted() {
        String yaml = ""
                + "name: trades-out\n"
                + "instance: !!" + FakeSink.class.getName() + " { filename: x }\n"
                + "agentName: sinks-agent\n"
                + "idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}\n";

        EventSinkConfig<?> cfg = SinkLoader.parseSinkYaml(yaml);

        assertTrue(cfg.isAgent());
        assertEquals("sinks-agent", cfg.getAgentName());
    }
}
