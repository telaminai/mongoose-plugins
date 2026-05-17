/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.admintelnet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TelnetAdminCommandProcessorTest {

    @Test
    void default_interface_is_loopback() {
        TelnetAdminCommandProcessor svc = new TelnetAdminCommandProcessor();
        Assertions.assertEquals("127.0.0.1", svc.getInterfaceName(),
                "telnet admin must default to loopback so a default-installed server isn't exposed");
    }

    @Test
    void init_rejects_invalid_port() {
        TelnetAdminCommandProcessor svc = new TelnetAdminCommandProcessor();
        svc.setListenPort(0);
        Assertions.assertThrows(IllegalStateException.class, svc::init);

        svc.setListenPort(70000);
        Assertions.assertThrows(IllegalStateException.class, svc::init);
    }

    @Test
    void init_rejects_empty_interface() {
        TelnetAdminCommandProcessor svc = new TelnetAdminCommandProcessor();
        svc.setListenPort(2019);
        svc.setInterfaceName("");
        Assertions.assertThrows(IllegalStateException.class, svc::init);

        svc.setInterfaceName(null);
        Assertions.assertThrows(IllegalStateException.class, svc::init);
    }

    @Test
    void tear_down_without_start_is_safe() {
        TelnetAdminCommandProcessor svc = new TelnetAdminCommandProcessor();
        Assertions.assertDoesNotThrow(svc::tearDown);
        Assertions.assertDoesNotThrow(svc::tearDown);
    }

    @Test
    void port_ctor_sets_listen_port() {
        TelnetAdminCommandProcessor svc = new TelnetAdminCommandProcessor(2024);
        Assertions.assertEquals(2024, svc.getListenPort());
        Assertions.assertEquals("127.0.0.1", svc.getInterfaceName());
    }
}
