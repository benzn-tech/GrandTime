package com.benzn.grandtime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeoutTest {
    @Test fun `one minute maps to 60000 ms`() = assertEquals(60_000, screenOffTimeoutMillis(1))
    @Test fun `three minutes maps to 180000 ms`() = assertEquals(180_000, screenOffTimeoutMillis(3))
    @Test fun `five minutes maps to 300000 ms`() = assertEquals(300_000, screenOffTimeoutMillis(5))
    @Test fun `never (0) maps to Int MAX (effectively never sleeps)`() =
        assertEquals(Int.MAX_VALUE, screenOffTimeoutMillis(0))
    @Test fun `negative is treated as never`() = assertEquals(Int.MAX_VALUE, screenOffTimeoutMillis(-1))
}
