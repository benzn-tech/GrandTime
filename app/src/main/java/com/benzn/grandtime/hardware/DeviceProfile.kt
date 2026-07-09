package com.benzn.grandtime.hardware

import android.os.Build

object DeviceProfile {
    private val F2SP_MODELS = setOf("SDJW-F2SP", "F2S-A", "SDJW-F2S", "XB-15")

    fun isF2spFamily(model: String = Build.MODEL): Boolean = model in F2SP_MODELS
}
