# Audit log work in `svc-admin-web` — brief for a reviewer, 2026-09-23

Two changes to the audit log, on one branch, both written and both answered through five rounds of
review. This file is the record of what they are and what is and is not proved about them.

**Branch** `fix/audit-tail-thread-safety`, base `origin/main` `df12155`. No pull request is open.
Nothing is merged. Nothing is released.

```
4f83a02  Close the audit-tail acceptance end to end, and fix the defect it found
6137087  Brief: it is one branch now, and say what the gates were
1403c44  Brief: record the review outcome, the fixes and the reconnect behaviour
a2d8569  Record the audit-log work, so it stops living only in another repository
78dd23a  Guard the audit-tail fix, and terminate the export's last document
419f8d4  Fix the audit-tail socket: it connected, reported healthy and delivered nothing
```

They are on one branch deliberately: they touch the same file and the same feature — one makes the tail
deliver, the other makes an export's completeness claim mean anything — so they review and release
together.

**This file exists** because neither change was recorded anywhere in this repository. Everything about
them lived on an unmerged branch of `telaminai/fluxtionauditlog-analyser`, where nobody working here
would find it.

**Two earlier versions of this file made claims that were wrong, and review caught both.** It said "all
20 modules" where the gate is 19 test suites plus an aggregator, and it described the end-to-end
acceptance as impossible without a client that did not exist. Both are corrected below. The history is
in the file's own commits; it is noted here because a brief that quietly rewrites its own claims is
worth less than one that says which of them did not hold.

**Gates, after round 5.** Clean build of the whole repository: 20 reactor projects, 19 of which have
tests, **296 tests**, 0 failures, 0 errors, 0 skipped. `svc-admin-web`: **128 tests**, 0 failures.

---

## 1 · The audit-tail socket delivered nothing

### The symptom

`GET /ws/audit-tail/{processor}` completed a real websocket upgrade, reported a healthy connection, and
delivered nothing — including for records appended after the client connected.

### Three defects. The first hid the second, and the third was introduced by the fix for the first

**(a) The tailer was built on the wrong thread.** It was created with `toEnd()` on the Jetty connect
thread and read on a scheduled executor. A Chronicle `StoreTailer` belongs to the thread that created
it, so every tick threw `ThreadingIllegalStateException` — and the catch logged it at debug level. That
is why the socket looked healthy while doing nothing.

**(b) Records were read and then dropped.** Each tick read into a **local** list and discarded it unless
the flush condition was met (32 records, or 50 ms elapsed). The tailer had already advanced past those
records, so they were unrecoverable and nothing reported the loss.

**(c) The tail was positioned too late.** Fixing (a) by creating the tailer lazily on the first tick put
`toEnd()` up to one poll interval — 25 ms — **after** the client connected, so everything written in
that window was skipped. Silently, because a tail that starts late is indistinguishable from a quiet
queue. This one is mine, not the original author's, and §4 is how it was found.

**(d) And the first fix for (c) did not close it.** Moving the positioning to the executor at connect
narrowed the window but left it open, because `onOpen` fires at the HTTP 101 upgrade — so from the
client's side the window spans the whole connect handler, not just the scheduling. I described that
residue as "microseconds" in a comment and in this file **without measuring it**, in the same document
where I said not to do that. Review measured it and rejected the claim. My own measurement is worse than
theirs: **records written immediately after `onOpen` were lost in 19 of 20 rounds**, and the window is a
**median of 8.0 ms, maximum 17.1 ms** — three to four orders of magnitude out.

### The fixes

| Defect | Fix |
| --- | --- |
| (a) | The tailer is created on the executor thread, which is the thread that reads it. |
| (b) | The batch lives in the per-socket state and is cleared only after a successful send. |
| (c)+(d) | `javalin.wsBeforeUpgrade` opens the queue and fixes the start index **before the 101 is sent**, so there is no window for the client to write into. The reading tailer still gets built on the executor thread and seeks to that index. |

