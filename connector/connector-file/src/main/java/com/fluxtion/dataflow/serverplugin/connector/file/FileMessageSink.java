/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.fluxtion.dataflow.serverplugin.connector.file;


import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

@Log4j2
public class FileMessageSink extends AbstractMessageSink<Object>
        implements Lifecycle {

    @Getter
    @Setter
    private String filename;
    private PrintStream printStream;
    @Getter
    @Setter
    private Supplier<Object> firstLineSupplier;

    @Override
    public void init() {
    }

    @SneakyThrows
    @Override
    public void start() {
        if (filename == null || filename.isEmpty()) {
            throw new IllegalStateException("FileMessageSink has no filename configured");
        }
        Path path = Paths.get(filename);
        boolean exists = Files.exists(path) && Files.size(path) > 0;
        File parent = path.toFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        printStream = new PrintStream(
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                false,
                StandardCharsets.UTF_8
        );

        if (!exists && firstLineSupplier != null) {
            Object firstLine = firstLineSupplier.get();
            if (firstLine != null) {
               printStream.print(firstLine);
            }
        }
    }

    @Override
    protected void sendToSink(Object value) {
        log.trace("sink publish:{}", value);
        printStream.println(value);
    }

    @Override
    public void stop() {
        printStream.flush();
        printStream.close();
    }

    @Override
    public void tearDown() {
        stop();
    }
}
