package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentShutdownTruthTest {
    @Test void cleanupOnlyStopMustNotClaimThreadsOrServicesAreRunning() throws Exception {
        var stop = StopResult.incomplete(null, List.of("ended-group"), List.of())
                .withHeldNames(List.of(new StopResult.HeldName("ended-group", "processor", 1, "close failed")));
        var issues = new ArrayList<Map<String, Object>>();
        var method = FailureIssues.class.getDeclaredMethod("shutdown", MongooseServer.class, StopResult.class, List.class);
        method.setAccessible(true);
        method.invoke(new FailureIssues(null), new MongooseServer(null), stop, issues);
        assertEquals(1, issues.size());
        String evidence = String.valueOf(issues.getFirst().get("evidence"));
        String impact = String.valueOf(issues.getFirst().get("impact"));
        assertAll(
                () -> assertTrue(evidence.contains("Groups with incomplete cleanup")),
                () -> assertTrue(impact.contains("StopResult lists no services left running")),
                () -> assertFalse(evidence.contains("Groups still running"), "unconfirmed cleanup is not proof of thread liveness"),
                () -> assertFalse(impact.contains("are kept running"), "servicesLeftRunning is empty; no service liveness may be claimed")
        );
    }
}
