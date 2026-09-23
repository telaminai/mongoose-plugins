# Audit log work in this repository — brief for another session, 2026-09-23

Two pieces of work in `svc-admin-web` touch the audit log. **Both are now written, reviewed once and
answered, on `fix/audit-tail-thread-safety`.** This file exists because neither was recorded anywhere in
this repository: everything about them lived on an unmerged branch of
`telaminai/fluxtionauditlog-analyser`, where nobody working here would find it.

They are on one branch deliberately. They touch the same file and the same feature — one makes the tail
deliver, the other makes an export's completeness claim mean anything — so they review and release
together.

**Gates on the branch:** the whole repository builds — 20 reactor projects, of which 19 have tests, 283
tests in total — and `svc-admin-web` is 115 tests, 0 failures, 0 errors, 0 skipped. (An earlier version of
this line said "all 20 modules" and review read it as a claim about 20 test suites; the aggregator is the
twentieth project and has none. Both numbers are stated now so neither reading is wrong.)

**The end-to-end acceptance is MET.** This file previously said it was owed and could not be claimed —
that was true when it was written and is no longer. `AuditTailDeliveryAcceptanceTest` starts a real
service on a free port, connects a `java.net.http.HttpClient` websocket (the JDK speaks the protocol, so
no dependency was added and no client had to ship), writes 250 records and asserts delivered == exported
for the same window. **It found a defect in the fix itself that no unit test could see** — see §1.

---

## 1 · The audit-tail socket fix — WRITTEN, PUSHED, NEVER REVIEWED

**Branch** `fix/audit-tail-thread-safety`, one commit `419f8d4`, ahead of `main` `df12155`. No pull
request is open. It has sat untouched since 2026-09-21.

**The symptom.** `GET /ws/audit-tail/{processor}` completed a real websocket upgrade, reported a healthy
connection, and delivered nothing — including for records appended after the client connected.

**Two defects, and the first hid the second.**

The tailer was created with `toEnd()` on the Jetty connect thread and read on a scheduled executor. A
Chronicle `StoreTailer` belongs to the thread that created it, so every tick threw
`ThreadingIllegalStateException` — and the catch logged it at debug level. That is why the socket looked
healthy while doing nothing.

Behind it: each tick read records into a **local** list and discarded it unless the flush condition was
met (at least 32 records, or 50 ms elapsed). The tailer had already advanced past those records, so they
were unrecoverable and nothing reported the loss.

**The fix.** The tailer is created lazily on first tick, which is always the executor thread. The batch
moved into the per-socket state and is cleared only after a successful send.

**Tests.** `AuditTailThreadingTest`, three tests. The first reproduces Chronicle's cross-thread refusal
against a bare queue, so the lazy creation is provably load-bearing rather than stylistic: if Chronicle
ever stops refusing, that test fails and tells the next reader the fix can be revisited. Module suite:
100 tests, zero failures.

**Reviewed three times, 2026-09-23, and answered on the same branch.** The first answer is `78dd23a`. The review found both causes
correctly diagnosed and correctly fixed, and then found that nothing protected either fix: three
production mutations each left the whole suite green, because every test drove a bare Chronicle queue and
re-implemented the tick loop. The tick is now a package-private method the tests drive, and all four
mutations go red. Also fixed: `close()` now waits for the tick before closing the queue (closing under an
active read throws on the reader, reproduced); consecutive failing ticks are counted, logged at warn and
eventually close the socket, so the *shape* that hid the original bug is gone — a later round added the
second half of that fuse, because the count alone is half a second at a 25 ms poll and would drop a client
that blocked briefly on one send, costing it the records it missed while reconnecting; the surviving batch has a
ceiling, because a stuck-but-open client grew it by about forty records a second for ever; the log-tail
fan-out no longer drops subscribers silently with their session left open; and the literal NUL byte that
made this file read as binary to `grep` and `diff` is gone.

**A reconnect starts at `toEnd()`**, so a client that drops misses everything written while it was away.
That is inherent to a tail rather than a defect, and it is stated here and in the tick tests so it is
known rather than discovered.

**The acceptance, and the third defect it found.** The count delivered equals the count exported for the
same window: 250 of 250, in `AuditTailDeliveryAcceptanceTest`, against a live server and a real websocket
client. It did not pass first time — it reported **0 of 250** while the export held all 250.

The cause was in the fix, not in the original code. Creating the tailer lazily put `toEnd()` on the first
tick, which is up to one poll interval (25 ms) AFTER the client connected, so everything written in that
window was skipped — silently, because a tail that starts late is indistinguishable from a quiet queue.
No unit test could see it: every one of them creates the state and takes its tailer in the same breath,
so the gap is zero. The fix positions the tail immediately, still on the reading thread:
`exec.execute(state::tailer)`. Reverting that line fails the acceptance and nothing else.

A residual window remains, between the queue opening and that task starting — microseconds rather than
tens of milliseconds. Closing it completely means capturing an index at connect and seeking to it. That is
not done, and is recorded as a limit rather than described as solved.

