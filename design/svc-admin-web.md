# svc-admin-web — Web Admin & Monitoring UI for Mongoose

**Status:** Draft / Initial spec
**Module:** `service/svc-admin-web`
**Maven coordinates (proposed):** `com.telamin:svc-admin-web:0.2.13-SNAPSHOT`  *(0.2.12 already shipped without this module)*
**Owners:** plugins team
**Sibling modules:** `svc-admin-telnet` (line protocol), `svc-admin-rest` (HTTP API)

---

## 1. Purpose

Provide a browser-based, single-binary admin and monitoring console for a running Mongoose server. It is a **standard Mongoose service plugin** — registered in the same YAML/Spring configuration as any other service, lifecycle-managed by Mongoose, and driving the same `AdminCommandRegistry` surface that `svc-admin-telnet` and `svc-admin-rest` already drive.

Where `svc-admin-telnet` is the "operator-with-a-shell" UX and `svc-admin-rest` is the "machine-to-machine" UX, `svc-admin-web` is the "human-with-a-browser" UX:

- See what services and processors are loaded **without typing a command**.
- Run admin commands from a form with discovery and history.
- Watch live counters, log tails, queue depths.
- Drive cache, loaders, and any other admin-command-publishing service from one place.

It does **not** introduce a new admin surface. It is a presentation layer over `AdminCommandRegistry`.

## 2. Non-goals

- Not a multi-tenant control plane: one running Mongoose JVM per instance of this plugin.
- Not an IDE: no graph editor, no live code reload of arbitrary processors.
- Not a replacement for `svc-admin-rest` for scripted/CI access — the REST surface stays the contract for automation.
- Not a metrics back-end: it scrapes what Mongoose and the JVM already expose; it does not ship Prometheus, Grafana, or a TSDB.
- No external storage. State is derived from the live server.

## 3. Relationship to existing plugins

| Plugin | Transport | Audience | Auth | Tab-completion / Discovery |
|---|---|---|---|---|
| `svc-admin-telnet` | TCP telnet, JLine | Operator on a shell | none (loopback bind expected) | `commandList()` via tab |
| `svc-admin-rest` | HTTP/JSON via Javalin | Scripts, curl, CI | NONE / BASIC / BEARER | none (clients must know commands) |
| **`svc-admin-web`** | HTTP + WebSocket (Javalin) | Human in a browser | NONE / BASIC / BEARER, plus session | UI lists, forms, completion |

`svc-admin-web` and `svc-admin-rest` both depend on Javalin and both expose HTTP. There are two viable arrangements:

- **(A) Independent module.** `svc-admin-web` runs its own Javalin instance on its own port. Simpler classpath, no cross-coupling, but two Jetty instances when both are configured.
- **(B) Compose on top of `svc-admin-rest`.** `svc-admin-rest` exposes its `Javalin` instance (or a router-mounting hook) and `svc-admin-web` registers WS handlers + static assets on it. Single port, single auth surface, but creates a hard runtime dependency.

**Recommendation: (A) for v1.** Keeps modules independent, matches the precedent of the telnet/REST split, and lets operators choose any combination. Re-evaluate composition once the UI ships and the duplication cost is measured.

## 4. User-visible features (v1)

### 4.1 Dashboard

- Server identity: artifact version, mongoose runtime version, host, PID, start time, uptime.
- Service inventory: name, class, lifecycle state (per `Lifecycle`), `ServiceRegistered` dependents where derivable.
- Processor inventory: registered processor groups (where surfaced by loaders).
- JVM snapshot: heap used / committed / max, non-heap, thread count, GC counts (via `ManagementFactory`).

### 4.2 Admin command runner

- Left pane: searchable, scrollable list of `adminCommandRegistry.commandList()`.
- Right pane:
  - Form to invoke selected command with positional args.
  - Per-session history of previously run commands.
  - Result panel for stdout / stderr (separately styled — same split as `AdminCommandRequest.output` vs `errOutput`).
- Quoted args / multi-word args supported (parity with telnet shell).

### 4.3 Live monitor

- WebSocket channel pushes periodic snapshots:
  - JVM metrics (1 Hz, configurable).
  - Per-queue depth for `EventFlowManager` queues when introspectable.
  - Tail of server log (opt-in; backed by a bounded ring buffer of recent log events).
- Pause / resume / clear controls per channel.

### 4.4 Cache inspector (conditional)

