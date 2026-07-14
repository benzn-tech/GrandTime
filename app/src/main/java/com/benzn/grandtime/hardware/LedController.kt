package com.benzn.grandtime.hardware

import java.io.File

/**
 * F2SP 物理指示灯(2 号灯)控制:直接写 sysfs `/sys/class/leds/<color>/brightness`。
 *
 * 真机实测(2026-07-14):这些节点是 -rwxrwxrwx(777),普通 app 域可直接写、SELinux 放行。
 * green 是 664(system/root 才可写),故待机改用蓝灯。police_system 系统服务是空壳,不走。
 * 节点不可写(别的机型/权限变化)时静默降级为 no-op。
 */
enum class LedColor(val node: String) {
    RED("red"),      // 录像
    YELLOW("yellow"), // 录音
    BLUE("blue"),     // 待机
}

class LedController {

    private val files: Map<LedColor, File> =
        LedColor.entries.associateWith { File("/sys/class/leds/${it.node}/brightness") }

    /** 至少一个颜色节点可写才认为设备支持;否则全程 no-op。 */
    val available: Boolean =
        files.values.any { runCatching { it.canWrite() }.getOrDefault(false) }

    /** 点亮指定颜色(255)并熄灭其它颜色(0);null = 全灭。写失败静默忽略。 */
    fun show(color: LedColor?) {
        for ((c, f) in files) {
            runCatching { f.writeText(if (c == color) "255" else "0") }
        }
    }

    fun off() = show(null)
}
