/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A run never writes or deletes one of its own inputs: the paths it writes (--actual, --audit, each cache) must differ
 * from each other and from the paths it reads (the feeds, --expect), checked before anything is deleted or run.
 */
class FixtureRunnerPathsTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    private int cli(String... options) {
        List<String> args = new ArrayList<>(List.of("--processor", FixtureCounterFlow.class.getName(), "--sink", "out"));
        args.addAll(List.of(options));
        if (!args.contains("--cache")) {   // the counter needs its cache: a run fails for that reason only if not given
            args.addAll(List.of("--cache", "counterState=" + dir.resolve("counter.json")));
        }
        return FixtureRunner.run(new PrintStream(console, true, StandardCharsets.UTF_8), args.toArray(String[]::new));
    }

    private String out() {
        return console.toString(StandardCharsets.UTF_8);
    }

    private Path write(String name, String text) throws Exception {
        return Files.writeString(dir.resolve(name), text);
    }

    @Test
    void actual_on_the_expected_file_is_rejected_and_leaves_the_baseline_alone() throws Exception {
        Path feed = write("feed.txt", "a\n");
        Path expected = write("expected.txt", "wrong\n");
        Path state = dir.resolve("state.json");
        int rc = cli("--cache", "counterState=" + state, "--feed", feed.toString(),
                "--expect", expected.toString(), "--actual", expected.toString());
        assertEquals(2, rc, out());
        assertTrue(out().contains("--actual") && out().contains("--expect"), out());
        assertEquals("wrong\n", Files.readString(expected), "the baseline is not overwritten");
        assertFalse(Files.exists(state), "rejected before anything ran");
    }

    @Test
    void an_alias_of_the_expected_file_is_rejected() throws Exception {
        Path feed = write("feed.txt", "a\n");
        Path expected = write("expected.txt", "wrong\n");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, expected);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "no symbolic links here: " + e);
        }
        Files.createDirectories(dir.resolve("sub"));
        String dotted = dir.resolve("sub").resolve("..").resolve("expected.txt").toString();
        assertEquals(2, cli("--feed", feed.toString(), "--expect", expected.toString(), "--actual", link.toString()), out());
        assertEquals(2, cli("--feed", feed.toString(), "--expect", expected.toString(), "--actual", dotted), out());
        Path hard = dir.resolve("hard.txt");
        try {
            Files.createLink(hard, expected);
            assertEquals(2, cli("--feed", feed.toString(), "--expect", expected.toString(), "--actual", hard.toString()),
                    out());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // no hard links on this file system: the symbolic link and the dotted path above still apply
        }
        assertEquals("wrong\n", Files.readString(expected));
    }

    @Test
    void outputs_on_a_feed_a_cache_or_each_other_are_rejected() throws Exception {
        Path feed = write("feed.txt", "a\n");
        Path state = dir.resolve("state.json");
        Path log = dir.resolve("out.txt");
        assertEquals(2, cli("--feed", feed.toString(), "--actual", feed.toString()), out());
        assertEquals(2, cli("--feed", feed.toString(), "--cache", "counterState=" + feed), out());
        assertEquals(2, cli("--feed", feed.toString(), "--cache", "counterState=" + state, "--audit", state.toString()), out());
        assertEquals(2, cli("--feed", feed.toString(), "--actual", log.toString(), "--audit", log.toString()), out());
        assertEquals(2, cli("--feed", feed.toString(), "--cache", "counterState=" + state + ",other=" + state), out());
        assertEquals("a\n", Files.readString(feed), "the feed is neither deleted nor overwritten");
        assertFalse(Files.exists(log));
    }

    @Test
    void the_builder_rejects_the_same_collisions() throws Exception {
        Path feed = write("feed.txt", "a\n");
        Path expected = write("expected.txt", "seen a\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FixtureRunner.builder()
                .processor(FixtureCounterFlow::new).sink("out").cache("counterState", feed).feed(feed).run());
        assertTrue(e.getMessage().contains(feed.getFileName().toString()), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FixtureRunner.builder()
                .processor(FixtureCounterFlow::new).sink("out").cache("counterState", dir.resolve("s.json"))
                .feed(feed).protect(expected).actual(expected).run());
        assertEquals("a\n", Files.readString(feed));
        assertEquals("seen a\n", Files.readString(expected));
    }

    @Test
    void the_expected_file_is_read_before_the_run() throws Exception {
        Path feed = write("feed.txt", "a\n");
        Path actual = dir.resolve("actual.txt");
        Path state = dir.resolve("state.json");
        int rc = cli("--cache", "counterState=" + state, "--feed", feed.toString(),
                "--expect", dir.resolve("missing.txt").toString(), "--actual", actual.toString());
        assertEquals(2, rc, out());
        assertTrue(out().contains("missing.txt"), out());
        assertFalse(Files.exists(actual), "nothing ran");
        assertFalse(Files.exists(state), "nothing ran");
    }

    @Test
    void distinct_paths_still_pass() throws Exception {
        Path feed = write("feed.txt", "a\n");
        Path expected = write("expected.txt", "seen a\n{\"value\":\"a\",\"count\":1}\n");
        int rc = cli("--cache", "counterState=" + dir.resolve("state.json"), "--feed", feed.toString(),
                "--expect", expected.toString(), "--actual", dir.resolve("actual.txt").toString(),
                "--audit", dir.resolve("audit.yaml").toString());
        assertEquals(0, rc, out());
        assertTrue(out().contains("PASS"), out());
    }
}
