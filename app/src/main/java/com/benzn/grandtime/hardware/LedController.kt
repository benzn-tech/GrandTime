package com.benzn.grandtime.hardware

import android.content.Context
import java.io.File
import java.lang.reflect.Method

/**
 * F2SP 物理指示灯(2 号灯)控制,两条通道,哪条能用走哪条:
 *
 * 1. sysfs `/sys/class/leds/<color>/brightness`。真机实测(2026-07-14,mt6768 / SDJW-F2SP):
 *    这些节点是 -rwxrwxrwx(777),普通 app 域可直接写、SELinux 放行。green 是 664(system/root
 *    才可写),故待机改用蓝灯。
 * 2. The ROM's `police_system` service, where sysfs is closed. Measured 2026-10-13 on FS-02
 *    (SDJW-F2S, mt6761, Aug 2025 ROM): /sys/class/leds is denied to apps and to adb, so (1) was
 *    a silent no-op and the ROM's own steady green stayed on. That ROM's
 *    PoliceSystemManager.setIndicatorLed(id, color, state) is real (system_server writes the same
 *    sysfs nodes): indicator 1 takes red/green/blue, indicator 2 takes yellow, anything else is
 *    refused. Driven from adb it turned the light red. On the older ROM the same class had no
 *    methods at all, which is why (1) came first and still wins when it works.
 *
 * Neither channel available → no-op.
 */
enum class LedColor(val node: String) {
    RED("red"),      // 录像
    YELLOW("yellow"), // 录音
    BLUE("blue"),     // 待机
}

class LedController(context: Context) {

    private val files: Map<LedColor, File> =
        LedColor.entries.associateWith { File("/sys/class/leds/${it.node}/brightness") }

    private val sysfsWritable: Boolean =
        files.values.any { runCatching { it.canWrite() }.getOrDefault(false) }

    private val police: PoliceIndicator? = if (sysfsWritable) null else PoliceIndicator.from(context)

    /** 至少一条通道可用才认为设备支持;否则全程 no-op。 */
    val available: Boolean = sysfsWritable || police != null

    /** Which channel is in use, for the startup log. */
    val channel: String = when {
        sysfsWritable -> "sysfs"
        police != null -> "police_system"
        else -> "none"
    }

    /** 点亮指定颜色并熄灭其它颜色;null = 全灭。写失败静默忽略。 */
    fun show(color: LedColor?) {
        val p = police
        if (p != null) {
            p.apply(PoliceIndicator.commands(color))
            return
        }
        for ((c, f) in files) {
            runCatching { f.writeText(if (c == color) "255" else "0") }
        }
    }

    fun off() = show(null)
}

/** setIndicatorLed through PoliceSystemManager, by reflection: the class exists only in this ROM. */
class PoliceIndicator private constructor(private val manager: Any, private val setIndicatorLed: Method) {

    /** One setIndicatorLed(id, color, state) call. */
    data class Command(val id: Int, val color: Int, val state: Int)

    fun apply(commands: List<Command>) {
        for (c in commands) runCatching { setIndicatorLed.invoke(manager, c.id, c.color, c.state) }
    }

    companion object {
        // PoliceSystemManager's own constants (INDICATOR_LED_ID_*, LED_COlOR_*, LIGHT_OPEN/CLOSE).
        const val INDICATOR_1 = 1
        const val INDICATOR_2 = 2
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        const val OPEN = 0
        const val CLOSE = 1

        /** Every colour each indicator accepts. GREEN is the ROM's own steady light: it is closed
         *  on every call so it cannot sit on top of ours (seen on FS-02: red opened under green
         *  still read as green until green was closed). */
        private val ALL = listOf(
            INDICATOR_1 to RED, INDICATOR_1 to GREEN, INDICATOR_1 to BLUE, INDICATOR_2 to YELLOW,
        )

        /** The calls that show [color] alone. Closes first, then opens, so two colours never share
         *  an indicator even for a moment. */
        fun commands(color: LedColor?): List<Command> {
            val target = when (color) {
                LedColor.RED -> INDICATOR_1 to RED
                LedColor.BLUE -> INDICATOR_1 to BLUE
                LedColor.YELLOW -> INDICATOR_2 to YELLOW
                null -> null
            }
            val closes = ALL.filter { it != target }.map { Command(it.first, it.second, CLOSE) }
            return if (target == null) closes else closes + Command(target.first, target.second, OPEN)
        }

        fun from(context: Context): PoliceIndicator? = runCatching {
            val manager = context.getSystemService("police_system") ?: return null
            val method = manager.javaClass.getMethod(
                "setIndicatorLed", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            PoliceIndicator(manager, method)
        }.getOrNull()
    }
}
