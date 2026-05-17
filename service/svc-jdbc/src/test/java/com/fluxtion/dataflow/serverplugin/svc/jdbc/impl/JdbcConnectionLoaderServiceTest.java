/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.svc.jdbc.impl;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

class JdbcConnectionLoaderServiceTest {

    private static JdbcConnectionConfig h2Config() {
        JdbcConnectionConfig cfg = new JdbcConnectionConfig();
        cfg.setUrl("jdbc:h2:mem:svc-jdbc-test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        return cfg;
    }

    @Test
    void pooled_connection_returns_working_h2_connection() throws SQLException {
        JdbcConnectionConfig cfg = h2Config();
        cfg.setMaximumPoolSize(2);

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("test", cfg));
        loader.init();

        try (Connection conn = loader.getConnection("test")) {
            Assertions.assertNotNull(conn);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                Assertions.assertTrue(rs.next());
                Assertions.assertEquals(1, rs.getInt(1));
            }
        }
        loader.tearDown();
    }

    @Test
    void unknown_name_returns_null() throws SQLException {
        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of());
        loader.init();

        Assertions.assertNull(loader.getConnection("does-not-exist"));
    }

    @Test
    void unpooled_returns_fresh_driver_manager_connection() throws SQLException {
        JdbcConnectionConfig cfg = h2Config();
        cfg.setPooled(false);

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("raw", cfg));
        loader.init();

        try (Connection conn = loader.getConnection("raw")) {
            Assertions.assertNotNull(conn);
            Assertions.assertTrue(conn.isValid(1));
        }
        Assertions.assertNull(loader.getPool("raw"), "unpooled entry should not allocate a HikariDataSource");
        loader.tearDown();
    }

    @Test
    void close_returns_connection_to_pool_no_leak() throws SQLException {
        JdbcConnectionConfig cfg = h2Config();
        cfg.setMaximumPoolSize(2);

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("test", cfg));
        loader.init();

        // borrow + return 50 times — should never exhaust a max-size-2 pool
        for (int i = 0; i < 50; i++) {
            try (Connection conn = loader.getConnection("test")) {
                Assertions.assertTrue(conn.isValid(1));
            }
        }

        HikariDataSource pool = loader.getPool("test");
        Assertions.assertNotNull(pool, "pool should be created on first getConnection");
        Assertions.assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(),
                "no connection should be in-use after the try-with-resources block");

        loader.tearDown();
        Assertions.assertTrue(pool.isClosed(), "tearDown should close the pool");
    }

    @Test
    void max_pool_size_is_a_hard_cap() throws Exception {
        JdbcConnectionConfig cfg = h2Config();
        cfg.setMaximumPoolSize(2);
        cfg.setConnectionTimeoutMs(500L); // fail fast on exhaustion

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("test", cfg));
        loader.init();

        Connection c1 = loader.getConnection("test");
        Connection c2 = loader.getConnection("test");
        Assertions.assertNotNull(c1);
        Assertions.assertNotNull(c2);

        // A third borrow without releasing must time out — proves the pool is enforcing the cap.
        Assertions.assertThrows(SQLException.class, () -> loader.getConnection("test"));

        c1.close();
        c2.close();
        loader.tearDown();
    }

    @Test
    void tear_down_is_idempotent() {
        JdbcConnectionConfig cfg = h2Config();

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("test", cfg));
        loader.init();

        Assertions.assertDoesNotThrow(loader::tearDown);
        Assertions.assertDoesNotThrow(loader::tearDown);
    }

    @Test
    void test_connection_with_fast_fail_throws_on_bad_url() {
        JdbcConnectionConfig cfg = new JdbcConnectionConfig();
        cfg.setUrl("jdbc:h2:tcp://127.0.0.1:1/no-such-db");
        cfg.setConnectionTimeoutMs(500L);
        cfg.setMaximumPoolSize(1);

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("bad", cfg));
        loader.setTestConnection(true);
        loader.setFastFail(true);
        loader.init();

        Assertions.assertThrows(RuntimeException.class, loader::start);
        loader.tearDown();
    }
}
