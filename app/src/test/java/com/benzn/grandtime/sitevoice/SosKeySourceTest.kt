package com.benzn.grandtime.sitevoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SosKeySourceTest {
    @Test fun parses_down() {
        assertEquals(SosDirection.DOWN, SosKeySource.parse("lolaage.sos.down"))
    }

    @Test fun parses_up() {
        assertEquals(SosDirection.UP, SosKeySource.parse("lolaage.sos.up"))
    }

    @Test fun ignores_unrelated_actions() {
        assertNull(SosKeySource.parse("lolaage.ptt.down"))
        assertNull(SosKeySource.parse("lolaage.sos"))
        assertNull(SosKeySource.parse("lolaage.video1.down"))
    }
}