If `svc-cache` is on the registry (i.e. `cache.*` commands are present), surface a tab that wraps those commands as:
- list caches → `cache.list`
- list keys for a cache → `cache.{name}.keys`
- get value for a key → `cache.{name}.get <key>`
The tab is only shown when the relevant commands exist — pure discovery, no hard dependency on `svc-cache`.

### 4.5 Loader actions (conditional)

If `svc-loader-yaml` / `svc-loader-spring` are present, surface forms for their commonly-used commands (e.g. `yamlLoader.compileProcessor <path> <group>`) with a file picker constrained to a configured base directory.

### 4.6 Out of scope for v1

- Editing live configuration in place.
- Role-based access control beyond the single shared credential of v1.
- Multi-window persistent dashboards (just one page per browser tab).
- Notification / alerting.

## 5. Architecture

```
   Browser
      │   HTTP (forms, REST) + WebSocket (live monitor)
      ▼
┌────────────────────────────┐
│ Javalin (Jetty)            │
│  • static SPA (/)          │
│  • /api/* command surface  │
│  • /ws/* live channels     │
│  • auth filter             │
└──────────┬─────────────────┘
           │ invoke
           ▼
┌────────────────────────────┐         ┌──────────────────────┐
│ AdminCommandRegistry       │◀────────│ Mongoose services    │
│  (canonical command surface)│        │ (cache, loaders, …)  │
└────────────────────────────┘         └──────────────────────┘
           ▲
           │ periodic sampling
           ▼
┌────────────────────────────┐
│ MonitoringSampler          │
│  • JVM metrics             │
│  • queue snapshots         │
│  • log tail ring buffer    │
└────────────────────────────┘
```

### 5.1 Java entry point

```java
public class WebAdminService implements EventFlowService<Object>, Lifecycle {
    public enum AuthMode { NONE, BASIC, BEARER }

    // wiring
    @ServiceRegistered void adminRegistry(AdminCommandRegistry r, String name);

    // config
    @Getter @Setter int    listenPort = 8181;
    @Getter @Setter String host       = "127.0.0.1";
    @Getter @Setter String basePath   = "/";        // mount point for SPA
    @Getter @Setter AuthMode authMode = AuthMode.NONE;
    @Getter @Setter String username;
    @Setter         String password;
    @Setter         String bearerToken;
    @Getter @Setter String realm      = "mongoose-admin";

    // monitoring config
    @Getter @Setter int    metricsIntervalMs = 1000;   // clamped to >= 250 ms server-side
    @Getter @Setter int    logTailBuffer     = 500;
    @Getter @Setter String loaderBaseDir;              // restrict file picker; required if §4.5 tab is enabled

    // session (v1: HMAC cookie, no server-side store)
    @Setter         String sessionSecret;             // $ENV. resolvable
    @Getter @Setter int    sessionMinutes = 60;

    // ... init() / start() / tearDown() per Lifecycle
}
```

Same `$ENV.NAME` resolution pattern as `svc-admin-rest` (env var first, system property fallback). Same constant-time comparison. Same `init()`-time fail-fast on BASIC/BEARER misconfig.

### 5.2 HTTP surface (server side)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET`  | `/` (and SPA assets) | per `authMode` | Serve UI (classpath resources, see §6) |
| `GET`  | `/api/commands` | yes | `commandList()` — JSON array |
| `POST` | `/api/commands/{name}` | yes | Invoke command; body `{ "args": [...] }`; response `{ "output": ..., "err": ... }` |
| `GET`  | `/api/server` | yes | Server identity + uptime + version |
| `GET`  | `/api/services` | yes | Service inventory |
| `GET`  | `/api/jvm` | yes | One-shot JVM snapshot |
| `WS`   | `/ws/monitor` | yes | Streams metrics + queue snapshots |
| `WS`   | `/ws/logs` | yes | Streams log tail |
| `POST` | `/api/session/login` | per `authMode` | Exchange credentials for session cookie |
| `POST` | `/api/session/logout` | yes | Invalidate session |
| `GET`  | `/api/files` | yes | Enumerate files under `loaderBaseDir` for the loader file picker. Server rejects any path escape via `..` or symlink. |
| `GET`  | `/healthz` | none | Liveness probe — no auth, no body, always 200 while the service is running. |

