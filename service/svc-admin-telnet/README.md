# svc-admin-telnet

> **Last-resort interactive admin shell.** The primary interactive
> command surface for Mongoose is the **Console panel in `svc-admin-web`**
> — proper terminal rendering, tab completion, history, browser-native,
> no client install. Use this telnet endpoint only when the web console
> is unreachable (headless boxes, restricted networks, scripted ops).

JLine + telnet admin endpoint for a running Mongoose server. Exposes the registered admin commands (from `AdminCommandRegistry`) over a plain telnet line protocol. Best with a real telnet client in char mode; behaviour against `nc` or BSD telnet in line mode is best-effort (the line editor's tab/control keys require the JLine line-editor stack on top of a proper PTY, which not every telnet client negotiates the same way).

Pair it with `svc-loader-yaml` / `svc-loader-spring` to reload processor graphs from a shell, or with `svc-cache` to inspect cache state interactively. For day-to-day interactive admin, use **`svc-admin-web`** instead.

Built against `org.jline:jline` (terminal + line reader + builtin telnet server).

## Capabilities

- Telnet listener on a configurable port (default `2019`).
- Server-side echo + line editing via JLine's `LineReader`.
- Tab-completion against the set of commands currently registered in the `AdminCommandRegistry` — **client-dependent**; works against char-mode telnet clients that negotiate a real terminal type, falls through as a literal `\t` against `nc` / BSD telnet in line mode.
- Line history per session (same client-dependency caveat).
- `help` lists default commands; `commands` lists every registered admin command; quoted arguments and multi-word args are supported.

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
- **Per-connection LineReader, wrapped terminal.** Each new telnet connection gets its own JLine `LineReader`. JLine's telnet builtin hands the shell a per-connection terminal with type `default` and size `0x0` (its NAWS / TERMINAL-TYPE negotiations rarely resolve in time, and `setSize` doesn't stick on that PTY) — and against that, `LineReader`'s full-screen prompt rendering collapses to garbage and typed characters don't register. The shell rebuilds a `Terminal` in front of the same connection streams with type `xterm` and a fixed `Size(120, 40)`, then drives the `LineReader` against that wrapper; JLine's TelnetIO layer still handles IAC on the underlying socket.
- **Commands populate themselves.** Other services (the loaders, svc-cache, svc-admin-rest's siblings) register commands via `AdminCommandRegistry` at startup; the telnet service discovers them dynamically.

## Related

- [`svc-admin-rest`](../svc-admin-rest/) — same admin surface over HTTP (Javalin), better for browser dashboards and curl-from-CI.
- [`svc-loader-yaml`](../svc-loader-yaml/) / [`svc-loader-spring`](../svc-loader-spring/) — the commands typically surfaced here.
