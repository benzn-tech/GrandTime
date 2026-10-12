package com.benzn.grandtime.hardware

import com.benzn.grandtime.hardware.PoliceIndicator.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The police_system indicator calls. The values are the ROM's own (PoliceSystemManager constants,
 * read from FS-02's framework.jar); system_server refuses any other colour for an indicator, so
 * a wrong constant here is a light that silently never changes.
 */
class PoliceIndicatorTest {

    private val red = Command(1, 0xFFFF0000.toInt(), 0)
    private val blue = Command(1, 0xFF0000FF.toInt(), 0)
    private val yellow = Command(2, 0xFFFFFF00.toInt(), 0)

    @Test
    fun `each state colour opens on the indicator the ROM accepts it on`() {
        assertEquals(red, PoliceIndicator.commands(LedColor.RED).last())
        assertEquals(blue, PoliceIndicator.commands(LedColor.BLUE).last())
        assertEquals(yellow, PoliceIndicator.commands(LedColor.YELLOW).last())
    }

    @Test
    fun `one colour at a time, and the ROM's green is always closed`() {
        for (color in LedColor.entries) {
            val cmds = PoliceIndicator.commands(color)
            assertEquals("exactly one open", 1, cmds.count { it.state == 0 })
            assertTrue("opens last, after every close", cmds.dropLast(1).all { it.state == 1 })
            assertTrue("green closed", Command(1, 0xFF00FF00.toInt(), 1) in cmds)
        }
    }

    @Test
    fun `off closes every colour`() {
        val cmds = PoliceIndicator.commands(null)
        assertTrue(cmds.all { it.state == 1 })
        assertEquals(
            setOf(1 to 0xFFFF0000.toInt(), 1 to 0xFF00FF00.toInt(), 1 to 0xFF0000FF.toInt(), 2 to 0xFFFFFF00.toInt()),
            cmds.map { it.id to it.color }.toSet(),
        )
    }

    // Source-level: the reflection target and the fallback order need a real ROM to run.
    @Test
    fun `police_system is used only where sysfs cannot be written`() {
        val src = File("src/main/java/com/benzn/grandtime/hardware/LedController.kt").readText()
        assertTrue(src.contains("if (sysfsWritable) null else PoliceIndicator.from(context)"))
        assertTrue(src.contains("getSystemService(\"police_system\")"))
        assertTrue(src.contains("\"setIndicatorLed\""))
    }
}
