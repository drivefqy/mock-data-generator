package com.github.mockdatagen.ui

import com.github.mockdatagen.core.ColumnDiff
import com.github.mockdatagen.core.ColumnMeta
import com.github.mockdatagen.core.DatabaseAccess
import com.github.mockdatagen.core.I18n
import com.github.mockdatagen.core.Lang
import com.github.mockdatagen.core.MockDataGenerator
import com.github.mockdatagen.core.MockRuleException
import com.github.mockdatagen.core.ParamCompat
import com.github.mockdatagen.core.RuleConfig
import com.github.mockdatagen.core.RuleParamSpec
import com.github.mockdatagen.core.RuleSuggester
import com.github.mockdatagen.core.RuleType
import com.github.mockdatagen.core.TableRef
import com.github.mockdatagen.core.TableSearch
import com.github.mockdatagen.core.TableTarget
import com.github.mockdatagen.core.TableTargets
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import javax.swing.event.DocumentEvent
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.TableModelEvent
import javax.swing.event.TableModelListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * 规则表格模型:5 列 —— 启用 / 列+类型 / 生成规则 / 参数 / 空值比例。
 *
 * 列一律用索引访问([ENABLE]…[NULLS]):列头文案随语言切换,按名字查找会失效。
 */
class RuleTableModel(private val rows: MutableList<RuleConfig>) : AbstractTableModel() {

    override fun getRowCount() = rows.size
    override fun getColumnCount() = COLS
    override fun getColumnName(c: Int) = headerAt(c)
    override fun getColumnClass(c: Int) = when (c) {
        ENABLE -> java.lang.Boolean::class.java
        COLUMN -> ColumnMeta::class.java
        RULE -> RuleType::class.java
        else -> String::class.java
    }

    /** 「参数」列不直接编辑:单击该单元格打开参数配置对话框 */
    override fun isCellEditable(r: Int, c: Int) = c == ENABLE || c == RULE || c == NULLS

    override fun getValueAt(r: Int, c: Int): Any = when (c) {
        ENABLE -> rows[r].enabled
        COLUMN -> rows[r].column
        RULE -> rows[r].type
        PARAM -> RuleParamSpec.summary(rows[r].type, rows[r].param)
        else -> rows[r].nullRatio.toString()
    }

    override fun setValueAt(a: Any, r: Int, c: Int) {
        val row = rows[r]
        when (c) {
            ENABLE -> row.enabled = a as Boolean
            RULE -> {
                val t = a as RuleType
                if (t != row.type) {
                    // 参数格式相同的规则之间切换(如 随机枚举 <-> 循环枚举)保留用户已配置的值
                    val keepParam = t.format == row.type.format && row.param.isNotBlank()
                    row.type = t
                    row.enabled = t != RuleType.SKIP
                    if (!keepParam) row.param = RuleSuggester.defaultParam(t, row.column)
                    fireTableRowsUpdated(r, r)
                    return
                }
            }

            NULLS -> row.nullRatio = a.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
        }
        fireTableCellUpdated(r, c)
    }

    /** 参数配置对话框回写 */
    fun setParam(r: Int, value: String) {
        rows[r].param = value
        fireTableRowsUpdated(r, r)
    }

    fun ruleAt(r: Int) = rows[r]

    /** 该行参数是否与列类型不兼容(用于行内标记,不跑生成引擎) */
    fun issueAt(r: Int): String? {
        val row = rows[r]
        if (!row.enabled || row.type == RuleType.SKIP) return null
        return ParamCompat.issue(row.column, row.type, row.param)
    }

    fun enabledGenCount(): Int = rows.count { it.enabled && it.type != RuleType.SKIP }

    /** 按列名查找(列名不区分大小写):列结构重建时用它保留用户已配置的规则与参数 */
    fun ruleFor(columnName: String): RuleConfig? =
        rows.firstOrNull { it.column.name.equals(columnName, ignoreCase = true) }

    companion object {
        const val ENABLE = 0
        const val COLUMN = 1
        const val RULE = 2
        const val PARAM = 3
        const val NULLS = 4
        const val COLS = 5

        /** 列头文案(按当前语言)。语言切换后 UI 侧需要把它写回 JTableColumn.headerValue */
        fun headerAt(c: Int): String = when (c) {
            ENABLE -> I18n.t("启用", "On")
            COLUMN -> I18n.t("列 / 类型", "Column / Type")
            RULE -> I18n.t("生成规则", "Rule")
            PARAM -> I18n.t("参数", "Parameter")
            else -> I18n.t("空值比例", "Null %")
        }
    }
}

/**
 * 规则下拉渲染:显示规则名与参数提示,并在分组切换处画一条分隔线,
 * 便于在 40+ 条内置规则中快速定位。[all] 必须与下拉框模型顺序一致。
 */
class RuleTypeRenderer(private val all: List<RuleType>) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        val t = value as? RuleType ?: return label
        label.text = t.display
        label.toolTipText = if (t.paramHint.isBlank()) t.group.label else "${t.group.label} · ${t.paramHint}"
        if (index > 0 && !isSelected) {
            val prev = all.getOrNull(index - 1)
            if (prev != null && prev.group != t.group) {
                label.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, UiTokens.border),
                    BorderFactory.createEmptyBorder(1, 2, 1, 2),
                )
            }
        }
        return label
    }
}

/**
 * 表格内「生成规则」单元格渲染:规则名 + **智能推荐**标记。
 *
 * 与当前列自动推断结果一致时,在规则名前画一个青绿圆点 —— 让"这行是系统替我选的"
 * 一眼可见,默认值因此可信(设计规范 §1「推荐优先」)。
 */
class RuleTableCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
        val t = value as? RuleType
        val m = table.model as? RuleTableModel
        val rule = if (m != null && row in 0 until m.rowCount) m.ruleAt(row) else null
        val recommended = rule != null && t != null && RuleSuggester.suggest(rule.column) == t

        label.horizontalAlignment = JLabel.LEFT
        label.text = when {
            t == null -> ""
            // 推荐标记用强调色,规则名用正文色:一个单元格里两种颜色需要 HTML 才能共存
            recommended && !isSelected -> "<html><font color='#0E9488'>\u25CF</font> ${t.display}</html>"
            recommended -> "\u25CF  ${t.display}"
            else -> t.display
        }
        label.toolTipText = when {
            t == null -> null
            recommended -> "${t.group.label} · ${t.display}\n" +
                I18n.t("[推荐] 依据列名与类型自动推断", "[Recommended] inferred from column name and type") +
                "\n${I18n.t("参数", "Parameter")}:${t.paramHint.ifBlank { "—" }}"

            t.paramHint.isBlank() -> "${t.group.label} · ${t.display}"
            else -> "${t.group.label} · ${t.display}\n${I18n.t("参数", "Parameter")}:${t.paramHint}"
        }
        return label
    }
}

/**
 * 「列 / 类型」单元格:自绘列名 + 类型 chip + 主键/唯一/自增徽章。
 *
 * 自绘而非拼 HTML —— Swing 的 HTML 对背景色、圆角支持有限,
 * 徽章要的是"色块 + 小字",直接画最可靠。
 */
class ColumnCellRenderer : JLabel(), TableCellRenderer {

