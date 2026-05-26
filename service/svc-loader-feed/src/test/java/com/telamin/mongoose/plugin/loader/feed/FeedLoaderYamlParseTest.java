/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.loader.feed;

import com.telamin.mongoose.config.EventFeedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** YAML-snippet → EventFeedConfig round-trip. SnakeYAML must accept
 *  {@code !!FQN} tags for user-defined feed classes (the TagInspector
 *  is permissive — same rationale as in the graph YAML loader). */
class FeedLoaderYamlParseTest {

    /** Public so SnakeYAML can instantiate via JavaBean rules. */
    public static class FakeFeedSource {
        public String filename;
        public String getFilename() { return filename; }
        public void setFilename(String v) { this.filename = v; }
    }

    @Test
    void parses_name_and_instance() {
        String yaml = ""
                + "name: ticks\n"
                + "instance: !!" + FakeFeedSource.class.getName() + "\n"
                + "  filename: data/ticks.csv\n"
                + "broadcast: true\n";

        EventFeedConfig<?> cfg = FeedLoader.parseFeedYaml(yaml);

        assertEquals("ticks", cfg.getName());
        assertTrue(cfg.isBroadcast());
        assertInstanceOf(FakeFeedSource.class, cfg.getInstance());
        assertEquals("data/ticks.csv", ((FakeFeedSource) cfg.getInstance()).filename);
        assertFalse(cfg.isAgent(), "no agentName set → not an agent-hosted feed");
    }

    @Test
    void agent_section_flags_agent_hosted() {
        String yaml = ""
                + "name: ticks\n"
                + "instance: !!" + FakeFeedSource.class.getName() + " { filename: x }\n"
                + "agentName: feeds-agent\n"
                + "idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}\n";

        EventFeedConfig<?> cfg = FeedLoader.parseFeedYaml(yaml);

        assertTrue(cfg.isAgent());
        assertEquals("feeds-agent", cfg.getAgentName());
        assertNotNull(cfg.getIdleStrategy());
    }

    @Test
    void rejects_unparseable_yaml() {
        assertThrows(Exception.class,
                () -> FeedLoader.parseFeedYaml("not: : valid: yaml: at: all:"));
    }
}
