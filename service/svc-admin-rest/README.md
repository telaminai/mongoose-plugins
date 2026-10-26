# svc-admin-rest

[Javalin](https://javalin.io)-backed REST admin endpoint for a running Mongoose server. Exposes the registered admin commands (from `AdminCommandRegistry`) over HTTP so external tools can drive the server without a telnet session.

Pair it with `svc-loader-yaml` / `svc-loader-spring` to reload processor graphs over the wire, or with `svc-cache` to inspect cached state from a browser.

Built against `io.javalin:javalin:6.3.0` (Jetty + Jakarta EE namespace).

## Endpoints

| Method | Path | Body | Effect |
|---|---|---|---|
| `POST` | `/admin` | `{"command":"<name>","arguments":[...]}` | Run a registered admin command. Every reply is returned as one `{"message":"..."}`. |
| `POST` | `/api/{action}` | none | Run the registered command `{action}` with no arguments. The reply is its output as-is. |

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
  -d '{"command":"cache.state-cache.keys","arguments":[]}'
```

## Request and reply contract

`POST /admin` takes `{"command":"<name>","arguments":["a","b"]}`. `arguments` is optional; a missing or `null`
list is treated as empty. The field is `arguments`, not `args`.

| Status | When | Body |
|---|---|---|
| `200` | The command ran | `{"message":"..."}` (see below) |
| `400` | `/admin` body is empty, not JSON, not an object, has no `command`, has a field other than `command`/`arguments` (e.g. `args`), or `arguments` is not an array of strings | `{"message":"<what is wrong>; expected {\"command\":\"<name>\",\"arguments\":[...]}"}` |
| `404` | The registry does not list the command | `{"message":"unknown command '<name>'; ..."}` |
| `503` | No `AdminCommandRegistry` is wired | `{"message":"admin command registry not available"}` |
| `401` | Authentication failed (see below) | `{"message":"unauthorized"}` |

A command may reply several times. The endpoint collects every output and error reply made while the command runs
and answers once, when the command returns:

- `/admin` replies `{"message":"..."}` with the replies joined by `\n`. Error replies are prefixed `Failure - `.
  The status stays `200` when the command reported an error. A command that replied nothing gives `{"message":""}`.
- `/api/{action}` replies with the command's output as-is when it replied exactly once without an error: a `String`
  output is written as `text/plain`, any other output is serialised as JSON. Otherwise (several replies, an error, or
  no reply) it replies `{"message":"..."}` as `/admin` does.
- A reply that arrives after the command returned (for example a registry completion timeout reporting
  `cancelled`/`abandoned`) is logged and dropped; the HTTP response has already been sent.

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

`host` defaults to `127.0.0.1` (loopback), so a fresh install does not expose the admin API to the network.

**Off-box access** is one setting: set `host` to `0.0.0.0` (all interfaces) or to a specific interface address, and choose an `authMode`:

```yaml
      host: 0.0.0.0
      listenPort: 8080
      authMode: BEARER
      bearerToken: ${ADMIN_TOKEN}
```

If the endpoint is reachable off-box with `authMode: NONE`, `init()` logs a warning naming the host and port. Front an off-box endpoint with TLS (a reverse proxy) and IP allow-listing.

> **Upgrading:** before this release `host` defaulted to `0.0.0.0`. A deployment that relied on that default for off-box access must now set `host` explicitly.

## Browser safety with `authMode: NONE`

With no credentials, a page open in the operator's browser could otherwise drive the endpoint. In `NONE` mode:

- a request whose `Origin` header is present and is not this endpoint (`Origin` host:port must equal `Host`), or is
  `Origin: null`, gets `403`;
- a request with a body, or with a `Content-Type`, that is not `application/json` gets `415` (this blocks
  cross-site form and `text/plain` posts, which browsers send without a preflight);
- with a loopback bind, a request whose `Host` is not `localhost`, `127.x.x.x` or `::1` gets `403` (DNS rebinding).
  Add other names you reach it by (a local proxy, a DNS alias) to `allowedHosts`.

What a script must send: no `Origin` (curl, Python and Java clients send none), and `Content-Type: application/json`
with any body. A body-less `curl -X POST http://127.0.0.1:8080/api/help` still works.

```bash
curl -X POST http://127.0.0.1:8080/admin -H 'Content-Type: application/json' -d '{"command":"help","arguments":[]}'
```

`BASIC`/`BEARER` clients authenticate with the `Authorization` header and are not subject to these checks.

## Notes

- The service implements `EventFlowService<Object>` to fit the Mongoose service-injection model. It does not push events into the dispatch pipeline — the `subscribe`/`unSubscribe`/`setEventToQueuePublisher` overrides are intentional no-ops.
- `staticDir` is loaded via Javalin's `Location.EXTERNAL` — point it at a directory on disk (e.g. a built SPA).
- Port defaults to 8080. Make it explicit in deployments so multiple Mongoose instances on the same host don't collide.
- Javalin uses SLF4J; without a binding on the classpath you'll see "no logger" notices. Add `org.slf4j:slf4j-simple` (test) or `org.apache.logging.log4j:log4j-slf4j2-impl` (prod) to silence.

## Tests

`JavalinAdminCommandServiceTest` (32 tests) covers: authentication (NONE/BASIC/BEARER, `WWW-Authenticate`, mis-configured `init()` throws, idempotent teardown), running a command over `/admin` and `/api/{action}`, null or missing `arguments`, several replies joined into one response, late replies dropped, `404` for an unknown command, an empty `message` for a silent command, `400` wording, `503` without a registry, and the `/api` content type.

## Related

- [`svc-admin-telnet`](../svc-admin-telnet/) — same admin surface over a telnet line protocol; lighter dependency footprint, no SPA hosting.
- [`svc-loader-yaml`](../svc-loader-yaml/) / [`svc-loader-spring`](../svc-loader-spring/) — the commands surfaced here are typically those registered by the loaders.
