package com.leaselens;

import com.leaselens.service.LicenseStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseStoreTest {

    private static final String TABLE = "leaselens-analyses-test";

    @Test
    void findReturnsNullForAnUnknownKey() {
        assertNull(new LicenseStore(new FakeDynamoDbClient(), TABLE).find("LL-NOPE"));
    }

    @Test
    void aNewLicenseIsActiveWithAllItsUses() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("LL-AAAA-BBBB", 300, "friend");

        LicenseStore.License license = store.find("LL-AAAA-BBBB");
        assertEquals(300, license.totalUses());
        assertEquals(300, license.remainingUses());
        assertTrue(license.isUsable());
    }

    @Test
    void lookupIgnoresCaseAndSurroundingWhitespace() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("LL-AAAA-BBBB", 5, "friend");

        assertTrue(store.find("  ll-aaaa-bbbb ").isUsable());
    }

    @Test
    void keyPrefixIsTheFirstFourCharactersInTheIssuedCase() {
        assertEquals("def_", LicenseStore.keyPrefix("DEF_ABCD1234"));
        assertEquals("clh5", LicenseStore.keyPrefix(" clh55_abcd1234 "));
        assertEquals("gpt5", LicenseStore.keyPrefix("gpt56l_abcd1234"));
        assertNull(LicenseStore.keyPrefix(null));
        assertNull(LicenseStore.keyPrefix("  "));
    }

    @Test
    void modelIsStoredOnTheLicenseAndAbsentByDefault() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("DEF_AAAA", 5, "default");
        store.create("CLH5_BBBB", 5, "haiku", "anthropic/claude-haiku-5.5");

        assertNull(store.find("DEF_AAAA").model());
        assertEquals("anthropic/claude-haiku-5.5", store.find("clh5_bbbb").model());
    }

    @Test
    void consumeOneDecrementsAndReportsWhatIsLeft() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("LL-AAAA-BBBB", 3, "friend");

        assertEquals(2, store.consumeOne("LL-AAAA-BBBB"));
        assertEquals(1, store.consumeOne("LL-AAAA-BBBB"));
        assertEquals(1, store.find("LL-AAAA-BBBB").remainingUses());
    }

    @Test
    void consumeOneNeverGoesBelowZero() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("LL-AAAA-BBBB", 1, "friend");

        assertEquals(0, store.consumeOne("LL-AAAA-BBBB"));
        assertEquals(-1, store.consumeOne("LL-AAAA-BBBB"));
        LicenseStore.License license = store.find("LL-AAAA-BBBB");
        assertEquals(0, license.remainingUses());
        assertFalse(license.isUsable());
    }

    @Test
    void consumeOneOnAnUnknownKeyReportsNothingToSpend() {
        assertEquals(-1, new LicenseStore(new FakeDynamoDbClient(), TABLE).consumeOne("LL-NOPE"));
    }

    // ---- monthly expiry ---------------------------------------------------------------------

    @Test
    void nextExpiryIsTheNextFifteenthAtMidnightUtc() {
        // The two examples from the todo note.
        assertEquals(Instant.parse("2026-12-15T00:00:00Z"), LicenseStore.nextExpiry(Instant.parse("2026-11-18T09:30:00Z")));
        assertEquals(Instant.parse("2026-11-15T00:00:00Z"), LicenseStore.nextExpiry(Instant.parse("2026-11-10T09:30:00Z")));
        // Edges: first day of the month, the 14th, and the 15th itself (strictly after -> next month).
        assertEquals(Instant.parse("2026-11-15T00:00:00Z"), LicenseStore.nextExpiry(Instant.parse("2026-11-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-11-15T00:00:00Z"), LicenseStore.nextExpiry(Instant.parse("2026-11-14T23:59:59Z")));
        assertEquals(Instant.parse("2026-12-15T00:00:00Z"), LicenseStore.nextExpiry(Instant.parse("2026-11-15T00:00:00Z")));
        // Year rollover.
        assertEquals(Instant.parse("2027-01-15T00:00:00Z"), LicenseStore.nextExpiry(Instant.parse("2026-12-20T12:00:00Z")));
    }

    @Test
    void aNewLicenseGetsTheNextFifteenthAsItsExpiry() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        Instant before = Instant.now();
        store.create("LL-AAAA-BBBB", 300, "friend");

        Instant expiresAt = store.find("LL-AAAA-BBBB").expiresAt();
        assertEquals(15, expiresAt.atZone(java.time.ZoneOffset.UTC).getDayOfMonth());
        assertTrue(expiresAt.isAfter(before));
        assertEquals(LicenseStore.nextExpiry(before), expiresAt);
    }

    @Test
    void aKeyIsUsableBeforeItsExpiryAndRejectedAfter() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        Instant expiry = Instant.parse("2026-12-15T00:00:00Z");
        store.create("LL-AAAA-BBBB", 300, "friend", null, expiry);
        LicenseStore.License license = store.find("LL-AAAA-BBBB");

        assertTrue(license.isUsable(expiry.minusSeconds(1)));
        assertFalse(license.isExpired(expiry.minusSeconds(1)));
        assertFalse(license.isUsable(expiry), "valid strictly before expiresAt");
        assertTrue(license.isExpired(expiry));
        assertFalse(license.isUsable(expiry.plusSeconds(86_400)));
        // Uses are untouched by expiry: the record still says what was left.
        assertEquals(300, license.remainingUses());
    }

    @Test
    void aRegeneratedKeyWorksAgainWhileTheOldOneStaysExpired() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("USR1_OLD", 300, "me", null, Instant.parse("2026-11-15T00:00:00Z"));
        Instant afterTheFifteenth = Instant.parse("2026-11-18T12:00:00Z");
        store.create("USR1_NEW", 300, "me", null, LicenseStore.nextExpiry(afterTheFifteenth));

        assertFalse(store.find("USR1_OLD").isUsable(afterTheFifteenth));
        assertTrue(store.find("USR1_NEW").isUsable(afterTheFifteenth));
    }

    @Test
    void aLegacyRecordWithoutExpiresAtNeverExpires() {
        LicenseStore store = new LicenseStore(new FakeDynamoDbClient(), TABLE);
        store.create("LEGACY", 300, "me", null, null);

        LicenseStore.License license = store.find("LEGACY");
        assertNull(license.expiresAt());
        assertTrue(license.isUsable(Instant.parse("2099-01-01T00:00:00Z")));
    }
}
