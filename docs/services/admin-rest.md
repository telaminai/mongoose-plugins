# svc-admin-rest

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
</span>

[Javalin](https://javalin.io)-backed REST admin endpoint for a running Mongoose server. Exposes the registered admin commands (from `AdminCommandRegistry`) over HTTP so external tools can drive the server without a telnet session.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-admin-rest</artifactId>
    <version>{{ plugin_version }}</version>
</dependency>
```

## Endpoints

| Method | Path                | Body                       | Effect                                   |
|--------|---------------------|----------------------------|------------------------------------------|
| `POST` | `/admin`            | `{"command":"<name>","arguments":[...]}` | Run a registered admin command |
| `POST` | `/api/{action}`     | none                       | Run command `{action}` with no arguments; the reply is its output as-is |

If `staticDir` is configured the server also serves static files from that directory at `/`.

## Sample

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

The admin endpoints aren't injected into processors — they call back into the
shared `AdminCommandRegistry` — so registering under the concrete class is the
right choice here. Add `serviceClass: <interface-FQN>` only if you want to
abstract the endpoint type for a custom integration.

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

| `authMode`        | Required config        | Header                              |
|-------------------|------------------------|-------------------------------------|
| `NONE` (default)  | —                      | _none — public_                     |
| `BASIC`           | `username`, `password` | `Authorization: Basic <base64>`     |
| `BEARER`          | `bearerToken`          | `Authorization: Bearer <token>`     |

- Username, password, and bearer token support `$ENV.NAME` resolution.
- Comparisons are constant-time (no early-exit timing leak).
- `init()` throws `IllegalStateException` if you select `BASIC`/`BEARER` but leave credentials empty — fail fast.
- Failed auth returns `401` with a `WWW-Authenticate` header and a JSON `{"message":"unauthorized"}` body.

## Bind host

`host` defaults to `0.0.0.0` (all interfaces). For ops endpoints, bind to `127.0.0.1` and front with a reverse proxy that handles TLS + IP allow-listing.

## Configuration reference

| Field          | Default        | Notes                                          |
|----------------|----------------|------------------------------------------------|
| `host`         | `0.0.0.0`      | Bind address                                   |
| `listenPort`   | `8080`         | TCP port                                       |
| `staticDir`    | _unset_        | Optional directory served at `/`               |
| `authMode`     | `NONE`         | `NONE`, `BASIC`, or `BEARER`                   |
| `username`     | _unset_        | BASIC mode credential                          |
| `password`     | _unset_        | BASIC mode credential                          |
| `bearerToken`  | _unset_        | BEARER mode credential                         |
| `realm`        | `mongoose-admin` | WWW-Authenticate realm                       |

## Operational notes

- Implements `EventFlowService<Object>` but is not an event source — `subscribe`/`unSubscribe`/`setEventToQueuePublisher` are no-ops.
- Javalin uses SLF4J; add a binding (e.g. `log4j-slf4j2-impl`) to silence "no logger" notices in production.

## Examples

- **[how-to/writing-an-admin-command](https://github.com/telaminai/mongoose-examples/tree/main/how-to/writing-an-admin-command)** — register an admin command; `svc-admin-rest` then exposes it over HTTP.

## Source

[`mongoose-plugins/service/svc-admin-rest`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-admin-rest)