Notes:
- `/api/commands/{name}` is a re-skinning of `svc-admin-rest`'s `/api/{action}` with a stable structured response — the goal is one place that the UI talks to, not parity with the REST plugin's URL scheme. If we later choose option (B) above (compose on top of `svc-admin-rest`), `/api/commands/{name}` becomes a UI-friendly wrapper over `/admin`.
- All output is JSON. Plain-text stdout from commands is wrapped as a string field, so the UI can render it monospaced.

### 5.3 WebSocket payloads

`/ws/monitor` emits at `metricsIntervalMs`:
```json
{
  "ts": 1715900000000,
  "jvm":   { "heapUsed": 1234, "heapMax": 8192, "threads": 47, "gc": [...] },
  "queues":[ { "name": "...", "depth": 12, "capacity": 1024 } ]
}
```
`/ws/logs` emits per appended record (debounced):
```json
{ "ts": 1715900000000, "level": "INFO", "logger": "...", "msg": "..." }
```
Backpressure: server-side bounded queue per WS connection; if a client lags, drop oldest with a single `{"dropped": N}` marker rather than buffering unbounded.

### 5.4 Authentication & session

- v1 supports `NONE`, `BASIC`, `BEARER` — identical semantics to `svc-admin-rest`.
- Browser UX requires a session: `POST /api/session/login` exchanges credentials for an HMAC-signed cookie (`HttpOnly`, `SameSite=Strict`, `Secure` when behind TLS). Cookie payload is `{userId, expiry}`; secret is `sessionSecret` (env-resolvable). No server-side session table.
- `sessionSecret` default: if unset, a random 256-bit secret is generated at `start()` and held in memory only. Sessions therefore invalidate on every restart, which is fine for v1; production deployments should set an env var so sessions survive restarts.
- All `/api/*` and `/ws/*` honour either the static credential header **or** a valid session cookie. The UI uses cookies; scripts can still use the header.
- CSRF: state-changing requests (`POST`) require an `X-CSRF-Token` header matching a per-session token surfaced by `/api/session/login`.
- **WebSocket upgrade.** The upgrade request reads the same cookie / header as `/api/*`. In addition, the server verifies the `Origin` header against a configurable allow-list (default: same host/port as `listenPort`) to prevent cross-origin WS hijack. CSRF token is passed as a query parameter on the upgrade URL (`/ws/monitor?csrf=...`) since browsers do not send custom headers on upgrade.

### 5.5 Lifecycle

- `init()`: validate config, wire dependencies.
- `start()`: bind Javalin, start `MonitoringSampler` (ScheduledExecutor), install log appender for the ring buffer.
- `tearDown()`: stop Javalin, stop sampler, remove log appender.

### 5.6 Threading

- Javalin owns Jetty threads.
- `MonitoringSampler` runs on a single `ScheduledExecutorService` with daemon threads named `svc-admin-web-monitor`.
- Command invocation is synchronous on the request thread (parity with `svc-admin-rest`). Long-running commands are an open question — see §10.

## 6. Frontend

### 6.1 Distribution model

Bundle the UI as **static resources inside the plugin JAR**, served from the classpath. Operators get a working UI by adding one dependency — no separate SPA build, no `staticDir` to configure. This matches the spirit of `svc-admin-telnet` (drop in, works).

`svc-admin-rest` already supports a `staticDir` for an externally-built SPA; that remains the right tool when a team wants their own UI. `svc-admin-web` is the curated default.

### 6.2 Stack

**Pinned for v1: htmx + Alpine.js, vanilla HTML, no node toolchain.**

Rationale: the surface area here is forms, tables, and WS-driven panels — exactly htmx's sweet spot. Server-rendered partial swaps cover the command runner, dashboard, and conditional tabs without client-side routing. Alpine handles the small amount of local component state (form open/closed, history dropdown). React/Vue/Svelte would be over-engineered for this UI and would introduce a node build step that the "drop-in, works" promise of §6.1 explicitly avoids.

Assets are checked into `src/main/resources/web/` as plain HTML/CSS/JS. htmx and Alpine are vendored (single `.min.js` each, ~30 KB combined) rather than CDN-loaded to keep §6.1's offline guarantee. Bundle budget: < 500 KB gzip total, works offline.

### 6.3 Build & packaging

- UI sources live directly under `src/main/resources/web/` (HTML, CSS, JS, vendored `htmx.min.js` + `alpine.min.js`).
- Standard Maven `resources` plugin packages them into the jar. No node toolchain, no `frontend-maven-plugin`, no separate build step.
- Javalin serves `/` and `/web/*` from the classpath via its built-in static file handler (`Location.CLASSPATH`).
- If the UI later outgrows this and needs a build pipeline, the directory layout is already compatible with a `target/classes/web/` output target.

