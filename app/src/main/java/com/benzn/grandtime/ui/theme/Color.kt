package com.benzn.grandtime.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// fieldsight-ui styles/tokens.css 浅色令牌
val Navy900 = Color(0xFF102A43)
val Navy50 = Color(0xFFF0F4F8)
val Accent500 = Color(0xFFFFD966)
val AccentHover = Color(0xFFFFC107)
val AccentActive = Color(0xFFFF8F00)
val AppBg = Color(0xFFF9FAFB)
val SurfaceWhite = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF111827)
val TextSecondary = Color(0xFF4B5563)
val TextTertiary = Color(0xFF6B7280)
val BorderDefault = Color(0xFFD1D5DB)
val BorderSubtle = Color(0xFFE5E7EB)
val DangerBtn = Color(0xFFDC2626)
val SurfaceSelected = Color(0xFFFFF7DB)
val SuccessDot = Color(0xFF22C55E)
val SuccessText = Color(0xFF15803D)
val WarningText = Color(0xFFB45309)
val InfoLink = Color(0xFF2563EB)
val SidebarNavy = Color(0xFF111827)

/** M3 colorScheme 盖不住的 FieldSight 语义色。 */
data class FsColors(
    val accentHover: Color = AccentHover,
    val accentActive: Color = AccentActive,
    val surfaceSelected: Color = SurfaceSelected,
    val successDot: Color = SuccessDot,
    val successText: Color = SuccessText,
    val warningText: Color = WarningText,
    val info: Color = InfoLink,
    val textTertiary: Color = TextTertiary,
    val sidebarNavy: Color = SidebarNavy,
)

val LocalFsColors = staticCompositionLocalOf { FsColors() }
