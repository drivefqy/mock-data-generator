package com.github.mockdatagen.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font

/**
 * 界面设计 token —— 与 `design/ui-prototype.html` 的 CSS 变量一一对应。
 *
 * 每个颜色都给 (浅色, 深色) 两套,由 [JBColor] 跟随 IDE 主题自动切换。
 * **不要**在组件上硬编码 `Color(0x…)`:深色主题下会发灰、发脏,这是改造前的老问题。
 */
object UiTokens {

    private fun c(light: Int, dark: Int) = JBColor(Color(light), Color(dark))

    // ---------- 层 ----------
    val bgPage = c(0xF6F7FA, 0x12151B)
    val surface = c(0xFFFFFF, 0x1A1E26)
    val surface2 = c(0xF4F6FA, 0x212630)
    val surfaceHover = c(0xF1F5FC, 0x252B36)
    val sunken = c(0xFAFBFD, 0x171B22)

    // ---------- 描边 ----------
    val border = c(0xE2E6EE, 0x2A303A)
    val borderStrong = c(0xC8D0DD, 0x3B4350)
    val borderSubtle = c(0xEDF0F6, 0x232935)

    // ---------- 文字 ----------
    val text1 = c(0x191C22, 0xE8EBF1)
    val text2 = c(0x575F6E, 0x9BA3B2)
    val text3 = c(0x8A93A2, 0x6D7684)

    // ---------- 品牌 / 智能推荐 ----------
    val brand = c(0x2F6BE4, 0x6F9BF6)
    val brandSoft = c(0xE9F0FD, 0x1A2540)

    /** 只用于「智能推荐」标记,不做其它用途 —— 避免两个强调色互相抢戏 */
    val accent = c(0x0E9488, 0x3CBFA9)
    val accentSoft = c(0xE2F5F1, 0x12302C)

    // ---------- 语义 ----------
    val success = c(0x15803D, 0x4EC77F)
    val successSoft = c(0xE7F6EC, 0x12291B)
    val warn = c(0xAC6408, 0xE0A552)
    val warnSoft = c(0xFDF3E3, 0x2E2413)
    val danger = c(0xCE3F3F, 0xF0736F)
    val dangerSoft = c(0xFCECEC, 0x331A1A)

    // ---------- 形状与节奏 ----------
    /** 卡片 / 状态条圆角 */
    val radius = JBUI.scale(10)

    /** 表格行高在原基础上追加的留白(目标行高 ≈ 44px) */
    val rowPad = JBUI.scale(20)

    val gap1 = JBUI.scale(4)
    val gap2 = JBUI.scale(8)
    val gap3 = JBUI.scale(12)
    val gap4 = JBUI.scale(16)

    /** 次要文字(说明、计数、类型标签)统一走这里,别再单独派生字号 */
    fun small(base: Font): Font = base.deriveFont(base.size2D - 1f)
}
