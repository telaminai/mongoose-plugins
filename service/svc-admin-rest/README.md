# svc-admin-rest

[Javalin](https://javalin.io)-backed REST admin endpoint for a running Mongoose server. Exposes the registered admin commands (from `AdminCommandRegistry`) over HTTP so external tools can drive the server without a telnet session.

Pair it with `svc-loader-yaml` / `svc-loader-spring` to reload processor graphs over the wire, or with `svc-cache` to inspect cached state from a browser.

Built against `io.javalin:javalin:6.3.0` (Jetty + Jakarta EE namespace).

## Endpoints

| Method | Path | Body | Effect |
|---|---|---|---|
| `POST` | `/admin` | `AdminCommandRequest` JSON | Run a registered admin command. Output and error streams are returned as JSON. |
| `POST` | `/api/{action}` | depends on action | Per-action endpoints (set up by your processors via the `AdminCommandRegistry`). |

If `staticDir` is configured the server also serves static files from that directory at `/`.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-admin-rest</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — YAML config

```yaml
services:
  - name: adminRestService
    service: !!com.telamin.mongoose.plugin.svc.adminrest.JavalinAdminCommandService
      host: 127.0.0.1
      listenPort: 8080
      staticDir: ./web/dist
      authMode: BASIC
      username: $ENV.ADMIN_USER
      password: $ENV.ADMIN_PASSWORD
      realm: mongoose-admin
```

Invoke an admin command:

```bash
curl -u "$ADMIN_USER:$ADMIN_PASSWORD" -X POST http://localhost:8080/admin \
  -H 'Content-Type: application/json' \
  -d '{"command":"cache.state-cache.keys","args":[]}'
```

## Authentication

| `authMode` | Required config           | Header                              |
|------------|---------------------------|-------------------------------------|
| `NONE` (default) | —                   | _none — public_                     |
| `BASIC`    | `username`, `password`    | `Authorization: Basic <base64>`     |
| `BEARER`   | `bearerToken`             | `Authorization: Bearer <token>`     |

- Username, password, and bearer token all support `$ENV.NAME` resolution (env-var first, falls back to system property).
- Comparisons are constant-time (no early-exit timing leak).
- `init()` throws `IllegalStateException` if you select `BASIC`/`BEARER` but leave the credentials empty — fail fast rather than ship a permissive misconfig.
- Failed auth returns `401` with a `WWW-Authenticate` header and a JSON `{"message":"unauthorized"}` body. No state hits the `AdminCommandRegistry`.

## Bind host

`host` defaults to `0.0.0.0` (all interfaces). For ops endpoints, bind to `127.0.0.1` and front with a reverse proxy that handles TLS + IP allow-listing.

## Notes

- The service implements `EventFlowService<Object>` to fit the Mongoose service-injection model. It does not push events into the dispatch pipeline — the `subscribe`/`unSubscribe`/`setEventToQueuePublisher` overrides are intentional no-ops.
- `staticDir` is loaded via Javalin's `Location.EXTERNAL` — point it at a directory on disk (e.g. a built SPA).
- Port defaults to 8080. Make it explicit in deployments so multiple Mongoose instances on the same host don't collide.
- Javalin uses SLF4J; without a binding on the classpath you'll see "no logger" notices. Add `org.slf4j:slf4j-simple` (test) or `org.apache.logging.log4j:log4j-slf4j2-impl` (prod) to silence.

## Tests

10 tests cover: no-auth pass-through, BASIC missing/wrong/correct creds, BEARER missing/wrong/correct token, `WWW-Authenticate` header, mis-configured BASIC/BEARER `init()` throws, and idempotent teardown.

## Related

- [`svc-admin-telnet`](../svc-admin-telnet/) — same admin surface over a telnet line protocol; lighter dependency footprint, no SPA hosting.
- [`svc-loader-yaml`](../svc-loader-yaml/) / [`svc-loader-spring`](../svc-loader-spring/) — the commands surfaced here are typically those registered by the loaders.
