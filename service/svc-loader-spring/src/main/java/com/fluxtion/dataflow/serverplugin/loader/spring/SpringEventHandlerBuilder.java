/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.fluxtion.dataflow.serverplugin.loader.spring;


import com.fluxtion.dataflow.extern.spring.FluxtionSpring;
import com.fluxtion.dataflow.runtime.DataFlow;
import com.fluxtion.dataflow.runtime.annotations.feature.Preview;
import com.fluxtion.dataflow.runtime.audit.EventLogControlEvent;
import lombok.Data;

import java.nio.file.Path;
import java.util.function.Supplier;

@Preview
@Data
public class SpringEventHandlerBuilder<T extends DataFlow> implements Supplier<T> {

    private String springFile;
    private boolean addEventAuditor = true;
    private EventLogControlEvent.LogLevel auditTraceLogLevel;

    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        Path springFilePath = Path.of(springFile);
        if (!springFilePath.toFile().exists()) {
            throw new RuntimeException("File not found: " + springFile);
        }
        return (T) FluxtionSpring.compile(springFilePath, cfg -> {
            if (addEventAuditor && auditTraceLogLevel != null) {
                cfg.addEventAudit(auditTraceLogLevel);
            } else if (addEventAuditor) {
                cfg.addEventAudit();
            }
        });
    }
}
