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
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;

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

    private void shell(Terminal connTerminal, Map<String, String> environment) {
        // Use the per-connection terminal JLine telnet hands us directly. A
        // real telnet client (BSD telnet, GNU inetutils, PuTTY) negotiates
        // TERMINAL-TYPE + NAWS, so this PTY ends up with a sane type ("xterm"
        // or whatever $TERM is on the client) and the client's actual window
        // size — LineReader's prompt rendering + key bindings then work
        // properly. Earlier this method wrapped the connection in an
        // ExternalTerminal with .system(false) to force-set type/size, which
        // recovered display geometry against a non-negotiating probe (raw
        // socket) but silently disabled JLine's keymap layer: TAB and Ctrl-C
        // fell through as literal bytes. The wrap is gone; the trade is that
        // a non-negotiating client (`nc`) sees the original ">...." prompt
        // glitch, but the real telnet UX (tab-complete, line editing) works.
        //
        // `wireWriter` is the per-connection writer (the one TelnetIO drives
        // directly). Admin command output goes through it with an explicit
        // flush so handlers that run on a dispatcher thread don't queue
        // bytes behind LineReader's next display cycle.
        final PrintWriter wireWriter = connTerminal.writer();
        try {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(connTerminal)
                    .completer((reader1, line, candidates) -> {
                        for (String string : adminCommandRegistry.commandList()) {
                            candidates.add(new Candidate(AttributedString.stripAnsi(string), string, null, null, null, null, true));
                        }
                        candidates.add(new Candidate(AttributedString.stripAnsi("quit"), "quit", null, null, null, null, true));
                    })
                    .build();

            processCommand(wireWriter, new String[]{"commands"});
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
                    wireWriter.print("bye\r\n");
                    wireWriter.flush();
                    break;
                }
                reader.getHistory().add(line);
                processCommand(wireWriter, line.split("\\s+"));
            }
        } catch (Exception e) {
            log.error("problem executing shell", e);
        }
    }

    private void processCommand(PrintWriter wireWriter, String[] commandArgs) {
        if (commandArgs == null || commandArgs.length == 0 || commandArgs[0].isEmpty()) {
            return;
        }
        AdminCommandRequest adminCommandRequest = new AdminCommandRequest();
        List<String> commandArgsList = new ArrayList<>(Arrays.asList(commandArgs));
        commandArgsList.remove(0);

        adminCommandRequest.setCommand(commandArgs[0]);
        adminCommandRequest.setArguments(commandArgsList);
        // Flush after every line — handler may run on a dispatcher thread
        // (when AdminCommandProcessor's start() registered the command
        // under a processor context, registrations go through an event
        // queue and execute on the dispatcher agent), so the writes are
        // off the shell thread and need to be pushed through immediately
        // rather than waiting for the LineReader's next display cycle.
        adminCommandRequest.setOutput(line -> {
            wireWriter.println(line);
            wireWriter.flush();
        });
        adminCommandRequest.setErrOutput(line -> {
            wireWriter.println(line);
            wireWriter.flush();
        });

        log.info("adminCommandRequest: " + adminCommandRequest);
        if (adminCommandRegistry != null) {
            adminCommandRegistry.processAdminCommandRequest(adminCommandRequest);
        }
    }
}
