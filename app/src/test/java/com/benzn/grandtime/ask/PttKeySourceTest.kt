package com.benzn.grandtime.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PttKeySourceTest {
    @Test fun parses_down() {
        assertEquals(PttDirection.DOWN, PttKeySource.parse("lolaage.ptt.down"))
    }

    @Test fun parses_up() {
        assertEquals(PttDirection.UP, PttKeySource.parse("lolaage.ptt.up"))
    }

    @Test fun ignores_unrelated_actions() {
        assertNull(PttKeySource.parse("lolaage.video1.down"))
        assertNull(PttKeySource.parse("lolaage.ptt"))
        assertNull(PttKeySource.parse("android.intent.action.BOOT_COMPLETED"))
    }
}
