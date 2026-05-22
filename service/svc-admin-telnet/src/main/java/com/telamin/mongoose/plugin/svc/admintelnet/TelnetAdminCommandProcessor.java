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
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Reference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;

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

    private void shell(Terminal connTerminal, Map<String, String> environment) {
        // JLine's telnet builtin hands the shell a per-connection PTY that,
        // against macOS BSD telnet, often arrives with type "default" and
        // size 0x0: TERMINAL-TYPE + NAWS negotiations don't always resolve
        // in time, and `setSize` on this PTY doesn't stick. LineReader's
        // full-screen prompt renderer then collapses to ">...." and edits
        // mis-render. Wrap the connection's streams in an ExternalTerminal
        // we control (type "xterm", fixed 120x40) and drive the LineReader
        // against the wrapper; JLine telnet's TelnetIO still handles IAC on
        // the socket underneath.
        //
        // The wrap with .system(false) is what JLine builds for stream
        // terminals — it doesn't get a real TTY, so the default keymap that
        // LineReaderImpl populates against the terminal's terminfo doesn't
        // pick up the standard emacs bindings (TAB / Ctrl-C / arrows /
        // history). We bind the essential keys explicitly after build —
        // unconditional, doesn't rely on the .system path.
        //
        // `wireWriter` is the per-connection writer (the one TelnetIO drives
        // directly). Admin command output goes through it with an explicit
        // flush so handlers that run on a dispatcher thread don't queue
        // bytes behind LineReader's next display cycle.
        final PrintWriter wireWriter = connTerminal.writer();
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

            // Explicit emacs-style key bindings on the active keymap. Without
            // this the wrapped ExternalTerminal's LineReader treats TAB +
            // Ctrl-C + arrow keys as literal self-insert bytes.
            wireDefaultKeyBindings(reader);

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
        } finally {
            if (lineTerminal != null) {
                try {
                    lineTerminal.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Binds the essential emacs-style keys on the LineReader's active main
     * keymap. {@link LineReaderBuilder} populates the keymap from the
     * terminal's terminfo when it builds against a {@code .system(true)}
     * terminal; against the wrapped {@code ExternalTerminal} here that path
     * is incomplete, so we apply the bindings explicitly. The widget names
     * are JLine's standard public constants — same handlers a system
     * terminal would invoke.
     */
    private static void wireDefaultKeyBindings(LineReader reader) {
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        if (main == null) return;
        main.bind(new Reference(LineReader.COMPLETE_WORD), "\t");
        main.bind(new Reference(LineReader.SEND_BREAK), KeyMap.ctrl('C'));
        main.bind(new Reference(LineReader.BACKWARD_DELETE_CHAR), KeyMap.del(), "\b");
        main.bind(new Reference(LineReader.ACCEPT_LINE), "\r", "\n");
        main.bind(new Reference(LineReader.UP_LINE_OR_HISTORY), KeyMap.key(reader.getTerminal(), org.jline.utils.InfoCmp.Capability.key_up));
        main.bind(new Reference(LineReader.DOWN_LINE_OR_HISTORY), KeyMap.key(reader.getTerminal(), org.jline.utils.InfoCmp.Capability.key_down));
        main.bind(new Reference(LineReader.BACKWARD_CHAR), KeyMap.key(reader.getTerminal(), org.jline.utils.InfoCmp.Capability.key_left));
        main.bind(new Reference(LineReader.FORWARD_CHAR), KeyMap.key(reader.getTerminal(), org.jline.utils.InfoCmp.Capability.key_right));
        main.bind(new Reference(LineReader.BEGINNING_OF_LINE), KeyMap.ctrl('A'));
        main.bind(new Reference(LineReader.END_OF_LINE), KeyMap.ctrl('E'));
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
