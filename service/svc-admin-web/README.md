# svc-admin-web

Browser-based admin & monitoring UI for a running Mongoose server. Presentation layer over `AdminCommandRegistry` — the same command surface that `svc-admin-telnet` and `svc-admin-rest` drive.

## Status

**M1 (in progress):** module skeleton with `/healthz` only. Run the test, hit `http://127.0.0.1:8181/healthz`, get `200 OK`. No UI yet, no auth, no command surface.

See [`design/svc-admin-web.md`](../../design/svc-admin-web.md) for the full spec and milestone tracking.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-admin-web</artifactId>
    <version>0.2.13-SNAPSHOT</version>
</dependency>
```
