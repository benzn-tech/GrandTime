package com.benzn.grandtime.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStoreTest {
    private val sample = PersistedSession("rt-123", "sub-abc", "Jane Doe", "jane_subabc12", "jane")

    @Test fun `save then load roundtrip`() {
        val store = InMemoryTokenStore()
        assertNull(store.load())
        store.save(sample)
        assertEquals(sample, store.load())
    }

    @Test fun `clear removes session`() {
        val store = InMemoryTokenStore()
        store.save(sample)
        store.clear()
        assertNull(store.load())
    }

    @Test fun `null namePrefix survives roundtrip`() {
        val store = InMemoryTokenStore()
        val s = sample.copy(namePrefix = null, folder = "device")
        store.save(s)
        assertEquals(s, store.load())
    }
}
