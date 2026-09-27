package com.github.mockdatagen.core

/**
 * 主键 / 唯一列标记(纯逻辑,无平台依赖,可直接单测)。
 *
 * 为什么需要:主键与唯一列的值**必须互不相同**。此前所有整数列(包括主键 `id`)统一走
 * 「随机整数」,默认值域只有 1~100 —— 只要表里已有数据,插入就会以
 * `duplicate key value violates unique constraint "xxx_pkey"`(PostgreSQL)/
 * `Duplicate entry ... for key`(MySQL)失败,而且失败的是整批。
 *
 * 注意标记来源必须与插入用的连接一致:DataGrip 内省模型可能落后于数据库,所以
 * [DatabaseAccess.liveColumns] 会用真实连接的 `getPrimaryKeys` / `getIndexInfo` 重新标记。
 */
object PrimaryKeys {

    /**
     * 按列名(不区分大小写)写入主键/唯一标记。
     *
     * 只有**单列**唯一约束才算:多列联合唯一无法靠单列取值保证,强行按唯一列处理反而会
     * 产生误导性的自动规则。
     */
    fun mark(
        cols: List<ColumnMeta>,
        primaryKeyColumns: Collection<String>,
        uniqueColumns: Collection<String> = emptyList(),
        autoColumns: Collection<String> = emptyList(),
    ): List<ColumnMeta> = cols.map { c ->
        val pk = primaryKeyColumns.any { it.equals(c.name, ignoreCase = true) }
        val uq = !pk && uniqueColumns.any { it.equals(c.name, ignoreCase = true) }
        // 自增标记是"或"关系:驱动元数据与 SQL 探测任何一方认出来都算
        val auto = c.autoIncrement || autoColumns.any { it.equals(c.name, ignoreCase = true) }
        if (pk == c.isPrimaryKey && uq == c.isUnique && auto == c.autoIncrement) {
            c
        } else {
            c.copy(isPrimaryKey = pk, isUnique = uq, autoIncrement = auto)
        }
    }

    /** 该列的值是否必须唯一(主键,或单列唯一约束) */
    fun mustBeUnique(col: ColumnMeta): Boolean = col.isPrimaryKey || col.isUnique

    /** 结构标记的可读摘要,用于日志与界面差异提示 */
    fun describe(col: ColumnMeta): String = buildString {
        if (col.isPrimaryKey) append("主键")
        if (col.isUnique) append(if (isEmpty()) "唯一" else "、唯一")
        if (col.autoIncrement) append(if (isEmpty()) "自增" else "、自增")
    }

    /** 结构标记是否与另一份列元数据一致(决定是否要按数据库结果重建表格/重新推断规则) */
    fun sameFlags(a: ColumnMeta, b: ColumnMeta): Boolean =
        a.isPrimaryKey == b.isPrimaryKey && a.isUnique == b.isUnique && a.autoIncrement == b.autoIncrement
}
