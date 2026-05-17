/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.fluxtion.dataflow.serverplugin.svc.jdbc.impl;

import lombok.Data;
import lombok.ToString;

@Data
public class JdbcConnectionConfig {

    private String url;
    private String username;
    @ToString.Exclude
    private String password;

    /**
     * If true (default), connections for this entry are served from a HikariCP pool.
     * Set to false to fall back to fresh {@code DriverManager.getConnection} per call.
     */
    private boolean pooled = true;

    private int maximumPoolSize = 10;
    private int minimumIdle = 0;
    private long connectionTimeoutMs = 30_000L;
    private long idleTimeoutMs = 600_000L;
    private long maxLifetimeMs = 1_800_000L;
    private String poolName;
    /**
     * Optional SQL run on each new physical connection ({@code SELECT 1}, etc).
     * Hikari uses this both for {@code connectionInitSql} and validation.
     */
    private String validationQuery;

    public String getUsername() {
        if (username != null && username.startsWith("$ENV.")) {
            return resolveEnv(username);
        }
        return username;
    }

    public String getPassword() {
        if (password != null && password.startsWith("$ENV.")) {
            return resolveEnv(password);
        }
        return password;
    }

    private static String resolveEnv(String token) {
        String key = token.substring("$ENV.".length());
        String fromEnv = System.getenv(key);
        if (fromEnv != null) return fromEnv;
        return System.getProperty(token);
    }
}
