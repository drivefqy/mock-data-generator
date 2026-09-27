package com.github.mockdatagen.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 分级状态条(设计规范 §5「反馈分级」)。
 *
 * 一条信息只表达一件事,用底色 + 图标区分 4 档,替代原来把进度、错误、长句说明
 * 全塞进一个 `JLabel` 的做法。
 *
 * 为了不重写调用点,保留了旧 `JLabel` 的用法:`status.text = "…"` 依旧可用,
 * 档位按文案自动判定;关键路径建议显式调用 [info] / [ok] / [warn] / [error]。
 */
class UiStatusBar : JPanel(BorderLayout(JBUI.scale(8), 0)) {

    enum class Level { INFO, OK, WARN, ERROR }

    private val iconLabel = JLabel()
    private val message = JBLabel()
    private var level = Level.INFO
    private var fill: Color = UiTokens.brandSoft

    /** 与旧 JLabel 用法保持一致:直接赋值,档位由文案推断 */
    var text: String
        get() = message.text
        set(value) = show(inferLevel(value), value)

    init {
        isOpaque = false
        border = JBUI.Borders.empty(9, 12)
        message.font = UiTokens.small(font)
        add(iconLabel, BorderLayout.WEST)
        add(message, BorderLayout.CENTER)
        show(Level.INFO, " ")
    }

    fun info(msg: String) = show(Level.INFO, msg)
    fun ok(msg: String) = show(Level.OK, msg)
    fun warn(msg: String) = show(Level.WARN, msg)
    fun error(msg: String) = show(Level.ERROR, msg)
    fun clear() = show(Level.INFO, " ")

    private fun show(l: Level, msg: String) {
        level = l
        val fg: Color
        val icon: Icon
        when (l) {
            Level.INFO -> { fg = UiTokens.brand; fill = UiTokens.brandSoft; icon = AllIcons.General.BalloonInformation }
            Level.OK -> { fg = UiTokens.success; fill = UiTokens.successSoft; icon = AllIcons.General.InspectionsOK }
            Level.WARN -> { fg = UiTokens.warn; fill = UiTokens.warnSoft; icon = AllIcons.General.BalloonWarning }
            Level.ERROR -> { fg = UiTokens.danger; fill = UiTokens.dangerSoft; icon = AllIcons.General.BalloonError }
        }
        val blank = msg.isBlank()
        iconLabel.icon = if (blank) null else icon
        message.foreground = fg
        if (message.text != msg) message.text = msg
        isVisible = !blank
        revalidate()
        repaint()
    }

    /**
     * 按文案推断档位:只是为了兼容旧调用点,不参与任何逻辑判断。
     * 判定顺序必须"先错误后警告" —— 一句「连接失败,请先检查配置」应归为错误。
     * 中英关键词都要认(界面支持语言切换)。
     */
    private fun inferLevel(msg: String): Level {
        val m = msg.lowercase()
        fun hasAny(vararg keys: String) = keys.any { m.contains(it.lowercase()) }
        return when {
            msg.isBlank() -> Level.INFO
            hasAny("失败", "错误", "不合法", "无法", "异常", "✗", "fail", "error", "invalid", "cannot", "exception") -> Level.ERROR
            hasAny("中止", "不兼容", "请先", "未找到", "尚未", "没有", "不能", "abort", "incompatible", "not found", "must", "empty") -> Level.WARN
            hasAny("完成", "已向", "核对一致", "已按", "done", "inserted", "verified") -> Level.OK
            else -> Level.INFO
        }
    }

    override fun paintComponent(g: Graphics) {
        if (!isVisible || width <= 0 || height <= 0) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, UiTokens.radius, UiTokens.radius)
        g2.dispose()
        super.paintComponent(g)
    }
}
