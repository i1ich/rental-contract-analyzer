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
