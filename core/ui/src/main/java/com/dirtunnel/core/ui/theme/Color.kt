package com.dirtunnel.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * iOS Apple 风格调色板。
 * 精确色值全部来自 TECH.md 四、主题模式（浅色 / 深色）两张表格。
 * 表格之外的少量扩展色（UISwitch 系统绿、告警黄等）为复刻 iOS 控件所需，单独标注。
 */
@Immutable
data class AppleColors(
    // ===== 文档表格角色 =====
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val separator: Color,
    val floatingBarBg: Color,
    val outline: Color,
    val error: Color,
    // ===== 扩展（复刻 iOS 控件，非文档表格项） =====
    val switchOn: Color,        // iOS UISwitch 打开态绿
    val switchOffTrack: Color,  // iOS UISwitch 关闭态轨道
    val switchThumb: Color,     // iOS UISwitch 白色滑块
    val warning: Color,         // iOS 系统黄（告警）
    val success: Color,         // iOS 系统绿（运行中）
    val scrim: Color,           // 弹窗/蒙版
    val isDark: Boolean,
)

/** 浅色主题（Light · iOS浅色）— 严格取自 TECH.md */
val LightAppleColors = AppleColors(
    primary = Color(0xFF007AFF),          // 苹果蓝 Apple System Blue
    onPrimary = Color(0xFFFFFFFF),        // 纯白 White
    background = Color(0xFFF2F2F7),       // 苹果背景白 iOS System Background
    surface = Color(0xFFFFFFFF),          // 卡片白 iOS Secondary System Background
    textPrimary = Color(0xFF000000),      // 主文本黑 iOS Label
    textSecondary = Color(0xFF8E8E93),    // 次要文本灰 iOS Secondary Label
    separator = Color(0xFFC6C6C8),        // 分割线浅灰 iOS Separator
    floatingBarBg = Color(0xF8F8F8CC),    // 浅色毛玻璃底色（含 alpha CC）
    outline = Color(0xFFD1D1D6),          // 边框浅灰
    error = Color(0xFFFF3B30),            // 苹果红 Apple System Red
    switchOn = Color(0xFF34C759),         // UISwitch 系统绿
    switchOffTrack = Color(0xFFE9E9EA),   // UISwitch 关闭轨道
    switchThumb = Color(0xFFFFFFFF),
    warning = Color(0xFFFFCC00),          // iOS 系统黄
    success = Color(0xFF34C759),          // iOS 系统绿
    scrim = Color(0x66000000),
    isDark = false,
)

/** 深色主题（Dark · iOS深色）— 严格取自 TECH.md */
val DarkAppleColors = AppleColors(
    primary = Color(0xFF0A84FF),          // 苹果蓝 Apple System Blue
    onPrimary = Color(0xFFFFFFFF),        // 纯白 White
    background = Color(0xFF000000),       // 苹果深色背景 iOS Dark System Background
    surface = Color(0xFF1C1C1E),          // 深色卡片 iOS Dark Secondary Background
    textPrimary = Color(0xFFFFFFFF),      // 主文本白 iOS Dark Label
    textSecondary = Color(0xFF8E8E93),    // 次要文本灰 iOS Dark Secondary Label
    separator = Color(0xFF38383A),        // 深色分割线 iOS Dark Separator
    floatingBarBg = Color(0x2C2C2ECC),    // 深色毛玻璃底色（含 alpha CC）
    outline = Color(0xFF48484A),          // 深色边框灰
    error = Color(0xFFFF453A),            // 苹果红 Apple System Dark Red
    switchOn = Color(0xFF30D158),         // UISwitch 系统绿（深色）
    switchOffTrack = Color(0xFF39393D),   // UISwitch 关闭轨道（深色）
    switchThumb = Color(0xFFFFFFFF),
    warning = Color(0xFFFFD60A),          // iOS 系统黄（深色）
    success = Color(0xFF30D158),          // iOS 系统绿（深色）
    scrim = Color(0x99000000),
    isDark = true,
)
