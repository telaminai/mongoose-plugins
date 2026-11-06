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

class FixtureRunnerTest {

    @TempDir
    Path dir;

    private Path write(String name, String... lines) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, List.of(lines));
        return p;
    }

    @Test
    void api_runs_feeds_with_a_restart_on_the_same_cache() throws Exception {
        List<String> live = new ArrayList<>();
        Path cacheFile = dir.resolve("run/counter.json");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{\"stale\":{}}");   // deleted before the first run

        FixtureRunner.Result result = FixtureRunner.builder()
                .processor(FixtureCounterFlow::new)
                .mapper(new FixtureUpperMapper())
                .sink("out")
                .cache("counterState", cacheFile)
                .feedLines(List.of("a", "# a comment", "b", "#admin count"))
                .restartLines(List.of("c", "#admin count", "#admin nothing here"))
                .onAdminReply(live::add)
                .audit(dir.resolve("logs/audit.yaml"))
                .actual(dir.resolve("logs/actual.txt"))
                .run();

        assertEquals(List.of("seen A", "{\"value\":\"A\",\"count\":1}", "seen B", "{\"value\":\"B\",\"count\":2}",
                "seen C", "{\"value\":\"C\",\"count\":3}"), result.sinkLines(), "restart resumed the count");
        assertEquals(List.of("count=2", "count=3", "nothing: no handler replied"), result.adminReplies());
        assertEquals(result.adminReplies(), live);
        assertEquals(result.sinkLines(), Files.readAllLines(dir.resolve("logs/actual.txt")));
        assertTrue(Files.exists(dir.resolve("logs/audit.yaml")));
        assertTrue(Files.readString(cacheFile).contains("\"count\""));
        assertFalse(Files.readString(cacheFile).contains("stale"));

        FixtureRunner.Comparison cmp = result.compare(List.of("seen A"));
        assertFalse(cmp.pass());
        assertEquals(List.of("line 2: got      {\"value\":\"A\",\"count\":1}\n           expected <none>"),
                cmp.differences(1));
    }

    @Test
    void command_line_pass_fail_and_failure() throws Exception {
        Path feed = write("feed.txt", "x", "#admin count");
        Path restart = write("restart.txt", "y");
        Path good = write("expected.txt", "seen X", "{\"value\":\"X\",\"count\":1}", "seen Y",
                "{\"value\":\"Y\",\"count\":2}");
        Path bad = write("bad.txt", "seen X");
        String cache = "counterState=" + dir.resolve("data/state.json");

        Run pass = cli("--processor", FixtureCounterFlow.class.getName(), "--mapper", FixtureUpperMapper.class.getName(),
                "--sink", "out", "--cache", cache, "--feed", feed.toString(), "--restart", restart.toString(),
                "--expect", good.toString());
        assertEquals(0, pass.rc, pass.out);
        assertEquals("admin> count=1\nPASS: 4 lines match " + good + "\n", pass.out);

        Run fail = cli("--processor", FixtureCounterFlowSupplier.class.getName(), "--mapper",
                FixtureUpperMapper.class.getName(), "--sink", "out", "--cache", cache, "--feed", feed.toString(),
                "--restart", restart.toString(), "--expect", bad.toString(), "--actual", dir.resolve("a.txt").toString());
        assertEquals(1, fail.rc, fail.out);
        assertTrue(fail.out.contains("FAIL: 4 lines, expected 1"), fail.out);
        assertTrue(fail.out.contains("line 2: got      {\"value\":\"X\",\"count\":1}"), fail.out);
        assertTrue(fail.out.contains("full output: " + dir.resolve("a.txt")), fail.out);

        Run print = cli("--processor", FixtureCounterFlow.class.getName(), "--sink", "out", "--cache", cache,
                "--feed", restart.toString());
        assertEquals(0, print.rc);
        assertEquals("seen y\n{\"value\":\"y\",\"count\":1}\n", print.out);

        Path boom = write("boom.txt", "ok", "BOOM");
        Run crash = cli("--processor", FixtureCounterFlow.class.getName(), "--sink", "out", "--cache", cache,
                "--feed", boom.toString());
        assertEquals(2, crash.rc);
        assertTrue(crash.out.startsWith("FIXTURE RUN FAILED: java.lang.IllegalStateException: bad event"), crash.out);
        assertTrue(crash.out.contains("at " + FixtureCounterFlow.class.getName()), crash.out);

        assertEquals(2, cli("--bogus", "x").rc);
    }

    private record Run(int rc, String out) {
    }

    private static Run cli(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream console = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int rc = FixtureRunner.run(console, args);
        return new Run(rc, bytes.toString(StandardCharsets.UTF_8));
    }
}
