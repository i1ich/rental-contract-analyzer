package com.leaselens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leaselens.service.DisclaimerGate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the three copies of "which disclaimer is current" from drifting apart: the text shown to
 * users, the version + hash recorded next to it, and the version the API enforces. Editing the text
 * without bumping the version (in both disclaimer.json and DisclaimerGate) fails this test.
 */
class DisclaimerGateTest {

    // Surefire runs with the module directory as working directory.
    private static final Path LEGAL_DIR = Path.of("..", "..", "frontend", "src", "legal");

    @Test
    void textHashAndEnforcedVersionAgree() throws Exception {
        String text = Files.readString(LEGAL_DIR.resolve("disclaimer.es.md"), StandardCharsets.UTF_8)
                // git on Windows may check the file out with CRLF; the hash is of the LF form.
                .replace("\r\n", "\n");
        JsonNode meta = new ObjectMapper().readTree(Files.readString(LEGAL_DIR.resolve("disclaimer.json")));

        String actualHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals(meta.get("sha256").asText(), actualHash,
                "disclaimer.es.md changed: bump the version and update the sha256 in disclaimer.json "
                        + "(and DisclaimerGate.CURRENT_VERSION)");
        assertEquals(meta.get("version").asText(), DisclaimerGate.CURRENT_VERSION,
                "disclaimer.json version and DisclaimerGate.CURRENT_VERSION must match");
    }

    @Test
    void onlyAnExplicitTrueWithTheCurrentVersionIsAccepted() {
        assertTrue(DisclaimerGate.isAccepted(true, DisclaimerGate.CURRENT_VERSION));
        assertTrue(DisclaimerGate.isAccepted(true, " " + DisclaimerGate.CURRENT_VERSION + " "));
        assertFalse(DisclaimerGate.isAccepted(null, DisclaimerGate.CURRENT_VERSION));
        assertFalse(DisclaimerGate.isAccepted(false, DisclaimerGate.CURRENT_VERSION));
        assertFalse(DisclaimerGate.isAccepted(true, null));
        assertFalse(DisclaimerGate.isAccepted(true, ""));
        assertFalse(DisclaimerGate.isAccepted(true, "old"));
    }
}
