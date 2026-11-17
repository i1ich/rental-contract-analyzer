package com.leaselens;

import com.leaselens.service.LicenseStore;
import org.junit.jupiter.api.Test;

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
}
