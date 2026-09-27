package com.github.mockdatagen.core

/**
 * 规则的"自动修复"操作(纯逻辑,无平台依赖,可直接单测)。
 *
 * 插入撞唯一约束后,[com.github.mockdatagen.core.DatabaseAccess] 会按冲突列改写规则再重试一次。
 * 这里只负责"改什么",不碰数据库。
 */
object RuleRepairs {

    /**
     * 把「本次尝试实际生效的规则」叠加回全量规则列表。
     *
     * **取值必须基于返回值**:自动修复改过的列(比如冲突列改成自增数字)如果仍按最初的规则生成,
     * 绑定阶段拿到的还是旧值(撞库的随机整数),重试必然再撞一次。
     */
    fun withActive(all: List<RuleConfig>, active: List<RuleConfig>): List<RuleConfig> = all.map { r ->
        active.firstOrNull { it.column.name == r.column.name } ?: r
    }

    /** 该列是否在这份规则里 */
    fun contains(rules: List<RuleConfig>, column: String): Boolean =
        rules.any { it.column.name.equals(column, ignoreCase = true) }

    /**
     * 把某一列改成「自增数字」,从 [start] 开始按 1 递增 —— 用于"整数唯一列撞了库中已有数据"。
     * 只有**整数**列能这么改:文本/日期列接续不出唯一值。
     */
    fun continueFrom(rules: List<RuleConfig>, column: String, start: Long): List<RuleConfig> = rules.map { r ->
        if (r.column.name.equals(column, ignoreCase = true)) {
            r.copy(type = RuleType.AUTO_INCREMENT, param = "$start,1")
        } else {
            r
        }
    }

    /** 把某一列从插入语句中移除 —— 用于"该列由数据库自己生成"(serial / identity / auto_increment) */
    fun drop(rules: List<RuleConfig>, column: String): List<RuleConfig> =
        rules.filterNot { it.column.name.equals(column, ignoreCase = true) }

    /**
     * 该不该按"接续最大值"修:整数列可以,文本/日期列不行。
     * 拿不到列类型时保守放弃(返回 false),避免生成出仍然会撞的值。
     */
    fun canContinue(rules: List<RuleConfig>, column: String): Boolean {
        val rule = rules.firstOrNull { it.column.name.equals(column, ignoreCase = true) } ?: return false
        return RuleSuggester.isIntegerType(rule.column)
    }
}
