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
    instance: !!com.fluxtion.dataflow.serverplugin.svc.adminrest.JavalinAdminCommandService
      listenPort: 8080
      staticDir: ./web/dist
```

Invoke an admin command:

```bash
curl -X POST http://localhost:8080/admin \
  -H 'Content-Type: application/json' \
  -d '{"command":"cache.state-cache.keys","args":[]}'
```

## Notes

- The service implements `EventFlowService<Object>` to fit the Mongoose service-injection model. It does not push events into the dispatch pipeline — the `subscribe`/`unSubscribe`/`setEventToQueuePublisher` overrides are intentional no-ops.
- `staticDir` is loaded via Javalin's `Location.EXTERNAL` — point it at a directory on disk (e.g. a built SPA).
- Port defaults to 8080. Make it explicit in deployments so multiple Mongoose instances on the same host don't collide.

## Related

- [`svc-admin-telnet`](../svc-admin-telnet/) — same admin surface over a telnet line protocol; lighter dependency footprint, no SPA hosting.
- [`svc-loader-yaml`](../svc-loader-yaml/) / [`svc-loader-spring`](../svc-loader-spring/) — the commands surfaced here are typically those registered by the loaders.
