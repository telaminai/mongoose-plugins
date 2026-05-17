/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class SessionTokenTest {

    private static final byte[] SECRET = "test-secret-32-bytes-of-entropy!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER  = "different-secret-key-of-32-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void round_trip_preserves_fields() {
        long now = System.currentTimeMillis();
        SessionToken in = new SessionToken("alice", now + 60_000, "csrf-xyz");

        SessionToken out = SessionToken.decode(in.encode(SECRET), SECRET, now);

        Assertions.assertNotNull(out);
        Assertions.assertEquals("alice", out.userId);
        Assertions.assertEquals(now + 60_000, out.expiryMs);
        Assertions.assertEquals("csrf-xyz", out.csrfToken);
    }

    @Test
    void tampered_signature_fails_decode() {
        long now = System.currentTimeMillis();
        String cookie = new SessionToken("alice", now + 60_000, "csrf").encode(SECRET);

        // Tamper an INTERIOR char of the signature. Flipping the trailing
        // base64 char can be a no-op when the padded bits don't change the
        // decoded byte sequence — flip something a few chars in from the
        // signature start, where every char encodes 6 bits of real payload.
        int dot = cookie.indexOf('.');
        int target = dot + 3; // a char inside the signature
        char ch = cookie.charAt(target);
        char flipped = ch == 'A' ? 'B' : 'A';
        String tampered = cookie.substring(0, target) + flipped + cookie.substring(target + 1);

        Assertions.assertNull(SessionToken.decode(tampered, SECRET, now));
    }

    @Test
    void wrong_secret_fails_decode() {
        long now = System.currentTimeMillis();
        String cookie = new SessionToken("alice", now + 60_000, "csrf").encode(SECRET);

        Assertions.assertNull(SessionToken.decode(cookie, OTHER, now));
    }

    @Test
    void expired_token_fails_decode() {
        long now = System.currentTimeMillis();
        SessionToken expired = new SessionToken("alice", now - 1, "csrf");

        Assertions.assertNull(SessionToken.decode(expired.encode(SECRET), SECRET, now));
    }

    @Test
    void malformed_cookie_fails_decode() {
        long now = System.currentTimeMillis();
        Assertions.assertNull(SessionToken.decode(null, SECRET, now));
        Assertions.assertNull(SessionToken.decode("", SECRET, now));
        Assertions.assertNull(SessionToken.decode("nodot", SECRET, now));
        Assertions.assertNull(SessionToken.decode(".", SECRET, now));
        Assertions.assertNull(SessionToken.decode("not-valid-base64.also-not", SECRET, now));
    }

    @Test
    void rejects_pipe_in_input_fields() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionToken("bad|user", 1, "csrf"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionToken("alice", 1, "bad|csrf"));
    }
}
