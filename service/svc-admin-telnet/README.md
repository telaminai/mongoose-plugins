# svc-admin-telnet

JLine + telnet admin endpoint for a running Mongoose server. Exposes the registered admin commands (from `AdminCommandRegistry`) over a telnet line protocol with tab-completion, history, and a familiar shell-style UX.

Pair it with `svc-loader-yaml` / `svc-loader-spring` to reload processor graphs from a shell, or with `svc-cache` to inspect cache state interactively.

Built against `org.jline:jline` (terminal + line reader + builtin telnet server).

## Capabilities

- Telnet listener on a configurable port (default `2019`).
- Tab-completion against the set of commands currently registered in the `AdminCommandRegistry`.
- Line history per session.
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
