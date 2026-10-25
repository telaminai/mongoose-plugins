/*
 * SPDX-FileCopyrightText: © 2026 Telamin
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.audit.AuditSinkHandle;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import com.telamin.mongoose.service.introspection.AgentGroupSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The failure facts Mongoose 1.0.33 exposes, as operator issues (docs/failure-ux-proposal.md §3-§4).
 *
 * <p>Every issue is derived on each call from typed Mongoose state ({@code controlGroupStatus},
 * {@code lastStopResult}, health checks, counters), never from a {@code toString()}. The only state kept here is
 * what this console itself saw (requests it sent that were not applied), when it first saw each issue, and
 * which issues the operator acknowledged. Acknowledging hides an issue from the "new" count only: it never
 * changes a fact, the issue stays listed and keeps counting toward the worst severity until Mongoose stops
 * reporting it (#77 "no manufactured success").
 *
 * <p>Facts 1.0.33 does not expose are listed in {@code notAvailable}, so an empty issue list is never read as
 * "healthy".
 */
final class FailureIssues {

    enum Severity { CRITICAL, DEGRADED, INFO }

    /** Where the facts come from; each accessor may return null or empty when that source is not wired. */
    interface Sources {
        MongooseServer server();
        Map<String, AgentGroupSnapshot> agentGroups();
        MongooseHealthService health();
        MongooseCountersService counters();
        List<AuditSinkHandle> captures();
    }

    /** The data-path queue naming of Mongoose 1.0.33 ({@code EventFlowManager.queuePathFor}). */
    static final Pattern QUEUE_PATH = Pattern.compile("^/feed/(.+)/group/(.+)/subscriber/(.+)$");
    static final String QUEUE_DROPS_CHECK = "queue-drops:";
    /** {@code server.processors.stop} reply line on 1.0.33: {@code stop <ReplyOutcome>: <reason>}. */
    static final Pattern STOP_REPLY = Pattern.compile("^stop (APPLIED|REFUSED|FAILED|NOT_RUNNING): (.*)$");
    static final Pattern STOP_PENDING = Pattern.compile("^stop pending \\(still applies\\): (.*)$");
    static final int MAX_REQUESTS = 20;

    private final Sources sources;
    private final Set<String> acknowledged = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> requests = new ArrayDeque<>();
    private final AtomicLong requestSeq = new AtomicLong();

    FailureIssues(Sources sources) {
        this.sources = sources;
    }

    // ------------------------------------------------------------------ report

