/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.plugin.svc.jdbc.impl;

import com.telamin.mongoose.plugin.svc.jdbc.JdbcConnectionLoader;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import lombok.extern.log4j.Log4j2;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named JDBC connection registry. Each entry can either be served from a HikariCP
 * pool ({@code pooled=true}, default) or as fresh {@code DriverManager} connections.
 * <p>
 * Pools are created lazily on first {@link #getConnection(String)} and torn down on
 * {@link #tearDown()}.
 */
@Data
@Log4j2
public class JdbcConnectionLoaderService implements Lifecycle, JdbcConnectionLoader {

    private Map<String, JdbcConnectionConfig> connections = new HashMap<>();
    private boolean testConnection = false;
    private boolean fastFail = false;

    private final Map<String, HikariDataSource> pools = new LinkedHashMap<>();

    @Override
    public void init() {
        log.info("registered connections {}", connections.keySet());
    }

    @Override
    public void start() {
        if (!testConnection) {
            return;
        }
        log.info("starting connection test for {}", connections.keySet());
        for (String name : connections.keySet()) {
            try (Connection conn = getConnection(name)) {
                if (conn == null) {
                    throw new SQLException("getConnection returned null for " + name);
                }
                log.info("connection ok: {}", name);
            } catch (SQLException e) {
                log.warn("failed to connect to {}", name, e);
                if (fastFail) {
                    throw new RuntimeException("failed to connect to " + name, e);
                }
            }
        }
    }

    @Override
    public Connection getConnection(String name) throws SQLException {
        JdbcConnectionConfig cfg = connections.get(name);
        if (cfg == null) {
            log.warn("no connection registered with name:{}", name);
            return null;
        }
        if (cfg.isPooled()) {
            return poolFor(name, cfg).getConnection();
        }
        return DriverManager.getConnection(cfg.getUrl(), cfg.getUsername(), cfg.getPassword());
    }

    private synchronized HikariDataSource poolFor(String name, JdbcConnectionConfig cfg) {
        HikariDataSource existing = pools.get(name);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.getUrl());
        if (cfg.getUsername() != null) hc.setUsername(cfg.getUsername());
        if (cfg.getPassword() != null) hc.setPassword(cfg.getPassword());
        hc.setMaximumPoolSize(cfg.getMaximumPoolSize());
        hc.setMinimumIdle(cfg.getMinimumIdle());
        hc.setConnectionTimeout(cfg.getConnectionTimeoutMs());
        hc.setIdleTimeout(cfg.getIdleTimeoutMs());
        hc.setMaxLifetime(cfg.getMaxLifetimeMs());
        hc.setPoolName(cfg.getPoolName() != null ? cfg.getPoolName() : "mongoose-jdbc-" + name);
        if (cfg.getValidationQuery() != null && !cfg.getValidationQuery().isEmpty()) {
            hc.setConnectionInitSql(cfg.getValidationQuery());
            hc.setConnectionTestQuery(cfg.getValidationQuery());
        }
        HikariDataSource ds = new HikariDataSource(hc);
        pools.put(name, ds);
        log.info("created pool {} max:{} min:{} url:{}",
                hc.getPoolName(), hc.getMaximumPoolSize(), hc.getMinimumIdle(), cfg.getUrl());
        return ds;
    }

    /**
     * For tests and diagnostics. Returns the live pool for a name, or {@code null}
     * if no pool has been opened (or the entry is not pooled).
     */
    public HikariDataSource getPool(String name) {
        return pools.get(name);
    }

    @Override
    public synchronized void tearDown() {
        for (Map.Entry<String, HikariDataSource> e : pools.entrySet()) {
            try {
                e.getValue().close();
                log.info("closed pool {}", e.getKey());
            } catch (Exception ex) {
                log.warn("failed to close pool {}", e.getKey(), ex);
            }
        }
        pools.clear();
    }
}