**The window, measured after the fix.** 0 of 20 rounds lose records, and the first record delivered in
every round was written **0µs** after `onOpen` — at rest, and again under a load average of 21 to 27.
`AuditTailConnectWindowProbeTest` and `AuditTailConnectWindowMeasurementTest` are those two runs; the
second prints a distribution rather than asserting a bound, so it cannot turn back into the kind of
claim that caused this.

Review's suggested fix — capture `toEnd().index()` in the connect handler — is **necessary but not
sufficient**, and the measurement is why: the loss happens before that handler runs at all. It is in
place as the fallback for when the pre-upgrade hook does not run.

`ChronicleIndexSemanticsTest` pins the two facts the fix depends on, because both are easy to assume
wrongly and I did assume one wrongly first: on a non-empty queue `moveToIndex` on a captured
`toEnd().index()` returns true and reads exactly what followed; on an **empty** queue `toEnd().index()`
is `0`, `moveToIndex(0)` returns false, and the tailer is correctly left at the start. My first attempt
fell back to `toEnd()` when the seek failed, which skipped everything and made all 20 rounds lose.

**A reconnect starts at the live end**, so a client that drops misses everything written while it was
away. That is what a tail is rather than a defect, and it is asserted in `AuditTailTickTest` so it is
known rather than discovered.

### What review changed, in five rounds

**Round 1 — nothing protected the fix.** Both causes were correctly diagnosed and correctly fixed, and
three production mutations each left the whole suite green, because every test drove a bare Chronicle
queue and re-implemented the tick loop inside itself. `tick` is now a package-private method the tests
drive. Round 1 also fixed: `close()` waits for the tick before closing the queue (closing under an
active read throws `ClosedIllegalStateException` on the reader, reproduced); consecutive failing ticks
are counted, logged at warn and eventually close the socket, so the *shape* that hid the original bug is
gone; the surviving batch has a ceiling, because a stuck-but-open client grew it by about forty records a
second for ever; the log-tail fan-out no longer drops subscribers silently with their session left open;
and the literal NUL byte that made `WebAdminService.java` read as binary to `grep` and `diff` is gone.

**Round 2 — MERGE, with two unpinned behaviours and a signature.** All three taken:

- **`tick` took a `Consumer<Object>`**, so the call site `ctx::send` bound to Javalin's `send(Object)`
  and every frame went through the JSON mapper. Byte-identical today only because JavalinJackson passes
  a String through untouched; under any mapper without that passthrough, every frame becomes a quoted,
  escaped string. Found by reading the bytecode. It is now `Consumer<String>`, so the compiler chooses
  `send(String)` — and because no behavioural test can see the difference while the passthrough exists,
  the **signature itself** is asserted rather than the bytes.
- **`close()`'s wait for the reader** and **the failure counter's reset** were behaviours no test could
  tell had happened. `AuditTailLifecycleTest` drives both.
- **The fuse was too short.** 20 consecutive failures at a 25 ms poll is half a second, so a client that
  blocked briefly on one send lost its socket — and with it the records it missed while reconnecting. A
  run must now also last `MIN_FAILURE_WINDOW_MS` (2 s), which a transient stall does not reach and a
  genuinely broken socket passes without anyone waiting long.

**Round 3 — this brief's own numbers.** Corrected at the top.

**Round 5 — blocker cleared, two unguarded call sites.** Extracting `shouldPoll` and `fanOutLogLine`
made the logic testable but pinned nothing: the tests called them directly, so reverting either CALL
SITE left the whole suite green. Both are now driven as production runs them — the scheduled poll is
`pollOnce`, which a test invokes with a paused state, and `broadcastLogLine` is invoked directly with a
`WsContext` over a `Proxy` Jetty session that fails every send. Both mutations are red. Review also
scoped the external finding correctly; see §5.

**Round 4 — DO NOT MERGE, on one blocker.** The blocker was (d) above: the "microseconds" claim, which
review measured and disproved. Also taken:

- **The acceptance passed for the wrong reason.** It opened a fresh queue inside `append()`, and a queue
  build is slower than the server's positioning, so the connect window never showed up in it. It now
  writes through a handle held open across the connect, as the sink does, and compares record **ids as
  sets** rather than counts, so a duplicate and a loss cannot cancel out.
  **How much it now catches is machine-dependent, and I understated it.** On my machine, removing the
  pre-upgrade fix leaves the acceptance green while the probe goes to 19 of 20, and I wrote that up as
  "it does not catch the window". On the reviewer's machine the same mutation fails the acceptance, the
  probe AND the running-server run. The held-open handle made it sensitive after all, just not reliably
  so. The probe stays the designated guard because it is the one that fails everywhere; the acceptance
  is a second line, not no line.
- **Three behaviours nothing guarded**, all green when mutated, all now red: the `paused` half of the
  tick gate, the close on a failed log-tail fan-out send, and `lastFlush` advancing. The first two were
  unreachable from a test and are extracted as `shouldPoll` and `fanOutLogLine`.
- **The reflection test's justification was too strong.** "No behavioural test is possible" is true only
  because this service does not expose its JSON mapper — a design choice, not a law. The comment now
  says so, and says to delete the reflection test if that changes.

### Tests

| Class | Tests | What it holds |
| --- | --- | --- |
| `AuditTailTickTest` | 6 | The service's own tick loop: the surviving batch, the full-batch flush, retry on a failed send, the ceiling, lazy-tailer thread affinity, the `Consumer<String>` signature |
| `AuditTailLifecycleTest` | 7 | `close()` waits for a reader inside the queue; the failure reset; both halves of the fuse; `lastFlush` advancing; the pause gate, in isolation and at its call site |
| `LogFanOutCallSiteTest` | 1 | `broadcastLogLine` itself closes a failing subscriber's session |
| `AuditTailThreadingTest` | 3 | Chronicle's cross-thread refusal against a bare queue, so the affinity requirement is provably load-bearing rather than stylistic — if Chronicle ever stops refusing, this fails and tells the next reader the fix can be revisited |
| `AuditTailDeliveryAcceptanceTest` | 1 | §4 — delivery, by id, against a live server and a real client |
| `AuditTailConnectWindowProbeTest` | 1 | 20 rounds: nothing written after `onOpen` is lost. The guard for (c)+(d) |
| `AuditTailConnectWindowMeasurementTest` | 1 | Prints the window's width; asserts nothing, so it cannot become a claim |
| `ChronicleIndexSemanticsTest` | 3 | What `toEnd().index()` and `moveToIndex` do, empty and not |
| `AuditRunningServerAcceptanceTest` | 3 | §5 — a booted `MongooseServer` with its own audit sink |

**Mutations run, each one red:** `awaitTermination(250)` → `(0)`; deleting the reset in `succeeded()`;
dropping the duration half of the fuse; restoring the lazy tailer; removing the `wsBeforeUpgrade`
registration; dropping the `paused` half of the tick gate; dropping the close on a failed fan-out send;
not advancing `lastFlush`; **and the two call sites** — the scheduled poll ignoring `shouldPoll`, and the
fan-out reverting to a silent drop. Review independently reproduced four of these, added two of its own,
and found the two call-site gaps. Round 1's four were not re-run.

---

## 2 · The export must terminate its last document (`UP-MON-01`)

**Where.** `WebAdminService.handleAuditExport`, the YAML branch of its loop. It was:

```java
if (!first) w.write("\n---\n");
w.write(yaml);
first = false;
```

Separators went **between** documents and nothing was written after the last. The JSON-lines branch is
unaffected; this was the only YAML container writer in the service.

**Done in `78dd23a`.** The framing rule is extracted as `YamlContainerWriter` rather than left inline —
for exactly the reason §1's round-1 finding existed — and driven by `YamlContainerWriterTest`, 5 tests.

**Why it is not cosmetic.** Audit format revision 1.1 adds an optional *stream-end marker*: a final
record saying the writer finished and how many records it wrote, so a reader can tell a whole log from a
truncated one. §1a requires that marker to be followed by its `---`, because at the byte level a marker
a writer has finished and one it is halfway through writing are **the same bytes**. Measured on a reader
that did not require termination: a marker caught mid-write after twelve records reported *"this log
declares 1 record and 12 were read — the marker is wrong"*, a confident and fabricated verdict about a
file that was simply still being written. The separator is what makes the claim atomic.