    /** {@code GET /api/issues}. */
    Map<String, Object> report() {
        List<Map<String, Object>> issues = new ArrayList<>();
        MongooseServer server = sources.server();
        StopResult stop = server == null ? null : server.lastStopResult();
        boolean stopping = stop != null && !stop.complete();
        if (server != null) {
            for (String group : groupNames(server)) {
                MongooseServer.ControlGroupStatus st;
                try {
                    st = server.controlGroupStatus(group);
                } catch (IllegalArgumentException notAProcessorGroup) {
                    continue;
                }
                quarantined(server, st, issues);
                dead(server, st, issues);
                captureFailures(st, stopping, issues);
            }
            shutdown(server, stop, issues);
        }
        health(issues);
        synchronized (requests) {
            for (Map<String, Object> r : requests) issues.add(new LinkedHashMap<>(r));
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Severity s : Severity.values()) counts.put(s.name(), 0);
        Severity worst = null;
        int unacknowledged = 0;
        long now = System.currentTimeMillis();
        Set<String> live = new java.util.HashSet<>();
        for (Map<String, Object> issue : issues) {
            String id = (String) issue.get("id");
            live.add(id);
            issue.putIfAbsent("since", firstSeen.computeIfAbsent(id, k -> now));
            boolean acked = acknowledged.contains(id);
            issue.put("acknowledged", acked);
            if (!acked) unacknowledged++;
            Severity s = Severity.valueOf((String) issue.get("severity"));
            counts.merge(s.name(), 1, Integer::sum);
            if (worst == null || s.ordinal() < worst.ordinal()) worst = s;
        }
        // an issue Mongoose stopped reporting is gone; forget its acknowledgement so a recurrence is new again
        firstSeen.keySet().retainAll(live);
        acknowledged.retainAll(live);
        issues.sort((a, b) -> {
            int c = Integer.compare(Severity.valueOf((String) a.get("severity")).ordinal(),
                    Severity.valueOf((String) b.get("severity")).ordinal());
            return c != 0 ? c : Long.compare((Long) a.get("since"), (Long) b.get("since"));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worst", worst == null ? null : worst.name());
        out.put("counts", counts);
        out.put("unacknowledged", unacknowledged);
        out.put("issues", issues);
        out.put("notAvailable", notAvailable(server != null));
        return out;
    }

    /** {@code POST /api/issues/ack}: true when the issue is open. Never changes a Mongoose fact. */
    boolean acknowledge(String id) {
        if (id == null) return false;
        boolean open = firstSeen.containsKey(id);
        if (open) acknowledged.add(id);
        return open;
    }

    // ------------------------------------------------------------------ server facts

    private Collection<String> groupNames(MongooseServer server) {
        Set<String> names = new java.util.TreeSet<>();
        Map<String, AgentGroupSnapshot> snaps = sources.agentGroups();
        if (snaps != null) names.addAll(snaps.keySet());
        names.addAll(server.registeredProcessors().keySet());
        return names;
    }

    private void quarantined(MongooseServer server, MongooseServer.ControlGroupStatus st, List<Map<String, Object>> issues) {
        String group = st.group();
        st.quarantinedProcessors().forEach((name, reason) -> {
            String subject = group + "/" + name;
            Map<String, Object> issue = issue("PROCESSOR_QUARANTINED:" + subject, "PROCESSOR_QUARANTINED",
                    Severity.CRITICAL, subject);
            issue.put("group", group);
            issue.put("processor", name);
            issue.put("title", "Processor quarantined");
            issue.put("what", "The generated processor threw while handling an event. Mongoose fenced it from its"
                    + " feeds and timers and will not call it again. Reason: " + reason);
            List<Map<String, Object>> evidence = new ArrayList<>();
            evidence.add(evidence("Group status", "control.group.status " + group + ": quarantinedProcessors has " + name));
            evidence.add(evidence("Reason", reason));
            captureEvidence(subject, evidence);
            issue.put("evidence", evidence);
            issue.put("evidenceUnavailable", List.of(
                    "The ProcessorQuarantined control record (sequence, time) is only in a control-audit export on"
                            + " Mongoose 1.0.33: run Export evidence."));
            List<String> peers = peers(server, group, name);
            issue.put("impact", "no output from " + subject + " until the process is replaced: it receives no events"
                    + " and no timers. " + (peers.isEmpty() ? "It is the only processor in group " + group + "."
                    : "Other processors in group " + group + " keep running (" + String.join(", ", peers) + ").")
                    + " Shutdown will report " + group + " incomplete and keep the services it uses.");
            List<Map<String, Object>> actions = new ArrayList<>();
            actions.add(ack());
            actions.add(exportEvidence(group));
            actions.add(disabled("stop", "Stop",
                    "Known outcome on Mongoose 1.0.33: stop FAILED: processor quarantined. The name stays reserved"
                            + " until shutdown."));
            actions.add(disabled("replace", "Replace / re-add",
                    "Not offered: Mongoose keeps the quarantined name and instance reserved and has no audited"
                            + " release (proposal A6). Replace the process instead."));
            issue.put("actions", actions);
            issues.add(issue);
        });
    }

    private void dead(MongooseServer server, MongooseServer.ControlGroupStatus st, List<Map<String, Object>> issues) {
        if (st.state() != ControlDriver.GroupState.DEAD) return;
        String group = st.group();
        Map<String, Object> issue = issue("GROUP_DEAD:" + group, "GROUP_DEAD", Severity.CRITICAL, group);
        issue.put("group", group);
        issue.put("title", "Processor group dead");
        issue.put("what", "The group's agent thread ended abnormally. Mongoose admits no more requests for it and"
                + " runs none of its processors.");
        List<Map<String, Object>> evidence = new ArrayList<>();
        evidence.add(evidence("Group state", "control.group.status " + group + ": state DEAD"));
        AgentGroupSnapshot snap = sources.agentGroups() == null ? null : sources.agentGroups().get(group);
        if (snap != null) evidence.add(evidence("Thread", snap.threadName() + " " + snap.threadState()));
        issue.put("evidence", evidence);
        issue.put("evidenceUnavailable", List.of(
                "The Dead control record and the error that ended the thread are only in a control-audit export and"
                        + " the server log on Mongoose 1.0.33.",
                "With Mongoose's default error handler a fatal group error exits the JVM: this console only sees a"
                        + " DEAD group when the application installs its own handler."));
        List<String> members = memberNames(server, group);
        issue.put("impact", "No processor in " + group + " runs" + (members.isEmpty() ? "." : ": " + String.join(", ", members)
                + " are listed but not running.") + " Requests to the group are answered NOT_RUNNING. Other groups keep"
                + " running.");
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(ack());
        actions.add(exportEvidence(group));
        actions.add(manual("restart-process", "Restart",
                "Mongoose 1.0.33 cannot restart a dead group. Replace the process under your incident policy."));
        issue.put("actions", actions);
        issues.add(issue);
    }

    private void captureFailures(MongooseServer.ControlGroupStatus st, boolean stopping, List<Map<String, Object>> issues) {
        String group = st.group();
        Severity severity = stopping ? Severity.CRITICAL : Severity.DEGRADED;
        Map<Long, String> cleanup = st.captureCleanupFailures();
        st.captureCloseFailures().forEach((resource, reason) -> {
            Map<String, Object> issue = captureIssue("CAPTURE_CLOSE_FAILED", group, resource, reason, severity, st,
                    "A capture's physical close failed. Mongoose keeps the resource and its slot and never retries"
                            + " the close.");
            // the same failed close is usually also a cleanup failure of that resource: one issue, both facts
            String alsoCleanup = cleanup.get(resource);
            if (alsoCleanup != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> evidence = new ArrayList<>((List<Map<String, Object>>) issue.get("evidence"));
                evidence.add(evidence("Cleanup failure", alsoCleanup));
                issue.put("evidence", evidence);
            }
            issues.add(issue);
        });
        cleanup.forEach((resource, reason) -> {
            if (st.captureCloseFailures().containsKey(resource)) return;
            issues.add(captureIssue("CAPTURE_CLEANUP_FAILED", group, resource, reason, severity, st,
                    "Cleaning up a capture failed. Mongoose keeps the resource owned and reports it at shutdown."));
        });
    }

    private Map<String, Object> captureIssue(String kind, String group, long resource, String reason, Severity severity,
                                             MongooseServer.ControlGroupStatus st, String what) {
        String subject = group + " resource " + resource;
        Map<String, Object> issue = issue(kind + ":" + group + ":" + resource, kind, severity, subject);
        issue.put("group", group);
        issue.put("resource", resource);
        issue.put("title", kind.equals("CAPTURE_CLOSE_FAILED") ? "Capture close failed" : "Capture cleanup failed");
        issue.put("what", what + " Reason: " + reason);
        issue.put("evidence", List.of(
                evidence("Resource", "group " + group + ", resource id " + resource),
                evidence("Reason", reason),
                evidence("Capture slots in use", st.captureResourceSlotsInUse() + " (resources held "
                        + st.captureResourcesHeld() + ")")));
        issue.put("evidenceUnavailable", List.of(
                "Which capture (processor and file) resource " + resource + " belongs to: Mongoose 1.0.33 has no"
                        + " resource ledger (proposal A2). audit.stop reported STOPPED and the file is listed as an"
                        + " ordinary finished capture.",
                "The capture resource limit: Mongoose 1.0.33 has no getter (proposal A5)."));
        issue.put("impact", "The slot stays held until the process is replaced: with a capture resource limit, later"
                + " capture starts in " + group + " can be refused. The capture file may be incomplete. Shutdown will"
                + " report " + group + " incomplete.");
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(ack());
        Map<String, Object> verify = action("open-captures", "Verify capture files", true,
                "None.", "Opens the capture file list; check the files of " + group + " for truncation.");
        actions.add(verify);
        actions.add(disabled("retry-close", "Retry close",
                "Not offered: Mongoose deliberately never retries a physical close. Replace the process to release"
                        + " the slot."));
        issue.put("actions", actions);
        return issue;
    }

    private void shutdown(MongooseServer server, StopResult stop, List<Map<String, Object>> issues) {
        if (stop == null || stop.complete()) return;
        if (stop.handedOff()) {
            Map<String, Object> issue = issue("SHUTDOWN_HANDED_OFF", "SHUTDOWN_HANDED_OFF", Severity.INFO, "server");
            issue.put("title", "Shutdown continuing on the stop thread");
            issue.put("what", "shutdown() was called from a thread that may not wait; Mongoose continues it on its"
                    + " stop thread.");
            issue.put("evidence", List.of(evidence("StopResult", "handedOff=true")));
            issue.put("impact", "The outcome is not known yet.");
            issue.put("actions", List.of(ack()));
            issues.add(issue);
            return;
        }
        Map<String, Object> issue = issue("SHUTDOWN_INCOMPLETE", "SHUTDOWN_INCOMPLETE", Severity.CRITICAL, "server");
        issue.put("title", "Shutdown incomplete");
        issue.put("what", "The last shutdown returned incomplete. The server stays STOPPING with the groups and"
                + " services below still running.");
        List<Map<String, Object>> evidence = new ArrayList<>();
        evidence.add(evidence("Groups still running", String.join(", ", stop.groupsRunning())));
        evidence.add(evidence("Services left running", String.join(", ", stop.servicesLeftRunning())));
        if (stop.blockedStartup() != null) evidence.add(evidence("Blocked startup", stop.blockedStartup()));
        for (String group : stop.groupsRunning()) {
            try {
                var st = server.controlGroupStatus(group);
                evidence.add(evidence("Group " + group, "state " + st.state() + ", outstanding requests "
                        + st.outstandingRequests()));
            } catch (IllegalArgumentException ignored) {
                // not a processor group
            }
        }
        issue.put("evidence", evidence);
        issue.put("evidenceUnavailable", List.of(
                "Which callback is blocked: Mongoose 1.0.33 reports the group, not the processor or callback."));
        issue.put("impact", "Groups " + String.join(", ", stop.groupsRunning()) + " have not stopped; services they"
                + " may use are kept running (this console is one of them). Their resources stay held.");
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(ack());
        actions.add(disabled("retry-stop", "Retry stop",
                "No admin command on Mongoose 1.0.33 (proposal A4). The application can call shutdown() again once"
                        + " the blocker can progress: it resumes without repeating callbacks, and failed closes are"
                        + " not retried."));
        issue.put("actions", actions);
        issues.add(issue);
    }

    // ------------------------------------------------------------------ health and queues

    private void health(List<Map<String, Object>> issues) {
        MongooseHealthService health = sources.health();
        if (health == null) return;
        Map<String, Long> drops = dropCounts();
        try {
            health.forEachStatus((component, check, status) -> {
                if (status == null) return;
                HealthStatus.Verdict v = status.verdict();
                if (v != HealthStatus.Verdict.DEGRADED && v != HealthStatus.Verdict.DOWN) return;
                if (check.startsWith(QUEUE_DROPS_CHECK)) {
                    issues.add(queueLoss(component, check.substring(QUEUE_DROPS_CHECK.length()), status, drops));
                } else {
                    issues.add(healthIssue(component, check, status));
                }
            });
        } catch (RuntimeException e) {
            // a health check that throws is reported by Mongoose as UNKNOWN; a visitor failure must not hide the rest
        }
    }

    private Map<String, Object> queueLoss(String feed, String path, HealthStatus status, Map<String, Long> drops) {
        Matcher m = QUEUE_PATH.matcher(path);
        String group = m.matches() ? m.group(2) : null;
        String source = m.matches() ? m.group(1) : feed;
        String subject = group == null ? path : source + " → " + group;
        Map<String, Object> issue = issue("QUEUE_LOSS:" + path, "QUEUE_LOSS",
                status.verdict() == HealthStatus.Verdict.DOWN ? Severity.CRITICAL : Severity.DEGRADED, subject);
        issue.put("feed", source);
        if (group != null) issue.put("group", group);
        issue.put("queue", path);
        Long dropped = drops.get(path);
        issue.put("dropped", dropped);
        issue.put("title", "Queue loss");
        issue.put("what", "A full queue dropped items after its spin bound. Health " + status.verdict() + ": "
                + status.reason());
        List<Map<String, Object>> evidence = new ArrayList<>();
        evidence.add(evidence("Dropped", dropped == null ? "count not available (counters disabled)" : String.valueOf(dropped)));
        evidence.add(evidence("Health check", QUEUE_DROPS_CHECK + path + " " + status.verdict()));
        evidence.add(evidence("Queue", path));
        issue.put("evidence", evidence);
        issue.put("evidenceUnavailable", List.of(
                "The first loss (sequence and time of the QueueDrop record) is only in the group's control-audit"
                        + " export on Mongoose 1.0.33: run Export evidence."));
        issue.put("impact", (dropped == null ? "Items" : dropped + " item(s)") + " from feed " + source
                + (group == null ? "" : " were not delivered to group " + group + "; its processors ran without them")
                + ". Acceptable only for data where the next update supersedes the last.");
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(ack());
        if (group != null) actions.add(exportEvidence(group));
        actions.add(disabled("clear", "Clear",
                "No reset on Mongoose 1.0.33: the verdict stays DEGRADED while the drop count is non-zero."
                        + " Clearing never restores lost items."));
        issue.put("actions", actions);
        return issue;
    }

    private Map<String, Object> healthIssue(String component, String check, HealthStatus status) {
        String subject = component + "/" + check;
        boolean down = status.verdict() == HealthStatus.Verdict.DOWN;
        Map<String, Object> issue = issue((down ? "HEALTH_DOWN:" : "HEALTH_DEGRADED:") + subject,
                down ? "HEALTH_DOWN" : "HEALTH_DEGRADED", down ? Severity.CRITICAL : Severity.DEGRADED, subject);
        issue.put("title", "Health check " + status.verdict());
        issue.put("what", "Health check " + check + " of " + component + " reports " + status.verdict() + ": "
                + status.reason());
        issue.put("evidence", List.of(evidence("Health check", subject + " " + status.verdict()),
                evidence("Reason", String.valueOf(status.reason()))));
        issue.put("impact", "As described by the check's owner: " + status.reason());
        issue.put("actions", List.of(ack()));
        return issue;
    }

    /** Drop count per queue path, from the {@code queue.<path>.dropped} counters. */
    Map<String, Long> dropCounts() {
        MongooseCountersService counters = sources.counters();
        if (counters == null || !counters.isOperational()) return Collections.emptyMap();
        Map<String, Long> out = new ConcurrentHashMap<>();
        counters.forEachCounter((id, label, value) -> {
            if (label != null && label.startsWith("queue.") && label.endsWith(".dropped")) {
                out.put(label.substring("queue.".length(), label.length() - ".dropped".length()), value);
            }
        });
        return out;
    }

    /** Health verdict per queue path, from the {@code queue-drops:<path>} checks. */
    Map<String, HealthStatus> queueHealth() {
        MongooseHealthService health = sources.health();
        if (health == null) return Collections.emptyMap();
        Map<String, HealthStatus> out = new ConcurrentHashMap<>();
        try {
            health.forEachStatus((component, check, status) -> {
                if (check.startsWith(QUEUE_DROPS_CHECK) && status != null) {
                    out.put(check.substring(QUEUE_DROPS_CHECK.length()), status);
                }
            });
        } catch (RuntimeException ignored) {
            // see health()
        }
        return out;
    }

    // ------------------------------------------------------------------ requests this console sent

    /**
     * Records a control request run through this console that Mongoose did not apply. {@code out}/{@code err}
     * are the admin command's reply lines; only the documented 1.0.33 reply forms are read.
     */
    void recordCommand(String name, List<String> args, List<String> out, List<String> err) {
        String target = args == null || args.isEmpty() ? null : args.get(0);
        switch (name) {
            case "server.processors.stop" -> {
                for (String line : err) {
                    if (line.startsWith("exception:") || line.startsWith("usage:")) {
                        record("REQUEST_REFUSED", "INVALID", name, target, line);
                        return;
                    }
                }
                for (String line : out) {
                    Matcher reply = STOP_REPLY.matcher(line);
                    if (reply.matches()) {
                        String outcome = reply.group(1);
                        if (!outcome.equals("APPLIED")) {
                            record(outcome.equals("FAILED") ? "REQUEST_FAILED" : "REQUEST_REFUSED", outcome, name,
                                    target, reply.group(2));
                        }
                        return;
                    }
                    Matcher pending = STOP_PENDING.matcher(line);
                    if (pending.matches()) {
                        String selector = pending.group(1);
                        if (held(selector)) {
                            record("REQUEST_UNCONFIRMED", "PENDING", name, selector,
                                    "the group did not answer within 1 s; the stop still applies");
                        } else {
                            record("REQUEST_UNCONFIRMED", "UNKNOWN_TARGET", name, selector,
                                    "no processor group lists " + selector + "; Mongoose 1.0.33 still reports it as"
                                            + " pending");
                        }
                        return;
                    }
                }
            }
            case "audit.start", "audit.stop" -> {
                if (!err.isEmpty()) {
                    String line = err.get(0);
                    record("REQUEST_REFUSED", line.startsWith("usage:") ? "INVALID" : "REFUSED", name, target,
                            line.startsWith("audit: ") ? line.substring("audit: ".length()) : line);
                }
            }
            default -> { }
        }
    }

    /** Records a capture start/stop refused through {@code POST /api/audit/{selector}/start|stop}. */
    void recordCaptureRefusal(String verb, String selector, RuntimeException e) {
        record("REQUEST_REFUSED", e instanceof IllegalArgumentException ? "INVALID" : "REFUSED", "audit." + verb,
                selector, String.valueOf(e.getMessage()));
    }

    private boolean held(String selector) {
        MongooseServer server = sources.server();
        if (server == null || selector == null) return true; // cannot tell: do not claim it is unknown
        int slash = selector.indexOf('/');
        if (slash < 0) return false;
        String group = selector.substring(0, slash);
        String name = selector.substring(slash + 1);
        Collection<NamedEventProcessor> procs = server.registeredProcessors().get(group);
        if (procs != null && procs.stream().anyMatch(p -> name.equals(p.name()))) return true;
        try {
            return server.controlGroupStatus(group).quarantinedProcessors().containsKey(name);
        } catch (IllegalArgumentException unknownGroup) {
            return false;
        }
    }

    private void record(String kind, String outcome, String command, String target, String reason) {
        long seq = requestSeq.incrementAndGet();
        String subject = target == null ? command : target;
        Map<String, Object> issue = issue(kind + ":" + seq, kind, Severity.INFO, subject);
        issue.put("since", System.currentTimeMillis());
        issue.put("outcome", outcome);
        issue.put("command", command);
        issue.put("title", switch (outcome) {
            case "PENDING" -> "Request not confirmed";
            case "UNKNOWN_TARGET" -> "Request for an unknown name";
            case "INVALID" -> "Request invalid";
            case "FAILED" -> "Request failed";
            case "NOT_RUNNING" -> "Request not run: group not running";
            default -> "Request refused";
        });
        issue.put("what", command + (target == null ? "" : " " + target) + " was not applied ("
                + outcome + "): " + reason);
        issue.put("evidence", List.of(evidence("Reply", outcome + ": " + reason),
                evidence("Sent by", "this console")));
        issue.put("impact", hint(outcome, reason));
        issue.put("actions", List.of(ack()));
        firstSeen.put((String) issue.get("id"), (Long) issue.get("since"));
        synchronized (requests) {
            requests.addFirst(issue);
            while (requests.size() > MAX_REQUESTS) {
                requests.removeLast();
            }
        }
    }

    static String hint(String outcome, String reason) {
        String r = reason == null ? "" : reason;
        if (outcome.equals("UNKNOWN_TARGET")) {
            return "Most likely nothing was stopped. Check the name (group/processor); Mongoose 1.0.33 cannot refuse"
                    + " an unknown name.";
        }
        if (outcome.equals("PENDING")) return "The stop may still happen; refresh to see whether the processor is gone.";
        if (r.contains("mailbox full") || r.contains("queue stayed full")) {
            return "Nothing changed. The group's thread is not draining its control mailbox: check its thread state"
                    + " on Agents before retrying.";
        }
        if (r.contains("not admitted") || r.contains("cancelled")) return "Nothing changed: the group did not take the request in time.";
        if (r.contains("deadline") || r.contains("timed out")) return "Nothing changed: the request's deadline passed before the group answered.";
        if (r.contains("ambiguous")) return "Nothing changed. Use a qualified group/name selector.";
        if (r.contains("resource limit")) return "Nothing changed. A capture slot is held; see any capture close failure issue.";
        if (r.contains("quarantined")) return "Expected for a quarantined processor; see its quarantine issue.";
        if (r.contains("DEAD")) return "The group is dead; see its dead-group issue.";
        if (outcome.equals("INVALID")) return "Nothing changed: the request was malformed.";
        return "Nothing changed.";
    }

    // ------------------------------------------------------------------ what 1.0.33 cannot show

    static List<Map<String, Object>> notAvailable(boolean serverWired) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!serverWired) {
            out.add(gap("Group control status", "This server is not a MongooseServer: quarantine, dead groups,"
                    + " capture failures and shutdown results cannot be read.", "MongooseServer"));
        }
        out.add(gap("Handwritten handler failures", "Mongoose 1.0.33 logs a WARNING and retries the handler; no admin"
                + " surface reports it. See Logs.", "Mongoose #77"));
        out.add(gap("Control record evidence", "Sequence numbers of ProcessorQuarantined, Dead and QueueDrop records"
                + " are only in a control-audit export.", "proposal A3"));
        out.add(gap("First loss per queue", "Only the drop count and health verdict are live; the first QueueDrop"
                + " record is in the control-audit export.", "proposal A3"));
        out.add(gap("Failed close to capture", "A failed physical close is reported by resource id; the capture it"
                + " belongs to is not.", "proposal A2"));
        out.add(gap("Capture resource limit", "The configured limit cannot be read.", "proposal A5"));
        out.add(gap("Failure kind", "Replies carry an outcome and reason text, no typed failure kind.", "Mongoose #77"));
        out.add(gap("Retry an incomplete shutdown", "No admin command resumes shutdown().", "proposal A4"));
        out.add(gap("Overload policy and latch", "Queues drop after the spin bound; no policy, latch or clear.",
                "Mongoose #79"));
        out.add(gap("Replay status", "No admin command or endpoint.", "proposal A9"));
        return out;
    }

