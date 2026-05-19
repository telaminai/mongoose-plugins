/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.admintelnet;


import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.jline.builtins.telnet.Telnet;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Log4j2
public class TelnetAdminCommandProcessor implements Lifecycle {
    private AdminCommandRegistry adminCommandRegistry;
    @Getter
    @Setter
    private int listenPort = 2019;
    @Getter
    @Setter
    private String interfaceName = "127.0.0.1";
    private Telnet telnet;

    public TelnetAdminCommandProcessor(int listenPort) {
        this.listenPort = listenPort;
    }

    public TelnetAdminCommandProcessor() {
    }

    @ServiceRegistered
    public void adminRegistry(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("Admin registry: '{}' name: '{}'", adminCommandRegistry, name);
        this.adminCommandRegistry = adminCommandRegistry;
    }

    @Override
    public void init() {
        if (listenPort <= 0 || listenPort > 65535) {
            throw new IllegalStateException("listenPort out of range: " + listenPort);
        }
        if (interfaceName == null || interfaceName.isEmpty()) {
            throw new IllegalStateException("interfaceName must not be empty");
        }
    }

    @Override
    public void start() {
        try {
            log.info("Starting Jline admin command service interface:{} port:{}", interfaceName, listenPort);
            Terminal terminal = TerminalBuilder.terminal();
            telnet = new Telnet(terminal, this::shell);
            telnet.telnetd(new String[]{"telnetd", "-i" + interfaceName, "-p" + listenPort, "start"});
        } catch (Exception e) {
            log.error("problem starting Jline admin command service", e);
            telnet = null;
        }
    }

    @Override
    public void tearDown() {
        if (telnet == null) {
            return;
        }
        try {
            log.info("Stopping Jline admin command service port: {}", listenPort);
            telnet.telnetd(new String[]{"stop"});
        } catch (Exception e) {
            log.error("problem stopping Jline admin command service", e);
        } finally {
            telnet = null;
        }
    }

    private void shell(Terminal terminal, Map<String, String> environment) {
        // JLine's interactive LineReader is deliberately not used here. JLine's
        // own telnet builtin (`org.jline.builtins.telnet.Telnet`) creates the
        // per-connection terminal with type "default" and size 0x0 (the NAWS
        // negotiation rarely resolves a real size in time, and on this PTY
        // terminal `setSize` does not stick). Against that, the LineReader's
        // full-screen prompt rendering collapses to garbage — the operator
        // sees something like ">...." instead of "command > " and typed
        // characters neither echo nor register. Direct writes to the terminal
        // work fine, so a simple character-at-a-time line reader is the
        // robust path. Tab-completion is lost (cheap trade); the same admin
        // command surface is reachable through the web console for richer UX.
        final PrintWriter writer = terminal.writer();
        final NonBlockingReader in = terminal.reader();
        try {
            writer.print("Mongoose admin console — type 'help' for commands, 'quit' to exit.\r\n");
            writer.flush();
            processCommand(terminal, new String[]{"commands"});

            while (true) {
                writer.print("command > ");
                writer.flush();
                String line = readLine(in, writer);
                if (line == null) {
                    break;                              // client disconnected
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    writer.print("bye\r\n");
                    writer.flush();
                    break;
                }
                processCommand(terminal, line.split("\\s+"));
            }
        } catch (Exception e) {
            log.error("problem executing shell", e);
        }
    }

    /**
     * Reads one line from a character-at-a-time telnet connection, echoing
     * each printable character back to the client. Telnet was negotiated in
     * server-echo mode (IAC WILL ECHO + SUPPRESS-GO-AHEAD), so without
     * server-side echo the operator sees nothing as they type.
     * <p>
     * Telnet ends a line with CR LF or CR NUL; we treat CR as the line end
     * and consume a single trailing LF/NUL via the NonBlockingReader peek.
     * Backspace (BS / DEL) erases the last buffered character; other control
     * bytes are ignored. Returns {@code null} when the stream closes.
     */
    private static String readLine(NonBlockingReader in, PrintWriter writer) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == -1 || c == NonBlockingReader.READ_EXPIRED) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\r' || c == '\n') {
                if (c == '\r') {
                    int next = in.peek(50L);
                    if (next == '\n' || next == 0) {
                        in.read();
                    }
                }
                writer.print("\r\n");
                writer.flush();
                return sb.toString();
            }
            if (c == 0x7f || c == 0x08) {               // DEL / BS
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                    writer.print("\b \b");
                    writer.flush();
                }
                continue;
            }
            if (c < 0x20) {                              // other control bytes
                continue;
            }
            sb.append((char) c);
            writer.write(c);                             // server-side echo
            writer.flush();
        }
    }

    private void processCommand(Terminal terminal, String[] commandArgs) {
        if (commandArgs == null || commandArgs.length == 0 || commandArgs[0].isEmpty()) {
            return;
        }
        AdminCommandRequest adminCommandRequest = new AdminCommandRequest();
        List<String> commandArgsList = new ArrayList<>(Arrays.asList(commandArgs));
        commandArgsList.remove(0);

        adminCommandRequest.setCommand(commandArgs[0]);
        adminCommandRequest.setArguments(commandArgsList);
        adminCommandRequest.setOutput(terminal.writer()::println);
        adminCommandRequest.setErrOutput(terminal.writer()::println);

        log.info("adminCommandRequest: " + adminCommandRequest);
        if (adminCommandRegistry != null) {
            adminCommandRegistry.processAdminCommandRequest(adminCommandRequest);
        }
    }
}