**Why the marker writer cannot fix this itself.** The separator belongs to the export formatter, not to
whatever writes the marker. A marker appended by any other component is therefore always the last,
unterminated document, the analyser reports `unterminated_marker`, and the completeness claim is
ignored. The feature would ship and do nothing.

**Cost and risk.** A trailing separator has always been legal — the separator *separates* records, and
blank text after the last one is skipped — and every existing reader accepts one. **Verified against the
published analyser 1.18.0 jar** rather than a local build: a marked export reads `complete`, the same
export written the old way reads `unterminated_marker`, and an unmarked export reads exactly as it did
before, with the expected record count, zero parse errors and an unchanged time range. No reader needs
to change.

**Filed as** `UP-MON-01` in the analyser's `docs/proposals/upstream-asks.md`, with the reasoning in
`docs/specs/spec-audit-stream-end.md` D-E8.

---

## 3 · The larger piece this belongs to, for context — NOT STARTED

Mongoose writing the text audit log directly, rather than only exporting it from Chronicle. Not started
and not in this repository. It needs the marker writer **and** §2, which are in different classes and can
be done independently.

Its original acceptance was "byte-identical to a known-good export modulo the marker". That was withdrawn
as impossible once §1a required termination — a known-good export has no trailing separator. The
acceptance is now byte-identical modulo the marker **and** the final separator.

---

## 4 · The end-to-end acceptance — MET, and it found defect (c)

The acceptance that mattered from the first finding: **the count a live client is delivered equals the
count the export contains, for the same window.** Earlier versions of this file said it could not be
claimed, because it needs a running server and a client, and no shipped client opens this socket.

It cost a test class rather than a dependency: `java.net.http.HttpClient` speaks WebSocket natively.
`AuditTailDeliveryAcceptanceTest` starts a real `WebAdminService` on a free port over a real Chronicle
queue, connects a websocket, writes 250 records, and asserts delivered == exported == 250.

**It reported 0 of 250 on the first run**, while the export held all 250. That is defect (c), and it was
found by this test and by nothing else — every unit test creates the state and takes its tailer in the
same breath, so the gap that loses the records is zero. Reverting `exec.execute(state::tailer)` fails
this test and no other.

**Why the window is an empty queue.** A tail begins at the live end, so it can only deliver what is
written after it connects, while the export contains the whole file. Connecting to an empty queue and
writing afterwards is the one arrangement in which both cover exactly the same records, which is what
makes the two counts comparable at all.

---

## 5 · The runs against a booted server, and what one of them found

Review asked for two runs against the real thing rather than against `WebAdminService` standing alone.
Both were done. `AuditRunningServerAcceptanceTest` boots a real `MongooseServer` with
`PerformanceMonitoringConfig.auditCapture` enabled, so the core builds its own `ChronicleAuditCaptureService`
and `DirAuditIntrospectionService` and injects them. Nothing about the sink, the introspection or the
export is stubbed.

**RUN 2 — delivered equals exported, against a booted server.** produced 300, delivered 300, exported
300, compared as id sets. Ten frames, sizes `[32, 28, 32, 28, …]`, so both flush paths fired: the
32-record batch threshold and the 50 ms latency timer.

**RUN 1 — the format, end to end, against the published analyser.** The export of a running server's
sink, with a marker appended, read by
`fluxtion-auditlog-analyser.jar` 1.18.0 — downloaded from the release, sha256
`5a8c2a4f070ad06a7804894391b5660d3fe160c14d6f382ddf2ddff3f79a2f02`, 3,914,217 bytes — not a local build:

| Export | `log.streamEnd` | `records` |
| --- | --- | --- |
| with marker, exporter change in place | `{"state": "complete", "recordsRead": 40, "declaredRecords": 40}` | 40 |
| with marker, final separator removed | `{"state": "unterminated_marker", "recordsRead": 40}` | 40 |
| no marker | `{"state": "unknown", "recordsRead": 40}` | 40 |

The middle row is the one that matters as much as the first: it is the evidence that the one-line change
is load-bearing. And `records` is 40 in every row, so the marker is not counted as a record by a reader
built and released before this exporter existed.

