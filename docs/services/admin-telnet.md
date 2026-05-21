# svc-admin-telnet

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
</span>

!!! warning "Last-resort interactive admin"
    The primary interactive command surface is the **Console panel in
    [`svc-admin-web`](admin-web.md)** — proper terminal rendering, tab
    completion, history, browser-native, no client install. Use this
    telnet endpoint only when the web console is unreachable (headless
    boxes, restricted networks, scripted ops).

JLine-backed telnet admin endpoint for a running Mongoose server. Plain telnet line protocol with server-side echo. Tab-completion + line history are present in the line editor but their behaviour over the wire is client-dependent — proper char-mode telnet clients see them work, `nc` / BSD-telnet-in-line-mode treats them as literal bytes.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-admin-telnet</artifactId>
    <version>{{ plugin_version }}</version>
</dependency>
```

## Sample

```yaml
services:
  - name: adminTelnet
    service: !!com.telamin.mongoose.plugin.svc.admintelnet.TelnetAdminCommandProcessor
      listenPort: 2024
      interfaceName: 127.0.0.1
```

Connect:

```bash
telnet 127.0.0.1 2024
```

`commands` lists every registered admin command; `help` / `?` prints the built-in shortlist; `quit` (or `exit`) closes the session. Tab-complete is enabled against the live `AdminCommandRegistry` but only fires for clients that put the connection into character-at-a-time mode; for line-mode clients (the macOS BSD `telnet` default, `nc`) the `\t` byte is treated as a literal — switch to `svc-admin-web`'s Console panel for reliable autocomplete.

## Configuration reference

| Field           | Default     | Notes                                                |
|-----------------|-------------|------------------------------------------------------|
| `listenPort`    | `2019`      | TCP port (1-65535)                                   |
| `interfaceName` | `127.0.0.1` | Bind interface — **loopback by default**             |

## Operational notes

- **Loopback is the default**: a stock deployment is not exposed beyond the host. Override to `0.0.0.0` only behind a TLS proxy with auth.
- `init()` rejects out-of-range ports and empty/null interface names.
- `tearDown()` is safe to call without `start()` having been called.
- Telnet is plain-text; there is no auth layer on the protocol itself. **Do not** bind to a routable interface in production.

## Examples

- **[how-to/writing-an-admin-command](https://github.com/telaminai/mongoose-examples/tree/main/how-to/writing-an-admin-command)** — register an admin command; `svc-admin-telnet` then surfaces it on the telnet shell, and `svc-admin-web` lists it in the command runner panel.

## Source

[`mongoose-plugins/service/svc-admin-telnet`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-admin-telnet)
