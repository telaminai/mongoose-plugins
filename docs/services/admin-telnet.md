# svc-admin-telnet

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
</span>

JLine-backed telnet admin endpoint for a running Mongoose server. Tab-completion against the registered admin command list, in-session history.

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

Hit `?` to list available commands; tab-complete is on.

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

- **[how-to/writing-an-admin-command](https://github.com/telaminai/mongoose-examples/tree/main/how-to/writing-an-admin-command)** — register an admin command; `svc-admin-telnet` then surfaces it via the telnet shell with tab-complete.

## Source

[`mongoose-plugins/service/svc-admin-telnet`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-admin-telnet)
