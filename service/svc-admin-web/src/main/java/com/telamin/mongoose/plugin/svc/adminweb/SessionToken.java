/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.plugin.svc.adminweb;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-signed session cookie payload. Carries {@code userId}, expiry epoch
 * millis, and a per-session CSRF token. No server-side session table — the
 * cookie itself is the credential.
 *
 * <p>Wire format: {@code base64url(payload) + "." + base64url(hmac)}
 *
 * <p>{@code payload} is the raw UTF-8 string {@code userId|expiryMs|csrfToken}
 * with {@code '|'} as the delimiter. All three fields are validated free of
 * {@code '|'} on creation so the parser is unambiguous.
 */
final class SessionToken {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    final String userId;
    final long   expiryMs;
    final String csrfToken;

    SessionToken(String userId, long expiryMs, String csrfToken) {
        if (userId.indexOf('|') >= 0 || csrfToken.indexOf('|') >= 0) {
            throw new IllegalArgumentException("userId and csrfToken must not contain '|'");
        }
        this.userId = userId;
        this.expiryMs = expiryMs;
        this.csrfToken = csrfToken;
    }

    /** Serialise and sign with the given secret. */
    String encode(byte[] secret) {
        String payload = userId + '|' + expiryMs + '|' + csrfToken;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] sig = hmac(secret, payloadBytes);
        return B64.encodeToString(payloadBytes) + '.' + B64.encodeToString(sig);
    }

    /**
     * Verify signature and expiry, returning the decoded token or {@code null}
     * if the cookie is malformed, tampered with, or expired.
     */
    static SessionToken decode(String cookieValue, byte[] secret, long nowMs) {
        if (cookieValue == null) return null;
        int dot = cookieValue.indexOf('.');
        if (dot < 0 || dot == cookieValue.length() - 1) return null;

        byte[] payloadBytes;
        byte[] presentedSig;
        try {
            payloadBytes = B64D.decode(cookieValue.substring(0, dot));
            presentedSig = B64D.decode(cookieValue.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }

        byte[] expectedSig = hmac(secret, payloadBytes);
        if (!constantTimeEquals(presentedSig, expectedSig)) return null;

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 3) return null;

        long expiry;
        try {
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (expiry < nowMs) return null;

        return new SessionToken(parts[0], expiry, parts[2]);
    }

    private static byte[] hmac(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