**A reconnect still starts at the live end**, so a client that drops misses what was written while it was
away. That is what a tail is, and it is stated in the tick tests so it is known rather than discovered.

**The third round's items, all taken.** `tick` takes a `Consumer<String>` rather than `Consumer<Object>`,
so the call site binds to Javalin's `send(String)` and the wire format no longer depends on the mapper
having an `instanceof String` passthrough — found by reading the bytecode, invisible to any behavioural
test while the passthrough exists, so the signature itself is asserted. `close()`'s wait for the reader
and the failure counter's reset were both behaviours no test could tell had happened; both are now driven
by `AuditTailLifecycleTest`, and reverting either goes red. Four mutations were run and each one failed:
shortening the await to zero, deleting the reset, dropping the duration half of the fuse, and restoring
the lazy tailer.

**What a reviewer should attack.** Whether the batch can still be lost on a failed send or a disconnect;
whether the lazy creation races another tick; whether the flush thresholds are still right now that the
batch survives across ticks; and whether anything else in this service catches a throwable at debug level
and reports healthy.

---

## 2 · The export endpoint must terminate its last document — NOT STARTED, AND IT BLOCKS

**Where.** `WebAdminService.handleAuditExport`, the YAML branch of its loop:

```java
if (!first) w.write("\n---\n");
w.write(yaml);
first = false;
```

Separators go **between** documents and nothing is written after the last. The JSON-lines branch is
unaffected; this is the only YAML container writer in the service.

**The ask: also write `\n---\n` after the last document.** One line.

**DONE in `78dd23a`**, on the same branch as item 1, so they review and release together. The framing
rule is extracted as `YamlContainerWriter` rather than left inline — for the same reason item 1's blocker
existed — and driven by `YamlContainerWriterTest`. Acceptance run against the **published** analyser
1.18.0 jar rather than a local build: a marked export reads `complete`, the same export written the old
way reads `unterminated_marker`, and an unmarked export reads exactly as it did before. Module suite 110
run, 0 failures.

**Why it is not cosmetic.** Audit format revision 1.1 adds an optional *stream-end marker* — a final
record saying the writer finished and how many records it wrote — so a reader can tell a whole log from a
truncated one. §1a requires that marker to be followed by its `---` separator, because at the byte level
a marker a writer has finished and one it is halfway through writing are **the same bytes**. Measured on
a reader that did not require termination: a marker caught mid-write after twelve records reported
*"this log declares 1 record and 12 were read — the marker is wrong"*, a confident and fabricated verdict
about a file that was simply still being written. The separator is what makes the claim atomic.

**Why the marker writer cannot fix this itself.** The separator belongs to the export formatter, not to
whatever writes the marker. So a marker appended by any other component is always the last, unterminated
document, the analyser reports `unterminated_marker`, and the completeness claim is ignored. The feature
ships and does nothing.

**Cost and risk.** A trailing separator has always been legal in this format — the separator *separates*
records and blank text after the last one is skipped — and every existing reader accepts one. Verified
against the released analyser: a file with a trailing separator and a marker loads with the expected
record count, zero parse errors and an unchanged time range. No reader needs to change.

**Acceptance.** An export carrying a marker reads as *complete* in the analyser; an export without one
reads exactly as it does today.

**Filed as** `UP-MON-01` in the analyser's `docs/proposals/upstream-asks.md` (on the unmerged branch
`feat/audit-stream-end-v2`), with the reasoning in `docs/specs/spec-audit-stream-end.md` D-E8.

---

## 3 · The larger piece this belongs to, for context

A separate, larger item has Mongoose writing the text audit log directly, rather than only exporting it
from Chronicle. That work is not started and is not in this repository yet. It needs the marker writer
**and** the change in §2 above, which are in different classes and can be done independently — §2 is
useful on its own and is the smaller of the two.

Its original acceptance was "byte-identical to a known-good export modulo the marker". That was withdrawn
as impossible once §1a required termination: a known-good export has no trailing separator. The
acceptance is now byte-identical modulo the marker **and** the final separator.

---

## What I did and did not verify

**Read in this repository, on `origin/main` `df12155`:** `WebAdminService.handleAuditExport` and the
audit-tail websocket handler. The quoted export loop is the current code.

**Ran:** the `svc-admin-web` module suite with the branch-1 fix applied — 100 tests, zero failures — on
2026-09-21. Not re-run since.

**Since revised.** Both were done. A server IS started and a socket IS driven, by the acceptance test
described in §1 — which is also why the "no client written" line no longer holds: the JDK's own websocket
client is the client. §2 is implemented and driven by `YamlContainerWriterTest`.

**Still not done:** no export produced from a *running Mongoose* — the acceptance stands up
`WebAdminService` with a stub introspection service over a real Chronicle queue, which is the service's
own boundary, not the whole container. Mongoose writing the text log directly (§3) is untouched.
