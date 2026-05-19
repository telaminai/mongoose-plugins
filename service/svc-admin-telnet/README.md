# svc-admin-telnet

JLine-backed telnet admin endpoint for a running Mongoose server. Exposes the registered admin commands (from `AdminCommandRegistry`) over a plain line protocol — one command per line, server-echoed, with backspace editing.

Pair it with `svc-loader-yaml` / `svc-loader-spring` to reload processor graphs from a shell, or with `svc-cache` to inspect cache state interactively. For a richer UX (command discovery, dashboards, log tail) use `svc-admin-web`; this transport is the always-available terminal fallback.

Built against `org.jline:jline` (its builtin telnet server handles the socket and IAC negotiation); the line editor is a small in-house char-echo loop, deliberately not JLine's `LineReader` (see [Notes](#notes)).

## Capabilities

- Telnet listener on a configurable port (default `2019`).
- One command per line; server-side echo and backspace editing.
- `help` lists default commands; `commands` lists every registered admin command (services populate the registry at startup, so it is always current).

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-admin-telnet</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — YAML config

```yaml
services:
  - name: adminTelnetService
    service: !!com.telamin.mongoose.plugin.svc.admintelnet.TelnetAdminCommandProcessor
      listenPort: 2024
      welcomeMessage: "Mongoose admin (telnet). Type 'help' for commands."
```

Then from any shell on the same host:

```bash
telnet localhost 2024
> help
> cache.state-cache.keys
> yamlLoader.compileProcessor ./config/new-processor.yaml my-group
```

## Notes

- **No authentication.** Bind to `127.0.0.1` or firewall-scope the port; do not expose telnet to the open internet.
- **JLine's `LineReader` is intentionally not used.** JLine's own telnet builtin creates the per-connection terminal with type `default` and size `0x0` — its NAWS (window-size) negotiation rarely resolves a real size in time, and `setSize` does not stick on that PTY. Against zero width, the `LineReader`'s full-screen prompt rendering collapses to garbage (`>....` instead of `command > `) and typed characters neither echo nor register. We keep JLine for the socket / IAC layer and run a small char-by-char read+echo loop on top — robust on macOS BSD telnet, GNU inetutils telnet, and `nc`.
- **Tab-completion and history were removed** with the rewrite. They depended on the `LineReader` path. Use `svc-admin-web` for discoverable command panels.
- **Commands populate themselves.** Other services (the loaders, svc-cache, svc-admin-rest's siblings) register commands via `AdminCommandRegistry` at startup; the telnet service discovers them dynamically.

## Related

- [`svc-admin-rest`](../svc-admin-rest/) — same admin surface over HTTP (Javalin), better for browser dashboards and curl-from-CI.
- [`svc-loader-yaml`](../svc-loader-yaml/) / [`svc-loader-spring`](../svc-loader-spring/) — the commands typically surfaced here.
