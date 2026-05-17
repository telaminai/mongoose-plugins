/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.fluxtion.dataflow.serverplugin.svc.jdbc.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

class JdbcConnectionLoaderServiceTest {

    @Test
    void getConnection_returns_working_h2_connection() throws SQLException {
        JdbcConnectionConfig cfg = new JdbcConnectionConfig();
        cfg.setUrl("jdbc:h2:mem:svc-jdbc-test;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");

        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of("test", cfg));
        loader.init();

        try (Connection conn = loader.getConnection("test")) {
            Assertions.assertNotNull(conn, "connection should be returned for a known name");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                Assertions.assertTrue(rs.next());
                Assertions.assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void getConnection_returns_null_for_unknown_name() throws SQLException {
        JdbcConnectionLoaderService loader = new JdbcConnectionLoaderService();
        loader.setConnections(Map.of());
        loader.init();

        Assertions.assertNull(loader.getConnection("does-not-exist"),
                "unknown connection name should produce null + a warning, not throw");
    }
}
