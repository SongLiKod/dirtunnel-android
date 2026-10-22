package com.dirtunnel.core.ui.components

/**
 * iOS 风格提示弹窗数据（必带解决方案，REQ 五 · 易用性）。
 * message 通常承载“方案：xxx”文本。
 */
data class AppleAlert(
    val title: String,
    val message: String,
    val confirmText: String = "知道了",
    val destructive: Boolean = false,
    val onConfirm: (() -> Unit)? = null,
)