## 7. Configuration

YAML example (lives alongside the other services):

```yaml
services:
  - name: adminWebService
    instance: !!com.telamin.mongoose.plugin.svc.adminweb.WebAdminService
      host: 127.0.0.1
      listenPort: 8181
      authMode: BASIC
      username: $ENV.ADMIN_USER
      password: $ENV.ADMIN_PASSWORD
      realm: mongoose-admin
      sessionSecret: $ENV.ADMIN_SESSION_SECRET
      metricsIntervalMs: 1000
      logTailBuffer: 500
      loaderBaseDir: /opt/mongoose/config
```

Defaults are chosen to be safe out of the box: loopback bind, `authMode: NONE` only for local dev — the README will make this explicit.

## 8. Dependencies

- `io.javalin:javalin:6.3.0` (already used by `svc-admin-rest`).
- `com.fasterxml.jackson.core:jackson-databind` (already on transitively via mongoose).
- No JLine — this module does not host a terminal.
- Log-tail integration: Mongoose uses `java.util.logging` (j.u.l). The ring buffer is fed via a `java.util.logging.Handler` attached to the root logger — no log4j dependency is required. If log4j2 / SLF4J is bridged to j.u.l in the consumer's stack, those records flow in too.
- No extra runtime deps for the frontend (assets are static).

## 9. Security considerations

- Default bind to `127.0.0.1`. Document the requirement to front with a reverse proxy + TLS for any non-loopback deployment.
- `authMode: NONE` is allowed (parity with `svc-admin-rest`) but the UI will display a banner warning when it is in effect.
- Session cookies: `HttpOnly`, `SameSite=Strict`, `Secure` when `X-Forwarded-Proto: https` is observed.
- CSRF token mandatory on POST.
- No request logging of credentials or session cookies. Constant-time credential comparison.
- WS upgrade requires the same auth as HTTP.
- Static asset directory is served from classpath only (no `Location.EXTERNAL`) — eliminates path-traversal misconfig.
- Loader file picker is **constrained to `loaderBaseDir`** — paths outside it are rejected server-side, not just hidden in UI.

## 10. Open questions

1. **Long-running admin commands.** Current `AdminCommandRequest.output` is a `Consumer<Object>` that may be called multiple times. v1 will buffer all outputs and return the full result; cancellation, streaming, and progress reporting are deferred until a real use case appears.
2. **Queue introspection.** Whether `EventFlowManager` exposes a stable API for listing queues and their depths needs confirmation. If not, the monitor view degrades gracefully to JVM-only.
3. **Service inventory source.** Is there a registry of services with their lifecycle state, or do we need to crawl `@ServiceRegistered` dependents? Spike before implementation.
4. **Sharing a port with `svc-admin-rest`.** Decision in §3 was (A); revisit after v1 if operators ask for it.
5. **i18n.** Out of scope for v1; UI strings live in a single map so a later PR can swap them.

## 11. Milestones

Status legend: ☐ planned · ◐ in progress · ☑ done

