package com.github.mockdatagen

import com.github.mockdatagen.core.TableTarget
import com.github.mockdatagen.ui.MockDataDialog
import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbElement
import com.intellij.database.util.DasUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/** 插件入口:Tools 菜单 / 数据库工具窗口右键菜单 */
class MockDataAction : AnAction(
    MockDataBundle.message("action.generate.text"),
    MockDataBundle.message("action.generate.description"),
    com.intellij.icons.AllIcons.Actions.Execute,
) {

    private val log = Logger.getInstance(MockDataAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        // 在数据库窗口右击某张表打开时,把这张表带进对话框作为默认选中项;
        // 从 Tools 菜单打开时上下文里没有表,传 null,行为与以前一致。
        MockDataDialog(project, selectedTableTarget(e)).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * 取出「被右击的那张表」。
     *
     * 数据库树把选中的对象放在 PSI_ELEMENT 上,多选/编辑器导航场景只给
     * PSI_ELEMENT_ARRAY / NAVIGATABLE,所以逐个兜底;全都拿不到就当没选中。
     */
    private fun selectedTableTarget(e: AnActionEvent): TableTarget? = runCatching {
        val table = pickTable(e) ?: return@runCatching null
        val dsName = (table as? DbElement)?.dataSource?.name
        val target = TableTarget(dsName, DasUtil.getSchema(table), table.name, DasUtil.getCatalog(table))
        log.info("MockData: 右键上下文中的表 ${target.fullName}(数据源:${dsName ?: "未知"})")
        target
    }.onFailure { log.warn("MockData: 解析右键选中的表失败", it) }.getOrNull()

    private fun pickTable(e: AnActionEvent): DasTable? {
        val candidates = listOfNotNull(
            e.getData(CommonDataKeys.PSI_ELEMENT),
            e.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.firstOrNull(),
            e.getData(CommonDataKeys.NAVIGATABLE),
            e.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.firstOrNull(),
        )
        return candidates.firstOrNull { it is DasTable } as? DasTable
    }
}
