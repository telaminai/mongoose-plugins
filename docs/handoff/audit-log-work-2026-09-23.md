# Audit log work in this repository — brief for another session, 2026-09-23

Two pieces of work in `svc-admin-web` touch the audit log. One is written and waiting for review; one is
not started and is now blocking a feature in the analyser. Neither is recorded anywhere in this
repository, which is why this file exists: everything else about them lives on an unmerged branch of
`telaminai/fluxtionauditlog-analyser`, where nobody working here would find it.

Nothing here is a claim that either item is finished. Item 1 has never been reviewed by anyone.

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

**What is still owed, and was never claimed.** The acceptance that matters — the count delivered equals
the count exported for the same window — needs a live server and a client, and **no shipped client opens
this socket**. That is the other half of the original finding and it is still open. A reviewer should
decide whether the fix merges on the unit evidence with that acceptance filed, or waits for a client.

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

**Not done:** no server started, no socket driven, no export produced from a running Mongoose, no client
written. The end-to-end acceptance in §1 remains unmet and the §2 change is unimplemented.
