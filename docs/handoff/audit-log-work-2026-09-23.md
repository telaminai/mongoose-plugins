# Audit log work in `svc-admin-web` — brief for a reviewer, 2026-09-23

Two changes to the audit log, on one branch, both written and both answered through three rounds of
review. This file is the record of what they are and what is and is not proved about them.

**Branch** `fix/audit-tail-thread-safety`, six commits, base `origin/main` `df12155`. No pull request is
open. Nothing is merged. Nothing is released.

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

**Gates, as of `4f83a02`.** Whole repository: 20 reactor projects, 19 of which have tests, 283 tests, 0
failures, 0 errors, 0 skipped. `svc-admin-web`: 115 tests, 0 failures.

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

### The fixes

| Defect | Fix |
| --- | --- |
| (a) | The tailer is created on the executor thread, which is the thread that reads it. |
| (b) | The batch lives in the per-socket state and is cleared only after a successful send. |
| (c) | `exec.execute(state::tailer)` positions the tail at connect, still on the reading thread. |

**The residual window on (c).** Between the queue opening in the connect handler and that task starting
there is still a gap — microseconds, rather than tens of milliseconds. Closing it completely means
capturing an index at connect and seeking to it on the reader. **That is not done.** It is a stated
limit, not a solved problem, and it is the thing in this branch I would attack first.

**A reconnect starts at the live end**, so a client that drops misses everything written while it was
away. That is what a tail is rather than a defect, and it is asserted in `AuditTailTickTest` so it is
known rather than discovered.

### What review changed, in three rounds

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

### Tests

| Class | Tests | What it holds |
| --- | --- | --- |
| `AuditTailTickTest` | 6 | The service's own tick loop: the surviving batch, the full-batch flush, retry on a failed send, the ceiling, lazy-tailer thread affinity, the `Consumer<String>` signature |
| `AuditTailLifecycleTest` | 3 | `close()` waits for a reader that is inside the queue; the failure reset; both halves of the fuse |
| `AuditTailThreadingTest` | 3 | Chronicle's cross-thread refusal against a bare queue, so the affinity requirement is provably load-bearing rather than stylistic — if Chronicle ever stops refusing, this fails and tells the next reader the fix can be revisited |
| `AuditTailDeliveryAcceptanceTest` | 1 | §4 |

**Mutations run at `4f83a02`, each one red:** `awaitTermination(250)` → `(0)`; deleting the reset in
`succeeded()`; dropping the duration half of the fuse; restoring the lazy tailer. Round 1 recorded four
further mutations going red against `78dd23a`; those were not re-run for this commit.

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

## What is verified, and what is not

**Verified.** Everything in the tables above, run on 2026-09-23 at `4f83a02`. The analyser behaviour in
§2 against the published 1.18.0 jar. The four mutations in §1.

**Not verified.**

- **No export from a running Mongoose.** The acceptance stands up `WebAdminService` with a stub
  introspection service over a real Chronicle queue. That is the service's own boundary, not the whole
  container.
- **The residual window in §1(c)** is argued to be microseconds and is not measured.
- **No load or soak test.** The ceiling, the fuse and the batch behaviour are driven at unit speed with
  an injected clock, not observed under a real slow client.
- **§3 is untouched.**

## What a reviewer should attack

1. **The residual window on (c)** — is the index-at-connect approach the right fix, or does positioning
   at connect need to move into the upgrade handler itself?
2. **Whether the batch can still be lost** on a failed send that is followed by a disconnect rather than
   a retry.
3. **The flush thresholds** — 32 records / 50 ms — now that the batch survives across ticks rather than
   being dropped.
4. **The fuse numbers.** 20 failures and 2 s are both judgement, and the second was added in response to
   review rather than to evidence.
5. **Anything else in this service that catches a throwable at debug level and reports healthy.** That
   shape is what made the original defect invisible for as long as it was; §1 removed it in one place.
