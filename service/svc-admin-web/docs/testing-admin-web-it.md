# admin-web failure-scenario packs

Two regression packs drive the failure scenarios of `failure-ux-proposal.md` (a–h) against a real
Mongoose 1.0.33-rc1 server, in-process, with svc-admin-web and svc-admin-rest bound to 127.0.0.1 on free
ports. They live in `src/test/java/com/telamin/mongoose/plugin/svc/adminweb/it/` and run only under the
`admin-web-it` profile, so the normal build stays fast.

```sh
mvn -Padmin-web-it verify -pl service/svc-admin-rest,service/svc-admin-web   # unit + REST + UI
mvn -Padmin-web-it verify -pl service/svc-admin-web -Dit.groups=rest-it      # one pack (ui-it likewise)
mvn -Padmin-web-it verify -pl service/svc-admin-web -Dgolden.update=true     # rewrite golden JSON, then review the diff
mvn -Padmin-web-it verify -pl service/svc-admin-web -Dit.groups=proposed -Dit.excludedGroups=none  # expected to fail
```

| Class | Tag | What it asserts |
|---|---|---|
| `AdminRestScenarioIT` | `rest-it` | per scenario: exact JSON (golden files in `src/test/resources/it/golden/`, volatile values normalised) plus the failure facts |
| `AdminWebUiScenarioIT` | `ui-it` | per scenario: what the operator sees through `data-testid` selectors; no console/page errors, failed requests or unexpected HTTP errors |
| `AdminProposedIT`, `AdminWebUiProposedIT` | `proposed` | the behaviour the proposal asks for; each test names its section. Excluded by default; they fail until it lands |

Fixtures (`Fixtures`, `Scenarios`, `ScenarioServer`) provoke each failure deterministically: a generated
(AOT) processor and a handwritten one throwing on `boom`; a `StackOverflowError` killing a group (with a
recording error handler: the default exits the JVM); a `stop()` blocked on a latch; capture resources whose
physical close throws (reflection onto the group's `captureResources`, since `MongooseServer` does not expose
groups) with the resource limit set to 1; a parked consumer flooded past its 1024 queue; a parked group whose
4096-slot control mailbox is filled.

Where the current behaviour is wrong, the current-behaviour test pins it with a comment
("CURRENT (misleading)", "CURRENT (missing)") and the desired assertion lives in a `proposed` test. Do not
change a current-behaviour assertion to make it pass; change it when the behaviour changes on purpose.

**Browser.** Playwright for Java 1.49.0, headless Chromium. `scripts/` only fetches vendor JS, so there was
no existing driver to reuse. Playwright gives JUnit-native control, auto-waiting assertions, console and
network events (the DevTools-level checks) and traces without a separate driver binary; Selenium would need
CDP plumbing for the same checks. The first run downloads Chromium (set `PLAYWRIGHT_BROWSERS_PATH` to choose
where). Allowed 4xx: `/api/files` (404 without a loader directory) and the 401 auth probe; extend
`Browser.EXPECTED_4XX` only with a reason.

**Artifacts** under `target/admin-web-it/`: `ui/<test>/NN-step.png` screenshots for every step (evidence,
not a pixel-diff criterion), `ui/<test>/trace.zip` on failure (open with
`npx playwright show-trace`), `ui/<test>/http-errors.txt`, and `rest/<scenario>.actual.json`.
CI: `.github/workflows/admin-web-it.yml` (manual dispatch; installs Mongoose from source until the RC is
published; uploads the artifacts).