### The finding: a `customHandler` processor's audit log produces nothing

The runs were meant to have a real processor generate the records. On this path it cannot. A real handler
on a real agent thread, calling `auditLog.info(...)` on every event, at level DEBUG, produces **zero**
records:

- the DataFlow Mongoose builds for a `customHandler` processor has **no `EventLogManager` auditor** —
  `getAuditorById("eventLogger")` throws `NoSuchFieldException`;
- **no class in mongoose-1.0.29 references `EventLogManager`, `addAuditor` or `EventLogControlEvent`**
  (review unpacked all 170 classes; my own scan agreed), so nothing ever installs one;
- `POST /api/processors/{group}/{name}/audit/level` returns **200** and changes nothing;
- the sink is created, `isLive` is true, and the queue directory holds only `metadata.cq4t` — no data
  file is ever written.

**Scoped, because my first statement of this was too broad.** It is not true that Mongoose processors in
general produce no audit records. Review pointed at the analyser's preserved real export fixture,
`c21-real-export.yaml`: **25 records, of which 7 carry node entries and 18 are empty** — counted, not
taken on trust. So an AOT-built processor does log through Mongoose. What is broken is the
DataFlow-for-`customHandler` path specifically, and the claim now says that.

Two things follow, both review's:

- **The analyser already diagnoses this exact case.** `ProducerDiagnostics.NO_NODE_LOGS` says the
  `EventLogManager` auditor was never installed and names `addEventAudit()` as the likely missing call.
  The product points at the cause and the fix.
- **`c21` has been carrying the symptom in the fixture set all along** — 18 of its 25 records are empty —
  which is a better demonstration of the shape than anything constructed here.

Nothing on this branch depends on any of it; the runs write into the real sink directly, which is
labelled in the test. It is filed here because it was found here and is recorded nowhere else.

`aCustomHandlerProcessorsAuditLogProducesNothing` asserts the behaviour **as it is**, so the day it
changes somebody is told. It does not assert that it is correct. It is not.

---

## What is verified, and what is not

**Verified.** Everything in the tables above, run on 2026-09-23 at `4f83a02`. The analyser behaviour in
§2 against the published 1.18.0 jar. The four mutations in §1.

**Verified in round 4, additionally.** The window measurements in §1; the three RUN 1 rows against the
published 1.18.0 jar; RUN 2 against a booted server; the eight mutations.

**Not verified.**

- **No record produced by a real processor**, because none can be — see §5. Everything downstream of the
  sink is exercised with real bytes; the bytes themselves are written by the harness.
- **The `MAX_PENDING` ceiling has no live-server test.** One was written and it failed, and the premise
  rather than the service was wrong: a JDK websocket client that never calls `request()` applies flow
  control in its own listener, not on the wire, so the server's sends kept succeeding and the batch never
  grew. Reaching the ceiling live needs a client that stops reading at the TCP level. Recorded as not
  done rather than replaced with an assertion that would pass without testing anything. The ceiling is
  covered by `AuditTailTickTest`, where a failing send is the actual condition.
- **No soak test.** The fuse and the batch behaviour are driven with an injected clock, not observed over
  hours.
- **The window was measured on one machine**, at rest and under a load average of 21–27. Not on CI.
- **§3 is untouched.**

## What a reviewer should attack

1. **The pre-upgrade hook itself.** It opens a Chronicle queue on the upgrade request. If the upgrade is
   then rejected, nothing downstream ever claims that queue — there is a 30-second sweep for exactly
   that, and the sweep is the part I would attack: it runs only when another pre-open happens, so a
   single abandoned queue on an idle server is held until the next connect.
2. **Whether the batch can still be lost** on a failed send that is followed by a disconnect rather than
   a retry.
3. **The flush thresholds** — 32 records / 50 ms — now that the batch survives across ticks rather than
   being dropped.
4. **The fuse numbers.** 20 failures and 2 s are both judgement, and the second was added in response to
   review rather than to evidence.
5. **Anything else in this service that catches a throwable at debug level and reports healthy.** That
   shape is what made the original defect invisible for as long as it was; §1 removed it in one place.
