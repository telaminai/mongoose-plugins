# Failure UX for svc-admin-web: issues and remediation

Status: proposal. Validated against Mongoose **1.0.33-rc1** (`feature/control-plane` dfbfdd9, PR #76) on
2026-10-25. Issue #77 (FailureKind) and #79 (overload policy) are design-only; §6 covers what they add.

Every claim in §1 is pinned by a test in `src/test/java/.../it/`: the current behaviour in
`AdminRestScenarioIT` / `AdminWebUiScenarioIT`, the behaviour proposed here in `AdminProposedIT` /
`AdminWebUiProposedIT` (tag `proposed`, failing until implemented). See `testing-admin-web-it.md`.

Status legend used below. **[rc1]**: works today on rc1. **[plugin]**: needs a plugin change only.
**[mongoose]**: needs a Mongoose API addition, listed in §7.

## 1. What an operator sees today (rc1)

| # | Fact | REST today | UI today | Class |
|---|------|------------|----------|-------|
| a1 | Generated processor quarantined | `control.group.status <g>` toString has `quarantinedProcessors={name=reason}`; `/api/agents` and `server.processors.list` **omit** it | Processor row vanishes from Overview/Agents; header stays green "Live" | shown but misleading |
| a2 | Handwritten handler throws | nothing (log WARNING only); handler retried | nothing outside Logs | missing from REST and UI |
| a3 | Stop of quarantined processor | `stop FAILED: processor quarantined; lifecycle cleanup requires operator resolution` | same text in Commands output | shown correctly |
| b | Dead group | `control.group.status` says `state=DEAD`; `/api/agents` lists its processors as normal, `threadState: TERMINATED` | Agent card looks normal; detail shows `TERMINATED` | shown but misleading |
| b' | Dead group with the **default** error handler | `GlobalErrorHandler` calls `System.exit(-1)`: no surface at all | process gone | not reachable |
| c | Incomplete `shutdown()` / `StopResult` | not exposed anywhere; `/api/agents` shows the CLOSING group as `members: []`, `threadState: NEW` | Overview: "no processors"; header green | missing from REST and UI |
| d1 | Failed physical capture close | `control.group.status` has `captureResourceSlotsInUse`, `captureCloseFailures`; `audit.stop` says `STOPPED`; `audit.list`, `/api/audit/files` list it as a normal finished capture | Audit pill "off", file count 1, no failure | shown but misleading |
| d2 | Capture resource limit hit | `audit.start` err `open failed: physical capture resource limit reached`; `POST /api/audit/{p}/start` was **500** (fixed here: 409 + reason) | Commands err text | shown correctly (after fix) |
| e | Queue drops / health / first loss | `/api/queues` no drop field; `queue-drops:<path>` DEGRADED health check not exposed; first-loss `QueueDrop` record only inside a control-audit export | raw `queue.<path>.dropped` row on **App counters**, no severity | drop count shown but misleading; health and first loss missing from REST |
| f1 | Mailbox full refusal | `stop REFUSED: group busy: processor-stop queue stayed full (4096) for 100 ms; ...` | Commands output | shown correctly |
| f2 | Stop of a name nothing holds | `stop pending (still applies): nosuch/x` (Mongoose admin) | same | shown but misleading |
| f3 | Admin argument errors | `server.processors.stop` with no args → `exception: Index 1 out of bounds` | Commands err | shown but misleading |
| f4 | Capture start not taken in time | `audit: ... cancelled, and nothing changed` | Commands err | shown correctly |
| g1 | Ambiguous bare capture name | `processor 'pricer' is ambiguous: use one of [gA/pricer, gB/pricer]` (start 404, stop was 500, fixed) | Commands err | shown correctly |
| g2 | Qualified selector over REST | `POST /api/audit/gB%2Fpricer/start` works; raw `/gB/pricer/` 404 | Overview/Replay matched bare names only: never "recording" (fixed here) | shown correctly (after fix) |
| h | Replay status | no command, no endpoint (`MongooseServer.replayers()` only) | the "Replay" tab is the audit-file stepper, not Mongoose replay | not reachable |
| x | Control-audit completeness | `control.audit.export` + `.status` print `completeness=COMPLETE/INCOMPLETE` text | Commands only | shown, unstructured |

Defects fixed on this branch (minimal, with tests): audit start/stop refusals map to 404/409 with `err`
(were 500); Overview audit card parsed `/api/audit/files` as `{files}` (it is an array) and so always showed
0 recording; Overview/Replay match the qualified `group/name` selector; Replay rows keyed by bare name threw
an Alpine error when two groups share a name.

## 2. Principles

1. **Evidence over status.** Every issue links to the record that proves it (control record `seq`/`rootId`,
   resource id, first-loss sequence) and to the analyser.
2. **No manufactured success** (#77). Acknowledge hides noise; it never changes a fact. No action clears a
   quarantine, marks cleanup done, or turns an incomplete `StopResult` complete. A quarantined processor or a
   failed close stays an open issue until Mongoose reports it gone.
3. **Remediation states its precondition and consequence**, and is disabled, with the reason, when the
   precondition fails.
4. **Structured, not parsed.** The UI must not parse `toString()` (the shutdown runbook forbids it); §7 asks
   for JSON forms.

## 3. Health and issues panel [plugin + mongoose]

A strip at the top of every view, and an Issues view grouped by severity with drill-down by group,
processor and feed.

```
+------------------------------------------------------------------------------+
| ● DEGRADED   2 critical  1 degraded  0 info          [Issues ▸]  ack'd: 1    |
+------------------------------------------------------------------------------+
 Issues                                    group ▾  processor ▾  feed ▾  kind ▾
 CRITICAL
  ■ pricing/pricer   PROCESSOR_QUARANTINED   since 19:48:47   seq 41   [open]
  ■ server           SHUTDOWN_INCOMPLETE     groupsRunning [cap]       [open]
 DEGRADED
  ▲ feed → slow      QUEUE_LOSS   76 dropped, first at seq 1026        [open]
 ACKNOWLEDGED (still open)
  ▲ cap              CAPTURE_CLOSE_FAILED  resource 1                  [open]
```

Severity: CRITICAL = no output or no clean stop (quarantine, dead group, incomplete stop, failed close
during shutdown); DEGRADED = loss or reduced guarantees (queue loss, audit INCOMPLETE, failed close while
running); INFO = refusals and timeouts the operator caused.

Header pill rule: worst open issue, acknowledged or not. Today it says "Live" whatever happens.

## 4. Issue cards

Each card: **what happened**, **evidence**, **impact**, **actions** (precondition → consequence).

```
+-- CRITICAL  PROCESSOR_QUARANTINED  pricing/pricer ---------------------------+
| What     Generated graph threw on dispatch; fenced from feeds and timers.    |
|          ProcessorDispatchException: generated processor execution failed... |
| Evidence ProcessorQuarantined control record (group pricing, seq 41)         |
|          [Export control audit]  [Open in analyser at seq 41]                 |
| Impact   No output from pricing/pricer. Peers in 'pricing' keep running.     |
|          Shutdown will report incomplete; dependent services retained.       |
| Actions  [Acknowledge]   hides from the strip; issue stays open              |
|          [Export evidence] control.audit.export pricing <dir>               |
|          [Replace...]  disabled: Mongoose keeps the name reserved (§7 A6)     |
+------------------------------------------------------------------------------+
```

### 4.1 Quarantined generated processor
Impact text from the processor's group and outputs ("no quotes from it"). Keep the row on Overview/Agents,
badged `QUARANTINED` [plugin, from `controlGroupStatus`; mongoose A1 for a typed reason]. Actions:
acknowledge [plugin]; export evidence (`control.audit.export`) [rc1]; open the analyser at the
`ProcessorQuarantined` record [mongoose A3 for the seq]; replace/re-add: **not offered** on rc1 because the
name stays owned and no API releases it; becomes available only with A6. Stop is shown with its known
outcome (`FAILED`) rather than as a button.

### 4.2 Dead group
Badge the group `DEAD` (from `ControlGroupStatus.state`) and grey its processors with "not running" [plugin].
Actions: export control audit [rc1]; restart = process restart under incident policy (text, not a button).
Document that the default `GlobalErrorHandler` exits the JVM, so the UI only ever sees DEAD with a custom
handler [mongoose A7 for an opt-out that keeps the process].

### 4.3 Incomplete shutdown
While services outlive a stuck group the web admin is still up, so this is visible: banner with the last
`StopResult` (`groupsRunning`, `servicesLeftRunning`, `blockedStartup`, bracketed notes) and lifecycle
`STOPPING` [plugin: `MongooseServer.lastStopResult()` is public]. Action **Retry stop** (`shutdown()`
again; precondition: the blocker can progress; consequence: resumes, never repeats callbacks; failed closes
and failed service stops are not retried) [mongoose A4, a `server.shutdown.retry` admin command with a
structured result]. Never "force complete".

### 4.4 Capture close failure and resource limit
Overview pill `close failed`; Audit list marks the session `CLOSE_FAILED` with resource id and error;
show `captureResourceSlotsInUse / limit` [plugin for slots; mongoose A2 to join resource id ↔ capture id,
A5 for the limit value]. Actions: verify files (open the directory/analyser on the capture id) [rc1];
"Replace the failed close" is not offered: Mongoose deliberately never retries a physical close; the
remediation is evidence + process replacement. Limit reached: show the refusal reason and which slots hold
it [rc1 after the 409 fix].

### 4.5 Queue loss
Per queue: dropped count, health verdict, first loss (feed, queue path, group, sequence, wall time) with
"Open analyser at first loss" [plugin for count via `MongooseCountersService` and verdict via
`MongooseHealthService.forEachStatus` (both bindable services); mongoose A3 for first-loss without an export
to disk]. Move the `queue.*.dropped` counter out of App counters.

### 4.6 Refusals
Show the `ReplyOutcome` and reason verbatim, coloured by outcome. Map known refusal reasons to hints
(mailbox full → "group thread not draining: check Agents thread state"). Fix "stop pending" for unknown
names and the missing-argument exception [mongoose A8]. svc-admin-rest `/admin` must return every reply
line (fixed on `fix/admin-rest-json-and-bad-requests`, not merged to main).

### 4.7 Capture selectors
Audit controls always send `encodeURIComponent(group + "/" + name)` (done here for Replay). Show the
ambiguity error with the selectors as clickable choices [plugin].

### 4.8 Replay status (experimental)
Read-only panel per group/processor: delivered, stopped reason (`replayers().get(g).stopped(p)`) [mongoose A9
admin command, or plugin via `MongooseServer.replayers()`]. Rename the existing "Replay" tab to "Audit
stepper" to avoid the name clash [plugin].

## 5. Actions → admin commands (REST: `POST /api/commands/{name}` `{"args":[...]}` → `{command, output[], err[]}`)

| Action | Command | Status | Notes |
|---|---|---|---|
| Group status | `control.group.status <group>` | rc1 (toString) | A1 adds `--json` |
| Export evidence | `control.audit.export <group> <new-dir>` then `control.audit.export.status <job>` | rc1 | result carries `completeness=` |
| Stop processor | `server.processors.stop <group>/<name>` | rc1 | prints `stop <OUTCOME>: <reason>` |
| Capture start/stop | `audit.start|stop <group/name>`; `POST /api/audit/{selector}/start|stop` | rc1 (+409 fix) | selector URL-encoded |
| Acknowledge | `POST /api/issues/{id}/ack` (plugin-local, per server session) | plugin | never alters Mongoose state |
| Issues | `GET /api/issues` → `[{id, kind, severity, subject, since, evidence, impact, actions[]}]` | plugin + A1–A3 | |
| Last stop | `GET /api/server` adds `lifecycle`, `lastStopResult` | plugin | |
| Retry stop | `server.shutdown.retry` → JSON StopResult | mongoose A4 | |
| Queue health | `GET /api/queues` adds `dropped`, `health`, `firstLoss` | plugin + A3 | |
| Clear DEGRADED latch (#79) | `feed.overload.reset <feed>` | mongoose A10 | precondition: loss acknowledged with evidence exported; consequence: re-arms detection, does not restore lost items |
| Replay status | `replay.status [group]` | mongoose A9 | |

## 6. Once #77 and #79 merge

- **#77 FailureKind** on replies, `StopResult` and audit: issues key on `failureKind` instead of reason
  text; cards show it verbatim; `FAILED_DRIVER_FALLBACK` is CRITICAL. Audit-failure DEGRADED becomes an
  `AUDIT_DEGRADED` issue (evidence: manifest `INCOMPLETE`). The "no manufactured success" rule is #77's.
- **#79 OverloadPolicy** per feed/subscriber: show the policy beside each queue; BLOCK_WITH_DEADLINE shows
  blocked time and deadline misses; never-drop feeds that latch DEGRADED become CRITICAL `FEED_LATCHED` with
  the reset action (A10) and a kill-switch state indicator (read-only; triggering it stays with the
  deployment's incident tooling).

## 7. Mongoose API additions needed

- **A1** `control.group.status <group> --json` (or a JSON-serialisable `ControlGroupStatus` contract): state,
  outstanding, slots, close/cleanup failures by resource id, quarantined processors with reason and
  `failureKind`.
- **A2** Capture resource ledger by id: resource id ↔ capture id/path, state (`CLOSE_FAILED`, …).
- **A3** Bounded "recent facts" query per group without a filesystem export: `ProcessorQuarantined`,
  `QueueDrop`, `CleanupPending`, `Dead` records with `seq`/`fallbackSeq`.
- **A4** Admin `server.shutdown.retry` (and `server.lifecycle`) returning `StopResult` fields as JSON.
- **A5** Read the capture open/resource limits (`captureResourceLimit()`), not only set them.
- **A6** An explicit, audited "release quarantined name after external reconciliation" request, refused
  unless the operator attests reconciliation; until then no re-add is offered.
- **A7** A server option to keep the process alive on a group's VirtualMachineError (report DEAD) instead
  of `System.exit(-1)` in `GlobalErrorHandler`.
- **A8** `server.processors.stop`: argument validation; report `REFUSED: no such processor` instead of
  `stop pending` when nothing holds the name; one refusal wording ("mailbox full" vs "processor-stop queue
  stayed full").
- **A9** `replay.status [group]` admin command.
- **A10** (#79) reset command for a latched DEGRADED feed, audited.
- **A11** List groups including DEAD/CLOSING ones (`registeredProcessors()` drops quarantined processors and
  shows CLOSING groups as empty).