    private var meta: ColumnMeta? = null
    private var selected = false
    private var selectedFg: Color = Color.BLACK

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        meta = value as? ColumnMeta
        selected = isSelected
        selectedFg = table.selectionForeground ?: UiTokens.text1
        isOpaque = true
        background = if (isSelected) table.selectionBackground else table.background
        border = JBUI.Borders.empty(0, 12)
        text = ""   // 内容全部自绘
        toolTipText = meta?.let { "${it.name}  ${it.typeName}${if (it.nullable) "" else "  NOT NULL"}" }
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val m = meta ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val cy = height / 2
            var x = insets.left

            // 列名(选中的那一格加粗)
            g2.font = if (selected) font.deriveFont(java.awt.Font.BOLD) else font
            g2.color = if (selected) selectedFg else UiTokens.text1
            val nfm = g2.fontMetrics
            g2.drawString(m.name, x, cy + nfm.ascent / 2 - JBUI.scale(1))
            x += nfm.stringWidth(m.name) + UiTokens.gap2

            // 类型 chip(浅底小字)
            x = chip(g2, x, cy, m.typeName, UiTokens.text3, UiTokens.surface2)
            x += UiTokens.gap1

            // 徽章:主键 > 唯一 > 自增
            if (m.isPrimaryKey) x = chip(g2, x, cy, "PK", UiTokens.accent, UiTokens.accentSoft) + UiTokens.gap1
            if (m.isUnique) x = chip(g2, x, cy, I18n.t("唯一", "UQ"), UiTokens.brand, UiTokens.brandSoft) + UiTokens.gap1
            if (m.autoIncrement) x = chip(g2, x, cy, I18n.t("自增", "AI"), UiTokens.text2, UiTokens.surface2) + UiTokens.gap1
        } finally {
            g2.dispose()
        }
    }

    /** 画一个圆角色块 + 文字,返回右边界 x */
    private fun chip(g2: Graphics2D, x: Int, cy: Int, label: String, fg: Color, bg: Color): Int {
        val f = font.deriveFont(font.size2D - 1.5f)
        val fm = g2.getFontMetrics(f)
        val w = fm.stringWidth(label) + JBUI.scale(12)
        val h = JBUI.scale(18)
        g2.color = bg
        g2.fillRoundRect(x, cy - h / 2, w, h, JBUI.scale(5), JBUI.scale(5))
        g2.font = f
        g2.color = fg
        g2.drawString(label, x + JBUI.scale(6), cy + fm.ascent / 2 - JBUI.scale(1))
        return x + w
    }
}

