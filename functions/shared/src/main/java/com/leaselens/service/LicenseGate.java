package com.leaselens.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The read-only license check shared by every endpoint that must turn away a bad key
 * ({@code /analyze}, {@code /upload-url}). Checking never charges a use — a use is spent only by
 * the analysis worker, once an analysis has succeeded.
 *
 * <p>Returns what to answer with rather than a ready response, because each Lambda builds its own
 * API Gateway envelope and logs with its own {@code Log}.
 */
public final class LicenseGate {

    /** Machine-readable code on the 403 for a key past its {@code expiresAt}. */
    public static final String EXPIRED_CODE = "LICENSE_EXPIRED";

    /** Why a key was refused; {@code reason} is for logs, {@code body} is the JSON for the client. */
    public record Rejection(int status, String reason, Map<String, Object> body) {
    }

    private LicenseGate() {
    }

    /** {@code null} means the key may proceed. {@code normalizedKey} comes from {@link LicenseStore#normalize}. */
    public static Rejection check(LicenseStore store, String normalizedKey, Instant now) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return new Rejection(401, "missing", body("Ingresá tu clave de acceso para analizar un contrato."));
        }
        LicenseStore.License license = store.find(normalizedKey);
        if (license == null) {
            return new Rejection(403, "unknown", body("La clave de acceso no es válida."));
        }
        if (license.status() == LicenseStore.Status.REVOKED) {
            return new Rejection(403, "revoked", body("Esta clave de acceso ya no está activa."));
        }
        if (license.isExpired(now)) {
            // A distinct code (not just the generic 403) because the fix differs: the user needs a
            // fresh key, not a retry. Costs one extra field, so it is cheap even for one user.
            Map<String, Object> body = body("Esta clave de acceso venció. Pedí una clave nueva.");
            body.put("code", EXPIRED_CODE);
            return new Rejection(403, "expired", body);
        }
        if (!license.isUsable(now)) {
            return new Rejection(403, "exhausted", body("Esta clave de acceso ya usó todos sus análisis."));
        }
        return null;
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
