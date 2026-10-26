package com.telamin.mongoose.plugin.svc.adminweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.control.ControlDriver;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Mongoose110CompatibilityTest {
    private static MongooseServer.ControlGroupStatus status(boolean degraded, Map<String, Map<String,String>> overload) {
        return new MongooseServer.ControlGroupStatus("g", ControlDriver.GroupState.RUNNING, 0, 0, 0,
                Map.of(), Map.of(), Map.of(), degraded, 0, 0, null, overload);
    }
    @Test void structuredOverloadStatesAreShownWithoutCountersAndRemovedIsNotHealthy() {
        List<Map<String,Object>> issues=new ArrayList<>();
        FailureIssues.structuredFailures(status(false, Map.of("feed", Map.of("q0","UP","q1","DEGRADED","q2","LATCHED","q3","REMOVED"))),issues);
        assertEquals(3,issues.size());
        for(var i:issues) {
            assertEquals("OVERLOAD_LOSS",i.get("failureKind"));
            assertNull(i.get("evidenceStatus"),"a live snapshot cannot prove complete historical evidence");
            assertEquals("DEGRADED".equals(i.get("overloadState"))?"DEGRADED":"CRITICAL",i.get("severity"));
        }
        assertTrue(issues.stream().anyMatch(i->"REMOVED".equals(i.get("overloadState"))));
    }
    @Test void incompleteAuditCannotBePresentedAsVerified() {
        List<Map<String,Object>> issues=new ArrayList<>();
        FailureIssues.structuredFailures(status(true,Map.of("feed",Map.of("q","LATCHED"))),issues);
        assertEquals(2,issues.size());
        assertTrue(issues.stream().allMatch(i->"UNVERIFIED".equals(i.get("evidenceStatus"))));
        assertTrue(issues.stream().anyMatch(i->"UNVERIFIED".equals(i.get("failureKind"))));
    }
    @Test void heldNamesAndShutdownKindSurviveJson() throws Exception {
        StopResult stop=StopResult.incomplete(null,List.of("g"),List.of())
                .withHeldNames(List.of(new StopResult.HeldName("g","pricer",17,"quarantined")));
        var json=new ObjectMapper().valueToTree(WebAdminService.stopResultJson(stop));
        assertEquals("FAILED_SHUTDOWN",json.get("failureKind").asText());
        assertEquals("pricer",json.get("heldNames").get(0).get("name").asText());
        assertEquals(17,json.get("heldNames").get(0).get("generation").asLong());
    }
    @Test void agentJsonPreservesTheWholeControlStatus() throws Exception {
        var status=status(true,Map.of("feed",Map.of("q","REMOVED")));
        Map<String,Object> group=new LinkedHashMap<>();group.put("members",new ArrayList<>());
        WebAdminService.applyControlState(group,status);
        var mapper=new ObjectMapper();var json=mapper.valueToTree(group);
        assertEquals(mapper.readTree(status.toJson()),mapper.readTree(mapper.writeValueAsString(json.get("controlStatus"))));
        assertEquals("REMOVED",json.get("overloadStates").get("feed").get("q").asText());
    }
    private static final FailureIssues.Sources SOURCES=new FailureIssues.Sources() {
        public MongooseServer server(){return null;}
        public Map<String,com.telamin.mongoose.service.introspection.AgentGroupSnapshot> agentGroups(){return Map.of();}
        public com.telamin.mongoose.service.health.MongooseHealthService health(){return null;}
        public com.telamin.mongoose.service.counters.MongooseCountersService counters(){return null;}
        public List<com.telamin.mongoose.service.audit.AuditSinkHandle> captures(){return List.of();}
    };
    @SuppressWarnings("unchecked")
    @Test void newStopReplyKindIsRecognizedWithoutClassifyingReasonText() {
        FailureIssues f=new FailureIssues(SOURCES);
        f.recordCommand("server.processors.stop",List.of("g/p"),List.of("stop FAILED (FAILED_DRIVER_FALLBACK): arbitrary wording"),List.of());
        List<Map<String,Object>> issues=(List<Map<String,Object>>)f.report().get("issues");
        assertEquals(1,issues.size());assertEquals("FAILED_DRIVER_FALLBACK",issues.get(0).get("failureKind"));
        assertEquals("CRITICAL",issues.get(0).get("severity"));
        assertTrue(String.valueOf(issues.get(0).get("impact")).contains("do not assume"));
        assertNull(issues.get(0).get("evidenceStatus"));
    }
    @SuppressWarnings("unchecked")
    @Test void typedInvalidStopOnErrorStreamIsStillRecorded() {
        FailureIssues f=new FailureIssues(SOURCES);
        f.recordCommand("server.processors.stop",List.of(),List.of(),
                List.of("stop REFUSED (REFUSED_INVALID): usage: server.processors.stop <group>/<processor>"));
        List<Map<String,Object>> issues=(List<Map<String,Object>>)f.report().get("issues");
        assertEquals(1,issues.size());
        assertEquals("REFUSED_INVALID",issues.get(0).get("failureKind"));
        assertEquals("REFUSED",issues.get(0).get("outcome"));
        assertEquals("Request invalid",issues.get(0).get("title"));
    }
    @Test void appliedTypedStopDoesNotCreateAnIssue() {
        FailureIssues f=new FailureIssues(SOURCES);
        f.recordCommand("server.processors.stop",List.of("g/p"),List.of("stop APPLIED (NONE): stopped"),List.of());
        assertTrue(((List<?>)f.report().get("issues")).isEmpty());
    }
}