    private static Map<String, Object> gap(String fact, String detail, String needs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fact", fact);
        m.put("detail", detail);
        m.put("needs", needs);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private void captureEvidence(String selector, List<Map<String, Object>> evidence) {
        List<AuditSinkHandle> captures = sources.captures();
        if (captures == null) return;
        for (AuditSinkHandle h : captures) {
            if (selector.equals(h.processorName()) && h.id() != null) {
                Map<String, Object> e = evidence("Capture", (h.isLive() ? "recording " : "")
                        + (h.path() == null ? h.id() : h.path().toString()));
                e.put("href", "/api/audit/file/" + java.net.URLEncoder.encode(h.id(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20") + "/export");
                e.put("hrefLabel", "Download for the analyser");
                evidence.add(e);
            }
        }
    }

    private static List<String> peers(MongooseServer server, String group, String name) {
        List<String> out = new ArrayList<>(memberNames(server, group));
        out.remove(name);
        return out;
    }

    private static List<String> memberNames(MongooseServer server, String group) {
        Collection<NamedEventProcessor> procs = server.registeredProcessors().get(group);
        if (procs == null) return List.of();
        List<String> out = new ArrayList<>();
        for (NamedEventProcessor p : procs) out.add(p.name());
        return out;
    }

    private static Map<String, Object> issue(String id, String kind, Severity severity, String subject) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("severity", severity.name());
        m.put("subject", subject);
        // Mongoose #77 adds a typed FailureKind; 1.0.33 has none, so the field is present and null
        m.put("failureKind", null);
        return m;
    }

    private static Map<String, Object> evidence(String label, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> action(String id, String label, boolean enabled, String precondition,
                                              String consequence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", id);
        m.put("label", label);
        m.put("enabled", enabled);
        m.put("precondition", precondition);
        m.put("consequence", consequence);
        return m;
    }

    private static Map<String, Object> ack() {
        return action("acknowledge", "Acknowledge", true, "None.",
                "Moves it to Acknowledged. The issue stays open and keeps counting toward the health pill until"
                        + " Mongoose stops reporting it; nothing on the server changes.");
    }

    private static Map<String, Object> exportEvidence(String group) {
        Map<String, Object> m = action("export-evidence", "Export evidence", true,
                "Names a directory that does not exist yet, on the server host (relative paths resolve against the"
                        + " server's working directory). One export runs at a time.",
                "Writes group " + group + "'s control-audit records for offline analysis and reports completeness."
                        + " Changes no server state.");
        m.put("command", "control.audit.export");
        m.put("args", List.of(group));
        m.put("statusCommand", "control.audit.export.status");
        return m;
    }

    private static Map<String, Object> disabled(String id, String label, String reason) {
        Map<String, Object> m = action(id, label, false, reason, null);
        m.put("disabledReason", reason);
        return m;
    }

    private static Map<String, Object> manual(String id, String label, String text) {
        Map<String, Object> m = action(id, label, false, text, null);
        m.put("manual", true);
        m.put("disabledReason", text);
        return m;
    }
}
