package com.github.mockdatagen.ui

import com.github.mockdatagen.core.I18n
import com.github.mockdatagen.core.MockDataGenerator
import com.github.mockdatagen.core.MockRuleException
import com.github.mockdatagen.core.ParamChoice
import com.github.mockdatagen.core.ParamField
import com.github.mockdatagen.core.ParamFormat
import com.github.mockdatagen.core.ParamKind
import com.github.mockdatagen.core.RuleConfig
import com.github.mockdatagen.core.RuleParamSpec
import com.github.mockdatagen.core.RuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 规则参数配置对话框:按规则类型生成对应输入控件。
 * 枚举 → 多行候选值;时间/日期 → 起止时间(含快捷预设);数值 → 区间;正则 → 模板。
 *
 * 文案按打开时的界面语言渲染(对话框是模态的,期间不会发生语言切换)。
 */
class RuleParamDialog(
    project: Project,
    private val rule: RuleConfig,
    private val onApplied: () -> Unit,
) : DialogWrapper(project, true) {

    private val form = JPanel(GridBagLayout())
    private var gridRow = 0

    /** 放进表单的组件(多行文本会被 JBScrollPane 包裹,不一定是持有文本的那个对象) */
    private val components = ArrayList<JComponent>()

    /**
     * 真正持有输入值的控件,与 [components] 一一对应。
     *
     * 取值 / 注册监听 / 预设回填一律只认它:多行文本在 [components] 里是包裹它的滚动面板,
     * 从那里取值只会得到空串(表现为候选值填了却报"不能为空")。
     */
    private val editors = ArrayList<JComponent>()

    private val fields: List<ParamField> = RuleParamSpec.fields(rule.type)
    private val rawPreview = JLabel(" ").apply { foreground = UiTokens.text3 }

    /**
     * 参数与列类型不兼容时的内联提示:稳定显示的红字 + 保存前一次性确认。
     * 不走 [doValidate] 返回 ValidationInfo —— 那样提示会随输入反复刷新。
     */
    private val warningLabel = JLabel(" ").apply {
        foreground = UiTokens.danger
        font = UiTokens.small(font)
    }

    init {
        title = I18n.t(
            "配置规则参数 — ${rule.column.name}(${rule.type.display})",
            "Rule parameters — ${rule.column.name} (${rule.type.display})",
        )
        init()
    }

    private fun createRow(label: String, comp: JComponent, hint: String?) {
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(3, 4)
        gbc.anchor = GridBagConstraints.WEST
        gbc.gridx = 0
        gbc.gridy = gridRow
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        form.add(JLabel(label), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        form.add(comp, gbc)
        gridRow++

        if (!hint.isNullOrBlank()) {
            val h = JLabel(hint).apply {
                foreground = UiTokens.text3
                font = UiTokens.small(font)
            }
            val hc = GridBagConstraints()
            hc.insets = JBUI.insets(0, 4, 6, 4)
            hc.anchor = GridBagConstraints.WEST
            hc.gridx = 1
            hc.gridy = gridRow
            hc.weightx = 1.0
            hc.fill = GridBagConstraints.HORIZONTAL
            form.add(h, hc)
            gridRow++
        }
    }

    override fun createCenterPanel(): JComponent {
        val initial = RuleParamSpec.parse(rule.type, rule.param)

        fields.forEachIndexed { index, field ->
            val value = initial.getOrNull(index).orEmpty()
            // comp:放进表单的组件;editor:真正持有值的控件。多行文本两者不同,必须分开记
            val comp: JComponent
            val editor: JComponent
            when (field.kind) {
                ParamKind.MULTILINE -> {
                    val area = JTextArea(value, 10, 48).apply {
                        lineWrap = true
                        wrapStyleWord = true
                    }
                    comp = JBScrollPane(area).apply {
                        preferredSize = Dimension(JBUI.scale(380), JBUI.scale(150))
                    }
                    editor = area
                }

                ParamKind.CHOICE -> {
                    val combo = JComboBox(DefaultComboBoxModel(field.choices.toTypedArray()))
                    combo.renderer = object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean,
                        ): Component {
                            val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                            label.text = (value as? ParamChoice)?.label ?: ""
                            return label
                        }
                    }
                    val target = field.choices.firstOrNull { it.code == value } ?: field.choices.firstOrNull()
                    if (target != null) combo.selectedItem = target
                    combo.preferredSize = Dimension(JBUI.scale(320), combo.preferredSize.height)
                    comp = combo
                    editor = combo
                }

                ParamKind.INT, ParamKind.DECIMAL -> {
                    val f = JBTextField(value).apply {
                        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
                    }
                    comp = f
                    editor = f
                }

                else -> {
                    val f = JBTextField(value).apply {
                        preferredSize = Dimension(JBUI.scale(320), preferredSize.height)
                    }
                    comp = f
                    editor = f
                }
            }
            components.add(comp)
            editors.add(editor)
            createRow(field.label, comp, field.hint.ifBlank { null })
        }

        // 时间范围快捷预设
        val presets = RuleParamSpec.presets(rule.type)
        if (presets.isNotEmpty()) {
            val bar = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.gap2, 0))
            bar.add(JLabel(I18n.t("快捷范围:", "Presets:")))
            presets.forEach { (name, param) ->
                bar.add(JButton(name).apply { addActionListener { applyPreset(param) } })
            }
            createRow("", bar, null)
        }

        createRow(
            I18n.t("参数值", "Value"),
            rawPreview,
            I18n.t("最终写入生成引擎的参数(只读)", "Final parameter passed to the generator (read-only)"),
        )
        createRow("", warningLabel, null)

        // 「数据库表达式」会原样拼进 INSERT 并以当前数据库账号的权限执行,必须让用户知道自己在放开什么
        if (rule.type == RuleType.SQL_EXPRESSION) {
            createRow(
                "",
                JLabel().apply {
                    foreground = UiTokens.warn
                    font = UiTokens.small(font)
                    text = I18n.t(
                        "⚠ 表达式原样拼入 INSERT,以当前数据库账号权限执行 — 只填写可信内容",
                        "⚠ Inserted verbatim and executed with the current database account's privileges — use trusted content only",
                    )
                },
                null,
            )
        }

        val listener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshPreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshPreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshPreview()
        }
        // 监听 editor(而非 comp):包在滚动面板里的 JTextArea 也要能触发预览刷新
        editors.forEach { c ->
            when (c) {
                is JBTextField -> c.document.addDocumentListener(listener)
                is JTextArea -> c.document.addDocumentListener(listener)
                is JComboBox<*> -> c.addActionListener { refreshPreview() }
                else -> Unit
            }
        }
        refreshPreview()

        val wrapper = JPanel(BorderLayout())
        wrapper.add(form, BorderLayout.NORTH)
        wrapper.border = JBUI.Borders.empty(8)
        return wrapper
    }

    private fun applyPreset(param: String) {
        RuleParamSpec.parse(rule.type, param).forEachIndexed { i, v ->
            when (val comp = editors.getOrNull(i)) {
                is JBTextField -> comp.text = v
                is JTextArea -> comp.text = v
                else -> Unit
            }
        }
        refreshPreview()
    }

    private fun values(): List<String> = editors.map { c ->
        when (c) {
            is JBTextField -> c.text
            is JTextArea -> c.text
            is JComboBox<*> -> (c.selectedItem as? ParamChoice)?.code ?: ""
            else -> ""
        }
    }

    private fun composed(): String = RuleParamSpec.compose(rule.type, values())

    /** 用生成引擎试跑一行,返回不兼容原因(兼容返回空串)。只作提示,不阻断保存。 */
    private fun compatProblem(param: String): String {
        if (param.isBlank()) return ""
        return try {
            MockDataGenerator().generateRow(listOf(RuleConfig(rule.column, true, rule.type, param, 0)), 0L)
            ""
        } catch (e: MockRuleException) {
            e.message ?: I18n.t("参数不合法", "Invalid parameter")
        } catch (e: Exception) {
            I18n.t(
                "参数校验失败:${e.message ?: e.javaClass.simpleName}",
                "Parameter check failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun refreshPreview() {
        val text = composed()
        val shown = if (text.isBlank()) {
            I18n.t("(空)", "(empty)")
        } else {
            if (text.length > 80) text.take(77) + "…" else text
        }
        // 值没变就不写回:避免每次输入都触发整块重绘(提示闪烁的另一个来源)
        if (rawPreview.text != shown) rawPreview.text = shown
        if (rawPreview.toolTipText != text) rawPreview.toolTipText = text

        val problem = compatProblem(text)
        val warn = if (problem.isEmpty()) " " else htmlWarning(problem)
        if (warningLabel.text != warn) {
            warningLabel.text = warn
            warningLabel.toolTipText = problem.ifBlank { null }
        }
    }

    private fun htmlWarning(problem: String): String =
        "<html><body style='width:520px'>⚠ ${escapeHtml(problem)}</body></html>"

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * 只做参数本身格式的校验(空值、整数、日期格式、区间方向)。
     * 不在这里跑生成引擎:类型不兼容由 [warningLabel] 提示。
     */
    override fun doValidate(): ValidationInfo? {
        RuleParamSpec.validate(rule.type, values())?.let { return ValidationInfo(it, editors.firstOrNull()) }
        return null
    }

    override fun doOKAction() {
        val param = composed()
        val problem = compatProblem(param)
        if (problem.isNotEmpty()) {
            val go = Messages.showOkCancelDialog(
                rootPane,
                problem + "\n\n" + I18n.t(
                    "该参数与列 [${rule.column.name}](${rule.column.typeName}) 的类型不兼容,直接插入会失败。仍要保存吗?",
                    "This parameter is not compatible with column [${rule.column.name}](${rule.column.typeName}) — inserting will fail. Save anyway?",
                ),
                I18n.t("参数与列类型可能不兼容", "Parameter may be incompatible with the column type"),
                Messages.getWarningIcon(),
            ) == Messages.OK
            if (!go) return
        }
        rule.param = param
        onApplied()
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent(): JComponent? = editors.firstOrNull()

    override fun getInitialSize(): Dimension {
        val h = if (rule.type.format == ParamFormat.TEXT_LIST) JBUI.scale(560) else JBUI.scale(430)
        return Dimension(JBUI.scale(660), h)
    }
}

/** 该规则是否有可配置参数(NONE 类规则无需弹窗) */
fun ruleHasConfigurableParam(type: RuleType): Boolean = RuleParamSpec.fields(type).isNotEmpty()
