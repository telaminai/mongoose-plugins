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
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;

import java.io.IOException;
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

    private void shell(Terminal connTerminal, Map<String, String> environment) {
        // JLine's telnet builtin hands the shell a per-connection terminal
        // with type "default" and size 0x0 — its TERMINAL-TYPE and NAWS
        // negotiations rarely resolve in time, and on this pseudo-terminal
        // `setSize` does not stick. Against that, LineReader's full-screen
        // prompt renderer collapses to garbage (`>....` instead of
        // `command > `) and line editing does not accept input. Wrap the
        // connection's input / output streams in a Terminal we control —
        // type `xterm`, fixed 120x40 — and give that to the LineReader.
        // JLine telnet's TelnetIO layer still handles the IAC negotiation on
        // the underlying socket; we are only swapping the front-end the line
        // editor sees.
        Terminal lineTerminal = null;
        try {
            lineTerminal = TerminalBuilder.builder()
                    .type("xterm")
                    .streams(connTerminal.input(), connTerminal.output())
                    .system(false)
                    .name("telnet-line")
                    .size(new Size(120, 40))
                    .build();
            final Terminal t = lineTerminal;

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(t)
                    .completer((reader1, line, candidates) -> {
                        for (String string : adminCommandRegistry.commandList()) {
                            candidates.add(new Candidate(AttributedString.stripAnsi(string), string, null, null, null, null, true));
                        }
                        candidates.add(new Candidate(AttributedString.stripAnsi("quit"), "quit", null, null, null, null, true));
                    })
                    .build();

            processCommand(t, new String[]{"commands"});
            while (true) {
                String line;
                try {
                    line = reader.readLine("command > ");
                } catch (EndOfFileException eof) {
                    break;                              // client disconnected
                } catch (UserInterruptException ui) {
                    continue;                           // Ctrl-C — drop the line, keep the session
                }
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    t.writer().print("bye\r\n");
                    t.writer().flush();
                    break;
                }
                reader.getHistory().add(line);
                processCommand(t, line.split("\\s+"));
            }
        } catch (Exception e) {
            log.error("problem executing shell", e);
        } finally {
            if (lineTerminal != null) {
                try {
                    lineTerminal.close();
                } catch (IOException ignored) {
                }
            }
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