class MockDataDialog(
    private val project: Project,
    /** 由「在数据库窗口右击某张表」打开时携带的目标表:表下拉框默认选中它 */
    private val target: TableTarget? = null,
) : DialogWrapper(project) {

    private val log = Logger.getInstance(MockDataDialog::class.java)

    private val dsCombo = JComboBox<String>()
    private val tableCombo = JComboBox<String>()

    /** 表搜索框:按表名/schema 片段过滤 tableCombo,不改变 tablesByDisplayName(权威表集合) */
    private val tableSearchField = SearchTextField()

    /** 表匹配计数:搜索后立刻能看出筛掉/剩下了多少张("共 N 张" / "匹配 M / N") */
    private val matchCountLabel = JLabel(" ").apply {
        foreground = UiTokens.text3
        font = UiTokens.small(font)
    }
    private val reloadButton = JButton()

    /** 分级状态条:一条信息只表达一件事,替代原来塞长句的 JLabel */
    private val statusLabel = UiStatusBar()

    private val rowCountSpinner = JSpinner(SpinnerNumberModel(100, 1, 100_000_000, 100))
    private val previewButton = JButton()
    private val configParamButton = JButton()

    /** 界面语言切换:点一下即切换并就地刷新全部文案,不必重开对话框 */
    private val langButton = JButton()

    /** 底部写入计划:所选表 + 行数 + 参与列数,不用点开任何东西就能预知结果 */
    private val planLabel = JBLabel(" ").apply {
        foreground = UiTokens.text3
        font = UiTokens.small(font)
    }

    /** 语言切换时要重新赋值的文案(组件 -> 更新动作) */
    private val i18nUpdaters = ArrayList<() -> Unit>()

    private var ruleTable: JTable? = null
    private var ruleModel: RuleTableModel? = null

    /** 鼠标悬停的单元格(用于参数列的"可点击"暗示,替代常驻边框) */
    private var hoverRow = -1
    private var hoverCol = -1

    private var selectionGen = 0

    private val dataSources = LinkedHashMap<String, LocalDataSource>()
    private val dataSourceEntries = LinkedHashMap<String, DatabaseAccess.DataSourceEntry>()
    private val tablesByDisplayName = LinkedHashMap<String, TableRef>()
    private val modelTablesByDisplayName = LinkedHashMap<String, DasTable>()
    private val columnsByTable = LinkedHashMap<String, List<ColumnMeta>>()
    private val ruleModelsByTable = LinkedHashMap<String, RuleTableModel>()

    // 列结构以数据库真实连接为权威(内省缓存可能落后)。以下两个集合只在 EDT 上访问:
    // inFlight 防重复建连,done 保证每张表在本次窗口内只核对一次(点「刷新」会重置)。
    private val liveColumnsInFlight = HashSet<String>()
    private val liveColumnsDone = HashSet<String>()

    private var currentDs: LocalDataSource? = null

    /** 右键带来的目标表:匹配到后置空(只自动跳一次,之后听用户的) */
    private var pendingTarget: TableTarget? = target

    // 必须在 init{} 之前声明:init 会走加载路径访问它们。
    // 加载期间用 loadInProgress 防重入,重复刷新直接跳过,避免并发建连。
    private val loadThreadRef = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    private val loadInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastAutoReloadAt = java.util.concurrent.atomic.AtomicLong(0)

    init {
        title = I18n.t("生成 Mock 数据", "Generate Mock Data")
        setCancelButtonText(I18n.t("取消", "Cancel"))
        init()
        loadDataSourceList()
        // 不订阅连接事件:插件自建连接也会触发它,会让已加载结果的代次失效。刷新由「刷新」按钮显式触发。
    }

    /** 非模态:允许用户在对话框开着时切到数据库工具窗口连接数据源 */
    override fun isModal(): Boolean = false

    /** 防止 DialogWrapper 复用旧的窄窗口尺寸,同时避免窗口超出屏幕导致被截断。 */
    override fun getInitialSize(): Dimension {
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val width = minOf(JBUI.scale(1280), (bounds.width * 0.92).toInt())
        val height = minOf(JBUI.scale(820), (bounds.height * 0.90).toInt())
        return Dimension(width, height)
    }

    override fun setSizeDuringPack(): Boolean = true

    // ---------- i18n 辅助 ----------

    private fun JLabel.i18n(zh: String, en: String): JLabel {
        text = I18n.t(zh, en)
        i18nUpdaters.add { text = I18n.t(zh, en) }
        return this
    }

    private fun JButton.i18n(zh: String, en: String): JButton {
        text = I18n.t(zh, en)
        i18nUpdaters.add { text = I18n.t(zh, en) }
        return this
    }

    /**
     * 切换语言后就地刷新:
     * 1) 所有登记过的静态文案;2) 表头(列名随语言变,必须写回 headerValue);3) 表格内容;4) 写入计划
     */
    private fun refreshI18n() {
        i18nUpdaters.forEach { runCatching { it() } }
        ruleTable?.let { t ->
            val cm = t.columnModel
            for (i in 0 until cm.columnCount) {
                cm.getColumn(i).headerValue = RuleTableModel.headerAt(cm.getColumn(i).modelIndex)
            }
            t.tableHeader?.repaint()
            t.repaint()
        }
        // 状态条文本是上次操作时的语言快照,无法按新语言重放,直接清空
        statusLabel.clear()
        // 表计数("共 N 张表" / "M / N matched")同样是快照,按当前语言重算一次
        refreshTableItems(preserveSelection = true)
        updatePlan()
        // DialogWrapper 本身不是 JComponent,重新布局要落到 rootPane 上
        rootPane?.revalidate()
        rootPane?.repaint()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, UiTokens.gap2))

        // 顶部:数据源 + 表(标签用弱化的小字,让下拉框本身成为视觉主体)
        val top = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.gap2, 0))
        dsCombo.preferredSize = Dimension(JBUI.scale(240), dsCombo.preferredSize.height)
        tableCombo.preferredSize = Dimension(JBUI.scale(260), tableCombo.preferredSize.height)
        tableSearchField.preferredSize = Dimension(JBUI.scale(200), tableSearchField.preferredSize.height)
        updateTableSearchPlaceholder()
        i18nUpdaters.add { updateTableSearchPlaceholder() }
        top.add(fieldLabel("数据源", "Data source"))
        top.add(dsCombo)
        top.add(fieldLabel("目标表", "Target table"))
        top.add(tableCombo)
        top.add(fieldLabel("搜索", "Search"))
        top.add(tableSearchField)
        top.add(matchCountLabel)
        top.add(reloadButton.i18n("刷新", "Refresh"))
        panel.add(top, BorderLayout.NORTH)

        // 中部:规则表格(设计规范 §5:行高 44px、只留水平分隔线、无竖线)
        val table = JTable().apply {
            // 实线网格。注意:网格线画在 intercellSpacing 的 1px 间隙里,
            // 把 intercellSpacing / rowMargin 设为 0 时,即使 showGrid=true 也一条线都画不出来。
            setShowGrid(true)
            gridColor = UiTokens.border
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            fillsViewportHeight = true
            // 单元格级选中(点哪格只高亮哪格):JTable 的选择模式由 row / column 两个开关共同决定,
            // 只有两者都为 true 才是"单元格选择"。
            setCellSelectionEnabled(true)
            // 列顺序固定:渲染器与列宽都按索引绑定,允许拖动会让两者错位
            tableHeader.reorderingAllowed = false
            applyRowHeight()
        }
        ruleTable = table
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 单击「参数」单元格即进入编辑(原来是双击,属于隐性知识,新用户发现不了)。
                // 不在这里改选中范围:表格已是单元格级选中,点哪格就只高亮哪一格。
                if (e.clickCount != 1) return
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                if (table.convertColumnIndexToModel(col) == RuleTableModel.PARAM) {
                    openParamDialog(row)
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (hoverRow != -1 || hoverCol != -1) {
                    hoverRow = -1
                    hoverCol = -1
                    table.repaint()
                }
            }
        })
        // 悬停跟踪:参数单元格用淡底色提示"这里可以点"(不再用常驻边框)
        table.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val r = table.rowAtPoint(e.point)
                val c = table.columnAtPoint(e.point)
                if (r != hoverRow || c != hoverCol) {
                    hoverRow = r
                    hoverCol = c
                    table.repaint()
                }
            }
        })
        table.model.addTableModelListener(object : TableModelListener {
            override fun tableChanged(e: TableModelEvent?) = updatePlan()
        })
        val tableScroll = JBScrollPane(table).apply {
            preferredSize = Dimension(JBUI.scale(1120), JBUI.scale(520))
            minimumSize = Dimension(JBUI.scale(820), JBUI.scale(340))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = JBUI.Borders.customLine(UiTokens.border, 1, 1, 1, 1)
        }
        panel.add(tableScroll, BorderLayout.CENTER)

        // 底部:操作行 → 状态条 → 写入计划
        val bottom = JPanel(BorderLayout(0, UiTokens.gap2))
        // 操作行左侧放操作,语言切换固定在最右侧 —— 顶部工具栏空间紧张时 FlowLayout 会换行,
        // 而 BorderLayout.NORTH 只给一行高度,放在那里的按钮会被裁掉(用户就再也切不回中文了)
        val ops = JPanel(BorderLayout())
        val opsLeft = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.gap2, 0))
        opsLeft.add(JLabel().i18n("生成条数:", "Rows:"))
        opsLeft.add(rowCountSpinner)
        opsLeft.add(previewButton.i18n("预览 5 行", "Preview 5 rows"))
        opsLeft.add(configParamButton.i18n("配置参数…", "Configure…"))
        ops.add(opsLeft, BorderLayout.WEST)
        ops.add(
            langButton.apply {
                // 文本固定为「中 / EN」:宽度不随语言变化,按钮位置与命中区域始终稳定
                text = "中 / EN"
                toolTipText = langTooltip()
                addActionListener {
                    I18n.lang = if (I18n.isZh()) Lang.EN else Lang.ZH
                    refreshI18n()
                    toolTipText = langTooltip()
                }
            },
            BorderLayout.EAST,
        )
        val statusWrap = JPanel(BorderLayout(0, UiTokens.gap1))
        statusWrap.add(statusLabel, BorderLayout.NORTH)
        statusWrap.add(planLabel, BorderLayout.SOUTH)
        bottom.add(ops, BorderLayout.NORTH)
        bottom.add(statusWrap, BorderLayout.CENTER)
        panel.add(bottom, BorderLayout.SOUTH)

        // 事件
        dsCombo.addActionListener {
            if (isComboEvent()) {
                pendingTarget = null // 用户手动切换数据源 = 接管选择权,不再自动跳到右键那张表
                loadDataSourceList()
            }
        }
        tableCombo.addActionListener {
            if (isComboEvent()) onTableSelected()
        }
        reloadButton.addActionListener { loadDataSourceList() }
        previewButton.addActionListener { showPreview() }
        configParamButton.addActionListener {
            val row = table.selectedRow
            if (row < 0) {
                statusLabel.warn(I18n.t("请先在规则表格中选择一行", "Select a row in the table first"))
            } else {
                openParamDialog(row)
            }
        }
        rowCountSpinner.addChangeListener { updatePlan() }

        return panel
    }

    private fun updateTableSearchPlaceholder() {
        tableSearchField.textEditor.emptyText.text = I18n.t("搜索表名…", "Search tables…")
        tableSearchField.toolTipText = I18n.t(
            "按表名 / schema 片段过滤(空格分隔多个关键词,不区分大小写)",
            "Filter by table / schema fragment (space-separated, case-insensitive)",
        )
    }

    private fun fieldLabel(zh: String, en: String) = JLabel().i18n(zh, en).apply {
        foreground = UiTokens.text2
        font = UiTokens.small(font)
    }

    /** 语言切换按钮的提示:说明当前语言与点击后的结果 */
    private fun langTooltip(): String = I18n.t(
        "当前界面语言:中文,点击切换到 English",
        "Current UI language: English — click to switch to 中文",
    )

    private var comboGuard = false

    private fun isComboEvent(): Boolean = !comboGuard && SwingUtilities.isEventDispatchThread()

    // ---------- 数据源 ----------

    private fun loadDataSourceList() {
        val selectedBefore = dsCombo.selectedItem as? String
        val entries = DatabaseAccess.dataSourceEntries(project)
        dataSources.clear()
        dataSourceEntries.clear()
        entries.forEach { entry ->
            if (!dataSources.containsKey(entry.name)) {
                dataSources[entry.name] = entry.local
                dataSourceEntries[entry.name] = entry
            }
        }

        if (dataSources.isEmpty()) {
            dsCombo.model = DefaultComboBoxModel(arrayOf(I18n.t("(当前项目没有已配置的数据源)", "(no data source configured)")))
            tableCombo.model = DefaultComboBoxModel(arrayOf())
            statusLabel.warn(I18n.t("请先在数据库工具窗口中创建数据源", "Create a data source in the Database tool window first"))
            return
        }
        comboGuard = true
        dsCombo.model = DefaultComboBoxModel(dataSources.keys.toTypedArray())
        // 由「右击某张表」打开时,先定位到那张表所在的数据源,否则默认选中的数据源里根本没有它
        val targetDs = pendingTarget?.let { t ->
            t.dsName?.let { name -> dataSources.keys.firstOrNull { it.equals(name, ignoreCase = true) } }
                ?: entries.firstOrNull { TableTargets.match(it.modelTables.keys, t) != null }?.name
        }
        val pick = targetDs
            ?: selectedBefore?.takeIf { dataSources.containsKey(it) }
            ?: dataSources.keys.firstOrNull()
        if (pick != null) dsCombo.selectedItem = pick
        comboGuard = false
        applySelectedDataSourceModel()
    }

    private fun applySelectedDataSourceModel() {
        val name = dsCombo.selectedItem as? String ?: return
        val entry = dataSourceEntries[name] ?: return
        currentDs = entry.local
        tablesByDisplayName.clear()
        modelTablesByDisplayName.clear()
        columnsByTable.clear()
        ruleModelsByTable.clear()
        liveColumnsInFlight.clear()
        liveColumnsDone.clear()
        ruleModel = null
        ruleTable?.model = javax.swing.table.DefaultTableModel()
        entry.modelTables.forEach { (displayName, table) ->
            // 带上表所属数据库:一个数据源可能内省了多个库,插入时必须连到表真正所在的库
            tablesByDisplayName[displayName] = entry.tableRefs[displayName]
                ?: TableRef(DasUtil.getSchema(table), table.name, DasUtil.getCatalog(table))
            modelTablesByDisplayName[displayName] = table
        }
        refreshTableItems()
        reloadButton.isEnabled = true
        statusLabel.text = if (tablesByDisplayName.isEmpty()) {
            I18n.t(
                "DataGrip 尚未内省出表,请先在数据库窗口执行刷新(Introspect),再点击刷新",
                "No tables introspected yet — refresh (Introspect) in the Database tool window, then click Refresh",
            )
        } else {
            tableCountText(tablesByDisplayName.size) + I18n.t(",请选择表", ", pick a table")
        }
        log.info("MockData: model UI applied ${tablesByDisplayName.size} tables, selected=${tableCombo.selectedItem}")
        applyPendingTableSelection()
        if (tablesByDisplayName.isNotEmpty()) onTableSelected()
    }

    private fun tableCountText(n: Int): String = if (I18n.isZh()) "共 $n 张表" else "$n tables"

    private fun onDataSourceSelected(force: Boolean = false) {
        val name = dsCombo.selectedItem as? String ?: return
        val ds = dataSources[name] ?: return
        if (!force && ds === currentDs && tablesByDisplayName.isNotEmpty()) return
        if (!loadInProgress.compareAndSet(false, true)) {
            statusLabel.warn(
                I18n.t("表结构正在加载,请稍候或等待当前加载完成后再刷新", "Tables are loading — wait for it to finish before refreshing"),
            )
            return
        }
        currentDs = ds
        val gen = ++selectionGen

        tablesByDisplayName.clear()
        columnsByTable.clear()
        ruleModelsByTable.clear()
        liveColumnsInFlight.clear()
        liveColumnsDone.clear()
        ruleModel = null
        ruleTable?.model = javax.swing.table.DefaultTableModel()
        tableCombo.model = DefaultComboBoxModel(arrayOf(I18n.t("加载中...", "Loading...")))
        dsCombo.isEnabled = false
        reloadButton.isEnabled = false

        val step = java.util.concurrent.atomic.AtomicReference(
            I18n.t("正在查找数据源的现有连接...", "Looking for an existing connection..."),
        )
        val lastStepAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        fun setStep(s: String) {
            step.set(s)
            lastStepAt.set(System.currentTimeMillis())
            log.info("MockData: step: $s")
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed || gen != selectionGen) return@invokeLater
                // 表名一旦加载成功就永久保留在下拉框;后续列进度只更新状态栏
                statusLabel.info(s)
                // 加载期间表列表是"加载中/加载失败"占位,匹配计数暂时无意义(加载完成后由 refreshTableItems 重算)
                matchCountLabel.text = ""
            }
        }

        fun showError(msgRaw: String) {
            log.warn("MockData: load tables failed: $msgRaw")
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed || gen != selectionGen) return@invokeLater
                val msg = esc(msgRaw).replace("\n", "<br>")
                statusLabel.error(
                    "<html><body width='620px'>" +
                        I18n.t("读取表结构失败:", "Failed to read table structure: ") + msg +
                        "</body></html>",
                )
                if (tablesByDisplayName.isEmpty()) {
                    comboGuard = true
                    tableCombo.removeAllItems()
                    tableCombo.addItem(I18n.t("✗ 加载失败,见下方提示", "✗ Load failed, see message below"))
                    comboGuard = false
                }
                reloadButton.isEnabled = true
            }
        }

        // 每次加载用独立 daemon 线程:即使上次任务被数据库卡住,也不影响新加载
        val workerDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread({
            try {
                setStep(I18n.t("正在获取可用连接(优先复用会话连接)...", "Acquiring a connection (reusing session connection if possible)..."))
                val loaded = DatabaseAccess.withConnection(project, ds) { conn ->
                    setStep(I18n.t("连接就绪,正在读取表列表...", "Connected, reading table list..."))
                    DatabaseAccess.loadTables(conn)
                }
                ApplicationManager.getApplication().invokeAndWait {
                    if (isDisposed || gen != selectionGen) return@invokeAndWait
                    tablesByDisplayName.clear()
                    columnsByTable.clear()
                    ruleModelsByTable.clear()
                    liveColumnsInFlight.clear()
                    liveColumnsDone.clear()
                    loaded.forEach { ref -> tablesByDisplayName[ref.displayName] = ref }
                    refreshTableItems()
                    reloadButton.isEnabled = true
                    statusLabel.text = if (tablesByDisplayName.isEmpty()) {
                        I18n.t(
                            "连接正常,但未找到可用的表(请检查权限或 schema 过滤)",
                            "Connected, but no tables found (check permissions or schema filter)",
                        )
                    } else {
                        tableCountText(tablesByDisplayName.size) + I18n.t(",请选择表", ", pick a table")
                    }
                    log.info("MockData: UI applied ${tablesByDisplayName.size} tables, selected=${tableCombo.selectedItem}")
                    applyPendingTableSelection()
                    if (tablesByDisplayName.isNotEmpty()) onTableSelected()
                }
            } catch (e: Throwable) {
                log.warn("MockData: load tables throwable", e)
                val detail = buildString {
                    append(e.javaClass.simpleName)
                    if (!e.message.isNullOrBlank()) append(": ${e.message}")
                    e.cause?.let {
                        append("\n").append(I18n.t("原因:", "Cause:"))
                            .append(" ${it.javaClass.simpleName}: ${it.message ?: I18n.t("无详细信息", "no details")}")
                    }
                }
                showError(detail)
            } finally {
                workerDone.set(true)
                loadThreadRef.compareAndSet(Thread.currentThread(), null)
                loadInProgress.set(false)
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed && gen == selectionGen) dsCombo.isEnabled = true
                }
            }
        }, "mockdatagen-load-$gen").apply { isDaemon = true }
        loadThreadRef.set(worker)
        worker.start()

        // 看门狗:进度停滞超过 30 秒判定卡死,中止加载并报告卡点(加载已结束则立即退出,不覆盖错误提示)
        ApplicationManager.getApplication().executeOnPooledThread {
            while (true) {
                Thread.sleep(2000)
                if (gen != selectionGen || workerDone.get()) return@executeOnPooledThread
                val idleMs = System.currentTimeMillis() - lastStepAt.get()
                if (idleMs > 30_000) {
                    worker.interrupt()
                    log.warn("MockData: load stuck at step '${step.get()}' for ${idleMs}ms, aborted")
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposed || gen != selectionGen) return@invokeLater
                        statusLabel.error(
                            if (I18n.isZh()) {
                                "<html><body width='620px'>读取表结构停滞超过 30 秒,卡在「${step.get()}」阶段,已中止。" +
                                    "<br>常见原因:1) 该连接正被 DataGrip 占用(正在执行长查询或刷新);" +
                                    "2) 数据库已无响应(连接名义上存在但实际不通)。" +
                                    "<br>建议:先在数据库工具窗口双击任意表确认能看到数据,再回来点「刷新」;" +
                                    "若仍卡住,请把 Help → Show Log in Explorer 中 idea.log 里含 MockData 的行发给开发者。" +
                                    "</body></html>"
                            } else {
                                "<html><body width='620px'>Reading the table structure stalled for 30s at \"${step.get()}\" and was aborted." +
                                    "<br>Common causes: 1) the connection is busy in DataGrip (long query or refresh);" +
                                    " 2) the database is not responding." +
                                    "<br>Try double-clicking any table in the Database tool window to verify it works, then click Refresh." +
                                    "</body></html>"
                            },
                        )
                        comboGuard = true
                        tableCombo.removeAllItems()
                        tableCombo.addItem(I18n.t("✗ 已中止(停滞超时),见下方提示", "✗ Aborted (timeout), see message below"))
                        comboGuard = false
                        reloadButton.isEnabled = true
                    }
                    return@executeOnPooledThread
                }
            }
        }
    }

    // ---------- 表搜索与「右击表默认选中」 ----------

    /**
     * 搜索框内容变化:只保留匹配的表,并自动跳到第一个匹配项 —— 边打字边看到目标表。
     * 表列表还没加载出来时不处理(加载完成后 [refreshTableItems] 会按当前关键词重建)。
     */
    private fun onTableFilterChanged() {
        if (isDisposed) return
        if (tablesByDisplayName.isEmpty()) return
        val before = tableCombo.selectedItem as? String
        refreshTableItems(preserveSelection = false)
        val now = tableCombo.selectedItem as? String
        if (now != null && now != before) onTableSelected()
        if (now == null) {
            statusLabel.warn(
                I18n.t(
                    "没有表名匹配「${tableSearchField.text.trim()}」,清空搜索框可恢复全部表",
                    "No table matches \"${tableSearchField.text.trim()}\" — clear the search box to restore all",
                ),
            )
        }
    }

    /**
     * 按当前搜索关键词重建表下拉框。
     *
     * 过滤只作用于下拉框的可见项:[tablesByDisplayName] 始终是完整表集合,
     * 插入时按它取 TableRef,所以搜索不会影响"生成到哪张表"。
     */
    private fun refreshTableItems(preserveSelection: Boolean = true) {
        val keys = tablesByDisplayName.keys.toList()
        val visible = TableSearch.filter(keys, tableSearchField.text)
        val before = tableCombo.selectedItem as? String
        comboGuard = true
        tableCombo.model = DefaultComboBoxModel(visible.toTypedArray())
        val pick = when {
            // 保留原选择:切数据源/重新加载后尽量停在原来那张表上
            preserveSelection && before != null && visible.contains(before) -> before
            visible.isNotEmpty() -> visible.first()
            else -> null
        }
        if (pick != null) tableCombo.selectedItem = pick
        comboGuard = false
        matchCountLabel.text = when {
            keys.isEmpty() -> ""
            visible.size == keys.size -> tableCountText(keys.size)
            else -> if (I18n.isZh()) "匹配 ${visible.size} / ${keys.size}" else "${visible.size} / ${keys.size} matched"
        }
    }

    /**
     * 「在数据库窗口右击某张表」打开时,把下拉框默认选中该表。
     *
     * 只在匹配到的这一刻生效一次,之后用户自己切表/切数据源不再被拉回。
     * 同名表有多张时不做猜测 —— 说清楚让用户自己选,总好过把数据插进另一张表。
     */
    private fun applyPendingTableSelection() {
        val target = pendingTarget ?: return
        val key = TableTargets.match(tablesByDisplayName.keys, target)
        if (key != null) {
            pendingTarget = null
            comboGuard = true
            tableCombo.selectedItem = key
            comboGuard = false
            log.info("MockData: 默认选中右键表 -> $key")
            return
        }
        val sameName = TableTargets.nameCandidates(tablesByDisplayName.keys, target)
        if (sameName.size > 1) {
            pendingTarget = null
            statusLabel.warn(
                if (I18n.isZh()) {
                    "<html><body width='620px'>表名「${target.name}」在当前数据源里有 ${sameName.size} 张:" +
                        "${sameName.joinToString("、")},请手动选择要生成哪一张</body></html>"
                } else {
                    "<html><body width='620px'>There are ${sameName.size} tables named \"${target.name}\" in this data source: " +
                        "${sameName.joinToString(", ")}. Please pick the one you want.</body></html>"
                },
            )
        }
    }

    private fun onTableSelected() {
        val tableName = tableCombo.selectedItem as? String ?: return
        val modelTable = modelTablesByDisplayName[tableName] ?: return
        ruleModelsByTable[tableName]?.let {
            applyRuleModel(it)
            syncColumnsWithDatabase(tableName)
            return
        }
        val cols = columnsByTable.getOrPut(tableName) { DatabaseAccess.loadModelColumns(modelTable) }
        applyColumns(tableName, cols)
        log.info("MockData: 内省缓存列 $tableName -> ${cols.size} 列")
        statusLabel.info(
            I18n.t(
                "表「$tableName」共 ${cols.size} 列(来自 DataGrip 内省缓存,正在与数据库核对…)",
                "Table \"$tableName\": ${cols.size} columns (from DataGrip introspection cache, verifying against the database…)",
            ),
        )
        syncColumnsWithDatabase(tableName)
    }

    /**
     * 与数据库实际列结构核对。
     *
     * 列信息此前完全取自 DataGrip 的内省缓存,缓存落后于数据库时(建表后又 ALTER TABLE 加列)
     * 界面会少列;少掉的 NOT NULL 列不会被生成,插入必然报 not-null 约束失败。
     * 插入走的是真实连接,所以列的权威来源也必须是数据库本身 —— 不一致就按数据库重建表格。
     * 每张表在本次窗口内只核对一次(点「刷新」会重置),避免反复建连。
     */
    private fun syncColumnsWithDatabase(tableName: String) {
        val ds = currentDs ?: return
        val ref = tablesByDisplayName[tableName] ?: return
        if (tableName in liveColumnsDone || !liveColumnsInFlight.add(tableName)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val live = runCatching { DatabaseAccess.liveColumns(project, ds, ref) }
                .onFailure { log.warn("MockData: 读取数据库实际列失败 $tableName", it) }
                .getOrNull()

            ApplicationManager.getApplication().invokeLater {
                liveColumnsInFlight.remove(tableName)
                if (isDisposed || tableCombo.selectedItem != tableName) return@invokeLater
                liveColumnsDone.add(tableName)
                if (live.isNullOrEmpty()) {
                    statusLabel.warn(
                        I18n.t(
                            "表「$tableName」列信息来自 DataGrip 内省缓存(未能连接数据库核对,可点「刷新」重试)",
                            "Table \"$tableName\": columns come from the introspection cache (could not verify against the database — try Refresh)",
                        ),
                    )
                    return@invokeLater
                }
                val shown = columnsByTable[tableName].orEmpty()
                val diff = ColumnDiff.describe(shown, live)
                // 主键/唯一/自增标记也是结构性差异:规则推断依赖它们(整数主键 → 自增序列),
                // 不重建就会继续按"随机整数"生成,插入时撞库中已有数据
                if (!ColumnDiff.needsRebuild(shown, live)) {
                    if (diff == null) {
                        statusLabel.ok(
                            I18n.t(
                                "表「$tableName」共 ${live.size} 列(已与数据库结构核对一致)",
                                "Table \"$tableName\": ${live.size} columns (verified against the database)",
                            ),
                        )
                    } else {
                        // 仅类型表述不同(如 timestamp/timestamptz)不值得重置表格,提示即可
                        statusLabel.info(
                            "<html><body width='620px'>" +
                                I18n.t("列信息与数据库存在差异:", "Column info differs from the database: ") + esc(diff) +
                                "<br>" + I18n.t("列名集合一致,继续使用当前配置。", "Column names match — keeping the current configuration.") +
                                "</body></html>",
                        )
                    }
                    return@invokeLater
                }
                log.warn("MockData: 列结构与数据库不一致 $tableName -> ${diff ?: "主键/唯一/自增标记差异"}")
                rebuildWithLiveColumns(tableName, live)
                // 把探到的结构事实直接说出来:这是"为什么 id 不再参与生成"的答案
                val flags = live
                    .filter { it.isPrimaryKey || it.isUnique || it.autoIncrement }
                    .joinToString(I18n.t("、", ", ")) { c ->
                        val marks = buildList {
                            if (c.isPrimaryKey) add(I18n.t("主键", "PK"))
                            if (c.isUnique) add(I18n.t("唯一", "unique"))
                            if (c.autoIncrement) add(I18n.t("自增", "auto"))
                        }
                        if (marks.isEmpty()) c.name else "${c.name}(${marks.joinToString("/")})"
                    }
                statusLabel.info(
                    "<html><body width='620px'>" +
                        I18n.t("已按数据库实际结构刷新列", "Columns refreshed from the actual database structure") +
                        (if (flags.isEmpty()) "" else I18n.t(",数据库报告:$flags", ", database reports: $flags")) +
                        (if (diff == null) "" else "<br>" + I18n.t("差异:", "Diff: ") + esc(diff)) +
                        "<br>" + I18n.t("现共 ${live.size} 列", "Now ${live.size} columns") +
                        "</body></html>",
                )
            }
        }
    }

    private fun applyColumns(tableName: String, cols: List<ColumnMeta>) {
        val model = ruleModelsByTable.getOrPut(tableName) { buildRuleModel(cols, null) }
        applyRuleModel(model)
    }

    /** 按数据库实际列重建表格,按列名尽量保留用户已配置的规则与参数 */
    private fun rebuildWithLiveColumns(tableName: String, cols: List<ColumnMeta>) {
        val previous = ruleModelsByTable[tableName]
        columnsByTable[tableName] = cols
        val model = buildRuleModel(cols, previous)
        ruleModelsByTable[tableName] = model
        applyRuleModel(model)
    }

    private fun buildRuleModel(cols: List<ColumnMeta>, previous: RuleTableModel?): RuleTableModel {
        val rows = cols.map { c ->
            val old = previous?.ruleFor(c.name)
            when {
                old == null -> inferredRule(c)

                // 用户改过规则或参数:原样保留,只把列元数据换成最新的
                !isUntouchedInference(old) -> RuleConfig(c, old.enabled, old.type, old.param, old.nullRatio)

                // 仍是自动推断的结果,且列结构没变:保留(顺带保住 enabled)
                RuleSuggester.suggest(c) == RuleSuggester.suggest(old.column) ->
                    RuleConfig(c, old.enabled, old.type, old.param, old.nullRatio)

                // 仍是自动推断的结果,但列结构变了 —— 按新结构重新推断。
                // 典型场景:内省缓存没标出主键,与数据库核对后才发现;
                // 整数主键必须改成自增序列,否则「随机整数」会撞库中已有数据。
                else -> inferredRule(c)
            }
        }
        return RuleTableModel(rows.toMutableList())
    }

    private fun inferredRule(c: ColumnMeta): RuleConfig {
        val type = RuleSuggester.suggest(c)
        return RuleConfig(c, type != RuleType.SKIP, type, RuleSuggester.defaultParam(type, c), 0)
    }

    /** 该行是否仍是「未被用户改动过的自动推断结果」(规则、参数、启用状态、空值比例都是默认值) */
    private fun isUntouchedInference(row: RuleConfig): Boolean {
        val inferred = RuleSuggester.suggest(row.column)
        return row.type == inferred &&
            row.param == RuleSuggester.defaultParam(inferred, row.column) &&
            row.enabled == (inferred != RuleType.SKIP) &&
            row.nullRatio == 0
    }

    private fun applyRuleModel(model: RuleTableModel) {
        // 表格内容换了一批,旧的悬停坐标不再对应原来的行,清掉以免出现"莫名的高亮"
        hoverRow = -1
        hoverCol = -1
        ruleModel = model
        ruleTable?.model = model
        // 换模型会丢掉挂在旧模型上的监听器,这里重新挂(用于刷新底部写入计划)
        model.addTableModelListener(object : TableModelListener {
            override fun tableChanged(e: TableModelEvent?) = updatePlan()
        })

        val cm = ruleTable?.columnModel ?: return
        cm.getColumn(RuleTableModel.RULE).apply {
            val combo = JComboBox(RuleType.ordered.toTypedArray())
            combo.maximumRowCount = 24
            combo.preferredSize = Dimension(JBUI.scale(300), combo.preferredSize.height)
            combo.renderer = RuleTypeRenderer(RuleType.ordered)
            cellEditor = object : javax.swing.DefaultCellEditor(combo) {
                override fun getCellEditorValue(): Any = combo.selectedItem as? RuleType ?: RuleType.SKIP
            }
            cellRenderer = RuleTableCellRenderer()
        }

        cm.getColumn(RuleTableModel.COLUMN).cellRenderer = ColumnCellRenderer()

        cm.getColumn(RuleTableModel.PARAM).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int,
            ): Component {
                val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val m = table.model as? RuleTableModel
                val rule = if (m != null && row in 0 until m.rowCount) m.ruleAt(row) else null
                if (rule == null) {
                    label.text = ""
                    label.border = null
                    label.toolTipText = null
                    return label
                }
                val editable = ruleHasConfigurableParam(rule.type)
                val issue = m?.issueAt(row)
                val hovered = row == hoverRow && column == hoverCol
                label.text = if (editable) RuleParamSpec.summary(rule.type, rule.param) else "—"
                // 字体加黑:正文色从 text2(灰)提到 text1(近黑);停用行仍保持灰
                label.foreground = when {
                    !rule.enabled -> UiTokens.text3
                    issue != null -> UiTokens.danger
                    else -> UiTokens.text1
                }
                // 选中那一格文字加粗,配合蓝底做强调
                label.font = if (isSelected) table.font.deriveFont(java.awt.Font.BOLD) else table.font
                label.background = when {
                    isSelected -> table.selectionBackground
                    issue != null -> UiTokens.dangerSoft
                    hovered && editable -> UiTokens.surfaceHover
                    else -> table.background
                }
                label.isOpaque = true
                // 参数单元格不加边框:靠列间竖线分隔,"可点击"由悬停底色 + tooltip 表达。
                // 仅「参数与列类型不兼容」用红框 + 红底做稳定标记(不用 ValidationInfo,避免提示反复刷新)
                label.border = if (issue != null) {
                    BorderFactory.createCompoundBorder(
                        JBUI.Borders.customLine(UiTokens.danger, 1, 1, 1, 1),
                        JBUI.Borders.empty(2, 8),
                    )
                } else {
                    JBUI.Borders.empty(2, 8)
                }
                label.toolTipText = buildString {
                    append(I18n.t("规则:", "Rule: ")).append(rule.type.display)
                    if (rule.type.paramHint.isNotBlank()) {
                        append("\n").append(I18n.t("参数格式:", "Parameter format: ")).append(rule.type.paramHint)
                    }
                    append("\n").append(I18n.t("当前参数:", "Current: ")).append(rule.param.ifBlank { I18n.t("(空)", "(empty)") })
                    when {
                        issue != null -> append("\n\n⚠ ").append(issue)
                        editable -> append("\n").append(I18n.t("单击该单元格即可编辑", "Click to edit"))
                    }
                }
                return label
            }
        }

        // 列宽总和 ≈ 1060px:一屏放得下,不再需要横向滚动(小窗口仍保留滚动条兜底)
        setRuleColumnWidth(RuleTableModel.ENABLE, 62, 56, 72)
        setRuleColumnWidth(RuleTableModel.COLUMN, 340, 260, 560)
        setRuleColumnWidth(RuleTableModel.RULE, 190, 150, 320)
        setRuleColumnWidth(RuleTableModel.PARAM, 380, 280, 760)
        setRuleColumnWidth(RuleTableModel.NULLS, 96, 78, 130)
        ruleTable?.apply {
            applyRowHeight()
            doLayout()
            revalidate()
            repaint()
        }
        updatePlan()
    }

    /** 刷新底部「写入计划」:目标表 + 行数 + 参与列数 + 不兼容列提示 */
    private fun updatePlan() {
        val model = ruleModel
        val tableName = tableCombo.selectedItem as? String
        if (model == null || tableName == null) {
            planLabel.text = " "
            return
        }
        val on = model.enabledGenCount()
        val bad = (0 until model.rowCount).count { model.issueAt(it) != null }
        planLabel.text = if (I18n.isZh()) {
            buildString {
                append("将向 ").append(tableName).append(" 写入 ")
                append(String.format("%,d", count())).append(" 行,")
                append(on).append(" 列参与生成")
                if (bad > 0) append("   ·   ").append(bad).append(" 列参数与列类型不兼容")
            }
        } else {
            buildString {
                append("Insert ").append(String.format("%,d", count())).append(" rows into ")
                append(tableName).append(", ").append(on).append(" columns generated")
                if (bad > 0) append("   ·   ").append(bad).append(" columns have incompatible parameters")
            }
        }
        planLabel.foreground = if (bad > 0) UiTokens.danger else UiTokens.text3
    }

    /** 打开参数配置对话框(枚举候选值、时间范围、数值区间、正则模板等) */
    private fun openParamDialog(row: Int) {
        val model = ruleModel ?: return
        if (row !in 0 until model.rowCount) return
        val rule = model.ruleAt(row)
        if (!ruleHasConfigurableParam(rule.type)) {
            statusLabel.info(
                I18n.t(
                    "规则「${rule.type.display}」没有可配置参数,请先在「生成规则」列选择其他规则",
                    "Rule \"${rule.type.display}\" has no configurable parameters — pick another rule first",
                ),
            )
            return
        }
        // 参数对话框是模态的,不会同时存在两个实例,无需持有引用
        RuleParamDialog(project, rule) { model.setParam(row, rule.param) }.show()
    }

    /**
     * 空 JTable 的 preferredSize.height 接近 0,不能作为行高基准,否则行高只有几像素、文字上下重叠。
     * 行高按当前字体实际行高计算,保证中英文都能完整显示;留白比旧版多一点(设计规范 §4:行高 ≈ 44px)。
     */
    private fun JTable.applyRowHeight() {
        rowHeight = getFontMetrics(font).height + UiTokens.rowPad
    }

    private fun setRuleColumnWidth(index: Int, preferred: Int, min: Int, max: Int) {
        ruleTable?.columnModel?.getColumn(index)?.apply {
            val scaledPreferred = JBUI.scale(preferred)
            minWidth = JBUI.scale(min)
            maxWidth = JBUI.scale(max)
            preferredWidth = scaledPreferred
            width = scaledPreferred
        }
    }

    // ---------- 预览 ----------

    private fun showPreview() {
        val model = ruleModel ?: return
        val rows = count()
        val gen = MockDataGenerator()
        // MySQL 把反斜杠当转义字符,预览里的字面量必须按它的规则转义才不会被"提前结束字符串"
        val url = currentDs?.url.orEmpty()
        val mysql = url.startsWith("jdbc:mysql", ignoreCase = true) ||
            url.startsWith("jdbc:mariadb", ignoreCase = true)
        val sqlRows = StringBuilder()
        try {
            for (i in 0 until 5) {
                val row = gen.generateRow((0 until model.rowCount).map { model.ruleAt(it) }, i.toLong())
                val parts = (0 until model.rowCount)
                    .map { model.ruleAt(it) }
                    .filter { it.enabled && it.type != RuleType.SKIP }
                    .map { gen.toSqlLiteral(row[it.column.name], mysql) }
                if (parts.isEmpty()) {
                    statusLabel.warn(I18n.t("没有列启用生成规则", "No column has a generation rule enabled"))
                    return
                }
                sqlRows.append("INSERT INTO ${tableCombo.selectedItem} (${columnsOf(model)}) VALUES (${parts.joinToString(", ")});\n")
            }
        } catch (e: MockRuleException) {
            statusLabel.error(e.message ?: I18n.t("规则参数不合法", "Invalid rule parameter"))
            return
        }

        // 预览是"看到将要写入的值";真正的插入走绑定参数,不经过这里的字面量 —— 说明清楚,避免被当成可执行脚本
        val note = I18n.t(
            "-- 预览仅供查看:实际插入使用参数绑定,不经过下列字面量\n\n",
            "-- Preview only: the actual insert uses bound parameters, not these literals\n\n",
        )
        val area = JTextArea(note + sqlRows.toString(), 14, 90)
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        val title = I18n.t("预览(共 $rows 行,展示前 5 行)", "Preview (of $rows rows, first 5 shown)")
        val dlg = JDialog(SwingUtilities.getWindowAncestor(rootPane) as? JDialog, title, false)
        dlg.contentPane.add(JBScrollPane(area))
        dlg.pack()
        dlg.setLocationRelativeTo(rootPane)
        dlg.isVisible = true
        dlg.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) = dlg.dispose()
        })
    }

    private fun columnsOf(model: RuleTableModel): String =
        (0 until model.rowCount)
            .map { model.ruleAt(it) }
            .filter { it.enabled && it.type != RuleType.SKIP }
            .joinToString(", ") { it.column.name }

    private fun count(): Int = (rowCountSpinner.value as Number).toInt()

    // ---------- 提交 ----------

    private fun validateRules(): Boolean {
        val model = ruleModel
        if (model == null) {
            statusLabel.warn(I18n.t("请选择表", "Pick a table"))
            return false
        }
        val tableName = tableCombo.selectedItem as? String
        if (tableName == null || !tablesByDisplayName.containsKey(tableName)) {
            statusLabel.warn(I18n.t("请选择表", "Pick a table"))
            return false
        }
        if (model.enabledGenCount() == 0) {
            statusLabel.warn(I18n.t("至少为一列配置生成规则", "Configure a rule for at least one column"))
            return false
        }
        // 先做静态兼容性检查(不跑引擎):能明确指出是哪一列、怎么改
        val issues = (0 until model.rowCount).mapNotNull { r -> model.issueAt(r)?.let { r to it } }
        if (issues.isNotEmpty()) {
            val (row, msg) = issues.first()
            val more = if (issues.size > 1) {
                I18n.t(
                    "<br>另有 ${issues.size - 1} 列存在同类问题(表格中已标红)。",
                    "<br>${issues.size - 1} more column(s) have the same problem (marked red in the table).",
                )
            } else {
                ""
            }
            statusLabel.error(
                // msg 里含列名(来自数据库元数据),拼进 HTML 之前必须转义
                "<html><body width='620px'>${esc(msg)}$more" +
                    "<br>" + I18n.t("单击表格中该行「参数」单元格即可修改。", "Click the Parameter cell of that row to fix it.") +
                    "</body></html>",
            )
            ruleTable?.let { t ->
                // 定位到出问题的那一格(单元格级选中,不再整行高亮)
                t.changeSelection(row, RuleTableModel.PARAM, false, false)
                t.scrollRectToVisible(t.getCellRect(row, RuleTableModel.PARAM, true))
            }
            return false
        }
        // 再跑一次引擎兜底(能抓到静态检查没覆盖的规则参数问题)。失败时定位到具体列 ——
        // 引擎报错原文里没有列名,只显示原文会让人不知道该改哪一行
        val rules = (0 until model.rowCount).map { model.ruleAt(it) }
        return try {
            MockDataGenerator().generateRow(rules, 0L)
            true
        } catch (e: MockRuleException) {
            val badIdx = rules.indices.filter { rules[it].enabled && rules[it].type != RuleType.SKIP }
                .firstOrNull { r -> runCatching { MockDataGenerator().generateRow(listOf(rules[r]), 0L) }.isFailure }
            statusLabel.error(if (badIdx == null) {
                esc(e.message)
            } else {
                "<html><body width='620px'>" +
                    I18n.t(
                        "列「${esc(rules[badIdx].column.name)}」(${esc(rules[badIdx].column.typeName)})的参数不合法:",
                        "Column \"${esc(rules[badIdx].column.name)}\" (${esc(rules[badIdx].column.typeName)}): invalid parameter — ",
                    ) +
                    esc(e.message) +
                    "<br>" + I18n.t(
                        "单击该行「参数」单元格重新配置,或改选其他生成规则。",
                        "Click the Parameter cell of that row to reconfigure, or pick another rule.",
                    ) +
                    "</body></html>"
            })
            if (badIdx != null) ruleTable?.let { t ->
                t.changeSelection(badIdx, RuleTableModel.PARAM, false, false)
                t.scrollRectToVisible(t.getCellRect(badIdx, RuleTableModel.PARAM, true))
            }
            false
        }
    }

    private fun esc(s: String?): String =
        (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun doOKAction() {
        if (!validateRules()) return
        val ds = currentDs ?: return
        val tableName = tableCombo.selectedItem as? String ?: return
        val ref = tablesByDisplayName[tableName] ?: return
        val model = ruleModel ?: return
        val rows = count()
        val rules = (0 until model.rowCount).map { model.ruleAt(it) }

        super.doOKAction() // 先关闭对话框

        val taskTitle = I18n.t("生成 Mock 数据", "Generating mock data")
        val task = object : com.intellij.openapi.progress.Task.Backgroundable(project, taskTitle, true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                val start = System.currentTimeMillis()
                // 自动调整(唯一冲突后改列生成方式)发生在后台线程,先收集再统一展示
                val notices = java.util.Collections.synchronizedList(ArrayList<String>())
                val inserted = DatabaseAccess.insertRows(
                    project, ds, ref, rules, rows, indicator,
                    onProgress = { done -> indicator.fraction = done.toDouble() / rows },
                    onNotice = { notices.add(it) },
                )
                val cost = System.currentTimeMillis() - start
                showInfo(
                    I18n.t("Mock 数据生成完成", "Mock data generated"),
                    buildString {
                        append(
                            I18n.t(
                                "已向表「$tableName」插入 $inserted 行数据,耗时 ${cost}ms。",
                                "Inserted $inserted rows into \"$tableName\" in ${cost}ms.",
                            ),
                        )
                        if (notices.isNotEmpty()) {
                            append("\n\n").append(I18n.t("为避开唯一约束,已自动调整:", "Adjusted automatically to avoid unique violations:"))
                            append("\n")
                            append(notices.joinToString("\n") { "· $it" })
                        }
                    },
                )
            }
        }
        task.queue()
    }

    private fun showInfo(title: String, message: String) {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("MockDataGenerator")
                .createNotification(title, message, com.intellij.notification.NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