- **☑ M0 — spec sign-off.** Frontend stack pinned (htmx + Alpine.js), open questions resolved. Commit: `c5445cd`.
- **☑ M1 — module skeleton.** `service/svc-admin-web` Maven module, parent wired, empty `WebAdminService` with config + lifecycle, served at `/healthz` only. 3 tests green: healthz returns 200, metricsIntervalMs clamps to 250 ms, tearDown idempotent. Commit: `4e7d380`.
- **☑ M2 — auth + session.** BASIC + BEARER on `/api/*`, HMAC-signed `SessionToken` cookie (HttpOnly, SameSite=Strict, Secure when behind TLS), CSRF token on state-changing requests, `POST /api/session/{login,logout}`. `init()` fails fast on BASIC/BEARER misconfig. Random session secret generated per-JVM when unset. 18 tests green (12 service + 6 token round-trip / tamper / expiry / malformed). Commit: `6926c68`.
- **☑ M3 — admin command runner.** `GET /api/commands` returns `{commands: [...]}`. `POST /api/commands/{name}` invokes through `AdminCommandRegistry`, buffers `output` + `errOutput` consumer streams, returns `{command, output: [...], err: [...]}`. SPA shell at `/` (index.html + style.css + app.js) with htmx 2.0.4 + Alpine 3.14.8 vendored under `/vendor/`; left pane filterable command list, right pane args form + result pane + history. Example app now also registers `AdminCommandProcessor` + `MongooseServerAdmin` so the UI shows three real commands (`server.service.list`, `server.processors.list`, `server.processors.stop`) on boot. 23 tests green (5 new: list-commands populated/empty, invoke captures output, invoke blocked without CSRF, invoke unauth → 401). Commit: `08c1b88`. Follow-up fix: `62e2730` (Alpine script load order — `app.js` must load eagerly, not deferred, so the `alpine:init` listener attaches before Alpine starts).
- **☑ M4 — dashboard + JVM monitor.** `GET /api/server` returns identity (pid, runtime, startTime, uptimeMs). `GET /api/jvm` returns one-shot snapshot (heap, non-heap, threads, GC counts/times, empty queues — queue introspection deferred per §10.2). `WS /ws/monitor` pushes snapshots at `metricsIntervalMs`; CSRF carried as `?csrf=...` query param; `Origin` header allow-listed to same host:port. `MonitoringSampler` runs on a daemon-threaded `ScheduledExecutorService`. SPA: dashboard cards at the top of the page (server + JVM); WS connection auto-opens after auth, status pill shows "connecting…/live/closed/error"; `formatBytes` + `formatUptime` helpers for human-readable display. 26 tests green (3 new: `/api/server`, `/api/jvm`, unauth-blocked). Commit: `4417ec2`.
- **☑ M5 — log tail.** `LogTail` ring buffer (capacity = `logTailBuffer`, default 500) + `j.u.l.Handler` attached to root logger; `WS /ws/logs` replays buffered history on connect, then pushes per-record live; frame shape `{ts, level, logger, msg}` per spec §6. Handler skips records emitted by the `com.telamin.mongoose.plugin.svc.adminweb` package to avoid feedback loops. SPA: log panel below dashboard with level dropdown (ALL / INFO+ / WARN+ / ERROR), substring filter, auto-scroll toggle, clear button; rendered as a fixed-height pre with monospace grid (ts | level | logger | msg). 34 tests green (5 new LogTail + 3 new service: buffer-config, lifecycle smoke, WS auth-gated). Commit: `88ad7b6`.
- **☑ M6 — conditional tabs.** Cache + Loader panels surface as full-width sections when the corresponding commands are present (predicates `hasCacheCommands`, `hasYamlLoader`, `hasSpringLoader` against `/api/commands`). Cache panel exposes `cache.list`, `cache.{name}.keys`, `cache.{name}.get` actions; loader panel exposes `yamlLoader.compileProcessor` and `springLoader.compileProcessor` forms with optional file picker. `GET /api/files` is mounted unconditionally but returns 404 when `loaderBaseDir` is unset (so the "browse…" button hides automatically). Path-traversal hardening: reject absolute paths and `..` segments up front; `toRealPath` + `startsWith(base)` after resolve catches symlink escapes. New `invokeRaw(name, args)` helper isolates panel actions from the main command runner's UI state. 39 tests green (5 new file picker: 404 when unset, lists entries, rejects `..`, rejects absolute, blocked unauth). Commit: tbd.
- **☐ M7 — docs + README + screenshots.** Sibling-style README in `service/svc-admin-web/`, mkdocs page under `docs/`.

*Each milestone is updated in place when work starts (`◐`) and again when it lands (`☑`) with the commit hash. The doc is the running log of where we are.*

## 12. Acceptance for v1

A fresh checkout, after `mvn install`, can:

1. Add `svc-admin-web` to a YAML service config.
2. Start the server.
3. Open `http://127.0.0.1:8181` in a browser.
4. See the dashboard populated, run an admin command, watch live JVM metrics, tail logs — without configuring any external assets.

When `svc-cache` and a loader plugin are also configured, the corresponding tabs appear automatically.

**Negative-path acceptance (must also hold):**
- With `authMode: BASIC` set, anonymous `GET /api/commands` returns `401` and the UI redirects to a login form.
- A `POST /api/commands/{name}` without a valid CSRF token returns `403`.
- A WebSocket upgrade with no auth or a mismatched `Origin` is rejected before the channel opens.
- When `svc-cache` and the loader plugins are *not* configured, their tabs do not appear in the UI and the relevant `/api/*` endpoints (if any are tab-specific) return `404`.
- `loaderBaseDir` path-escape attempts (`../etc/passwd`, symlinks pointing outside) return `400` server-side.
