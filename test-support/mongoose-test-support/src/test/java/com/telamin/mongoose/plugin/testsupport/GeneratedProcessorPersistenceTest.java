/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.telamin.mongoose.plugin.lib.json.StateJson;
import com.telamin.mongoose.plugin.testsupport.gym.event.BookRoom;
import com.telamin.mongoose.plugin.testsupport.gym.event.CancelBooking;
import com.telamin.mongoose.plugin.testsupport.gym.event.CheckIn;
import com.telamin.mongoose.plugin.testsupport.gym.event.CheckOut;
import com.telamin.mongoose.plugin.testsupport.gym.event.Join;
import com.telamin.mongoose.plugin.testsupport.gym.event.Payment;
import com.telamin.mongoose.plugin.testsupport.gym.generated.GymProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PersistentNode in a generated processor: the generated code calls the subclasses' overridden
 * {@code @ServiceRegistered("appState")} methods and the inherited {@code @AfterEvent} save, and a JsonFileCache carries
 * the three nodes' state across a restart.
 */
class GeneratedProcessorPersistenceTest {

    @TempDir
    Path dir;

    /** The sample's feed lines, mapped by hand (connector-file's LineMapper is not on this module's class path). */
    static final Function<Object, Object> GYM_LINES = o -> {
        if (!(o instanceof String line)) return o;
        String[] f = line.split(" ");
        return switch (f[0]) {
            case "JOIN" -> new Join(f[1], f[2], f[3]);
            case "PAY" -> new Payment(f[1], Double.parseDouble(f[2]), f[3]);
            case "IN" -> new CheckIn(f[1]);
            case "OUT" -> new CheckOut(f[1]);
            case "BOOK" -> new BookRoom(f[1], f[2], f[3], f[4]);
            case "CANCEL" -> new CancelBooking(f[1]);
            default -> line;
        };
    };

    static Path resource(String name) throws URISyntaxException {
        return Path.of(GeneratedProcessorPersistenceTest.class.getResource("/gym/" + name).toURI());
    }

    /** The PersistentNode record stored under {@code key}: the cache holds it as a JSON string value. */
    private static JsonNode savedRecord(Path cache, String key) throws Exception {
        JsonNode file = StateJson.mapper().readTree(cache.toFile());
        String stringValue = StateJson.mapper().readTree(file.at("/" + key + "/data").asText()).asText();
        return StateJson.mapper().readTree(stringValue);
    }

    private FixtureRunner.Builder runner(Path cache) {
        return FixtureRunner.builder().processor(GymProcessor::new).mapper(GYM_LINES).sink("alerts").cache("appState", cache);
    }

    @Test
    void state_survives_a_restart_through_the_generated_processor() throws Exception {
        Path cache = dir.resolve("appState.json");
        FixtureRunner.Result day1 = runner(cache).feed(resource("day1.txt")).run();
        assertEquals(Files.readAllLines(resource("expected-day1.txt")), day1.sinkLines());

        for (String key : List.of("membership", "frontDesk", "bookings")) {
            assertEquals(3, savedRecord(cache, key).get("_v").asInt(), key + " saved by the inherited @AfterEvent");
        }
        JsonNode membership = savedRecord(cache, "membership");
        assertEquals(3, membership.at("/state/members").size());

        FixtureRunner.Result restarted = runner(cache).feed(resource("day1.txt")).restart(resource("day2.txt")).run();
        assertEquals(Files.readAllLines(resource("expected-all.txt")), restarted.sinkLines(),
                "day2 after a restart sees day1's members, check-ins and bookings");
    }

    @Test
    void without_the_saved_state_day2_behaves_differently() throws Exception {
        List<String> expectedAll = Files.readAllLines(resource("expected-all.txt"));
        List<String> day2Tail = new ArrayList<>(expectedAll.subList(
                Files.readAllLines(resource("expected-day1.txt")).size(), expectedAll.size()));
        FixtureRunner.Result fresh = runner(dir.resolve("fresh.json")).feed(resource("day2.txt")).run();
        assertNotEquals(day2Tail, fresh.sinkLines(), "the restart test would pass without restoring anything");
    }
}
