package com.github.mockdatagen.core

import java.sql.Types

/**
 * 参数与列类型的**静态兼容性**检查:纯字符串判断,不跑生成引擎。
 *
 * 只做判断,**由调用方决定怎么呈现**(表格标红、对话框内联提示、保存前确认)。
 * 不要把这类检查做成 `doValidate()` 的 ValidationInfo —— 那样提示会随输入反复刷新。
 */
object ParamCompat {

    /** 返回不兼容原因(可直接展示给用户);兼容则返回 null */
    fun issue(column: ColumnMeta, type: RuleType, param: String): String? {
        val p = param.trim()
        return when (type.format) {
            ParamFormat.TEXT_LIST -> textList(column, p)
            ParamFormat.INT_PAIR -> intPair(column, p)
            ParamFormat.DECIMAL_RANGE -> decimalRange(column, p)
            ParamFormat.START_STEP -> startStep(column, p)
            else -> null
        }
    }

    /** 列本身是否数值类型(决定"文本候选值"能不能直接用) */
    fun isNumericColumn(c: ColumnMeta): Boolean = c.jdbcType in NUMERIC_TYPES

    private fun textList(column: ColumnMeta, p: String): String? {
        val values = p.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) {
            return I18n.t("候选值不能为空,请至少填写一个值", "Candidates cannot be empty — enter at least one value")
        }
        if (isNumericColumn(column)) {
            val bad = values.firstOrNull { it.toDoubleOrNull() == null }
            if (bad != null) {
                return I18n.t(
                    "列「${column.name}」是 ${column.typeName} 数值类型,候选值「$bad」不是数字,直接插入会失败",
                    "Column \"${column.name}\" is numeric (${column.typeName}), but candidate \"$bad\" is not a number — insert will fail",
                )
            }
        }
        return null
    }

    private fun intPair(column: ColumnMeta, p: String): String? {
        val parts = p.split(',').map { it.trim() }
        if (parts.size < 2 || parts.any { it.toLongOrNull() == null }) {
            return I18n.t(
                "列「${column.name}」的参数需要「最小值,最大值」两个整数",
                "Column \"${column.name}\" needs two integers: \"min,max\"",
            )
        }
        val a = parts[0].toLong()
        val b = parts[1].toLong()
        if (b < a) {
            return I18n.t(
                "列「${column.name}」的最大值不能小于最小值($a > $b)",
                "Column \"${column.name}\": max must not be less than min ($a > $b)",
            )
        }
        return null
    }

    private fun decimalRange(column: ColumnMeta, p: String): String? {
        val parts = p.split(',').map { it.trim() }
        if (parts.size < 2 || parts[0].toDoubleOrNull() == null || parts[1].toDoubleOrNull() == null) {
            return I18n.t(
                "列「${column.name}」的参数需要「最小值,最大值[,小数位]」",
                "Column \"${column.name}\" needs \"min,max[,scale]\"",
            )
        }
        val a = parts[0].toDouble()
        val b = parts[1].toDouble()
        if (b < a) {
            return I18n.t(
                "列「${column.name}」的最大值不能小于最小值($a > $b)",
                "Column \"${column.name}\": max must not be less than min ($a > $b)",
            )
        }
        return null
    }

    private fun startStep(column: ColumnMeta, p: String): String? {
        val first = p.split(',').firstOrNull()?.trim().orEmpty()
        if (isAutoStartValue(first)) return null
        if (first.toLongOrNull() == null) {
            return I18n.t(
                "列「${column.name}」的起始值必须是整数,或填 auto(接续库中最大值 + 1)",
                "Column \"${column.name}\": start must be an integer, or auto (continue from max + 1)",
            )
        }
        return null
    }

    private val NUMERIC_TYPES = setOf(
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
        Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC,
    )
}
