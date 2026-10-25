package com.telamin.mongoose.plugin.svc.adminweb;

import org.junit.jupiter.api.Test;

import static com.telamin.mongoose.plugin.svc.adminweb.WebAdminService.AuthMode.BASIC;
import static com.telamin.mongoose.plugin.svc.adminweb.WebAdminService.AuthMode.BEARER;
import static com.telamin.mongoose.plugin.svc.adminweb.WebAdminService.AuthMode.NONE;
import static com.telamin.mongoose.plugin.svc.adminweb.WebAdminService.exposedWithoutAuth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The web admin binds to loopback unless off-box access is configured, and warns when exposed with no auth. */
class BindHostDefaultTest {

    @Test
    void defaultsToLoopback() {
        assertEquals("127.0.0.1", new WebAdminService().getHost());
    }

    @Test
    void loopbackWithoutAuthIsNotExposed() {
        assertFalse(exposedWithoutAuth("127.0.0.1", NONE));
        assertFalse(exposedWithoutAuth("localhost", NONE));
        assertFalse(exposedWithoutAuth("::1", NONE));
    }

    @Test
    void allInterfacesWithoutAuthIsExposed() {
        assertTrue(exposedWithoutAuth("0.0.0.0", NONE));
        assertTrue(exposedWithoutAuth("10.1.2.3", NONE));
        assertTrue(exposedWithoutAuth("no-such-host.invalid", NONE));
    }

    @Test
    void offBoxWithAuthIsNotFlagged() {
        assertFalse(exposedWithoutAuth("0.0.0.0", BASIC));
        assertFalse(exposedWithoutAuth("0.0.0.0", BEARER));
    }
}
