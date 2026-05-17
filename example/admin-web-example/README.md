# admin-web-example

Boots a Mongoose server with `svc-admin-web` registered, so you can iterate on the UI in a real browser while the plugin develops through M1–M7.

> **Interim home.** This project lives under `mongoose-plugins/example/` during svc-admin-web development. It is **not** wired into the parent pom's `<modules>` list, so the main build stays unaffected. After `svc-admin-web` ships in `0.2.13`, it moves to `mongoose-examples/plugins/admin-web-example/`.

## Run

From this directory:

```sh
# one-time, or after any change to svc-admin-web sources
mvn -f ../../pom.xml -pl service/svc-admin-web -am install -DskipTests

# boot the example
mvn exec:exec
```

Then open:

- `http://127.0.0.1:8181/healthz` — M1; returns `OK`.
- `http://127.0.0.1:8181/` — populated in M3 onward.

`Ctrl-C` cleanly stops the Mongoose server via a shutdown hook.

## What you see at each milestone

| Milestone | What works |
|---|---|
| **M1** | `/healthz` returns `200 OK`. No UI yet. |
| **M2** | `/api/session/login` accepts credentials; `/api/*` and `/ws/*` honour cookie + CSRF. |
| **M3** | Command list at `/api/commands`; minimal HTML page lists and invokes commands. |
| **M4** | Dashboard, JVM monitor over WebSocket. |
| **M5** | Log tail panel. |
| **M6** | Cache + loader tabs appear when those plugins are configured. |
| **M7** | Polished, documented, screenshots. |

## Notes

- `AuthMode.NONE` is set for local development. The UI will surface a banner in later milestones whenever auth is disabled.
- To exercise auth in M2+, edit `AdminWebExample.java` and set `adminWeb.setAuthMode(AuthMode.BASIC)` + username/password (or read from env per `$ENV.NAME` pattern).
- This example registers only `WebAdminService`. As more milestones land (cache inspector, loader forms), add `svc-cache` / `svc-loader-yaml` / etc. to see the conditional tabs activate.
