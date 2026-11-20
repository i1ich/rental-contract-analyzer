package com.leaselens.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side half of the disclaimer / release-of-liability acceptance.
 *
 * <p>A checkbox in the UI is not enough on its own: anyone can call the API directly. So
 * {@code POST /analyze} and {@code POST /upload-url} refuse unless the request itself carries {@code disclaimerAccepted: true}
 * and the {@code disclaimerVersion} of the text currently shown to users. The check runs before any
 * job record, OCR or LLM spend. It is per request on purpose — no session state to forget or forge.
 *
 * <p>{@link #CURRENT_VERSION} must be bumped whenever {@code frontend/src/legal/disclaimer.es.md}
 * changes (the frontend reads the same version from {@code frontend/src/legal/disclaimer.json}).
 * {@code DisclaimerGateTest} fails if the text, its recorded hash and this constant drift apart.
 */
public final class DisclaimerGate {

    public static final String CURRENT_VERSION = "2026-11-17.1";

    /** Machine-readable error code returned in the 400 body. */
    public static final String ERROR_CODE = "DISCLAIMER_NOT_ACCEPTED";

    private DisclaimerGate() {
    }

    /** The 400 body every gated endpoint ({@code /analyze}, {@code /upload-url}) answers with. */
    public static Map<String, Object> rejectionBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Para analizar un contrato tenés que leer y aceptar el aviso legal.");
        body.put("code", ERROR_CODE);
        body.put("currentVersion", CURRENT_VERSION);
        return body;
    }

    /** True only for an explicit {@code true} together with exactly the current version. */
    public static boolean isAccepted(Boolean accepted, String version) {
        return Boolean.TRUE.equals(accepted) && CURRENT_VERSION.equals(version == null ? null : version.trim());
    }
}
