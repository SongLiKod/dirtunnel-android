package com.dirtunnel.core.ui.theme

/**
 * 主题模式（TECH.md 四：跟随系统 / 浅色模式 / 深色模式）
 * 枚举名持久化到 DataStore：SYSTEM / LIGHT / DARK
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
