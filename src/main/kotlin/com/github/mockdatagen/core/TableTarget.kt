package com.github.mockdatagen.core

/**
 * 「右击某张表」打开插件时携带的目标表。
 *
 * 表在下拉框里的键有两种格式:一个数据源只内省了一个库时是 `schema.table`,
 * 内省了多个库时是 `catalog.schema.table`(见 [DatabaseAccess.dataSourceEntries])。
 * 这里把两种要素都带上,由 [TableTargets.match] 去和实际键匹配 —— 界面不必知道键的格式。
 */
data class TableTarget(
    /** 所在数据源名称;读不到时为 null(此时按表名在所有数据源里找) */
    val dsName: String?,
    val schema: String?,
    val name: String,
    val catalog: String? = null,
) {
    val displayName: String = if (schema.isNullOrBlank()) name else "$schema.$name"

    /** 含库名的完整标识 */
    val fullName: String = if (catalog.isNullOrBlank()) displayName else "$catalog.$displayName"
}

object TableTargets {

    /**
     * 在「下拉框里实际存在的键」中找出目标表,按「最确定 → 最宽松」逐级降级:
     * 1. 含库名的完整标识;2. `schema.表`;3. 键以 `.schema.表` 结尾(库名写法不同时的兜底);
     * 4. 表名相同 —— 3、4 两步都要求候选唯一。
     *
     * 一旦有多个候选就返回 null —— 宁可保持默认(第一张表),也不能猜错表:
     * 猜错的代价是把数据插进了另一张同名的表。
     */
    fun match(keys: Collection<String>, target: TableTarget?): String? {
        if (target == null || keys.isEmpty()) return null
        keys.firstOrNull { it.equals(target.fullName, ignoreCase = true) }?.let { return it }
        keys.firstOrNull { it.equals(target.displayName, ignoreCase = true) }?.let { return it }
        keys.filter { it.endsWith(".${target.displayName}", ignoreCase = true) }.singleOrNull()?.let { return it }
        return nameCandidates(keys, target).singleOrNull()
    }

    /** 表名与目标相同的候选(不含 schema/库名比较);用于「同名多张,请手动选择」的提示 */
    fun nameCandidates(keys: Collection<String>, target: TableTarget?): List<String> {
        if (target == null) return emptyList()
        return keys.filter { it.substringAfterLast('.').equals(target.name, ignoreCase = true) }
    }
}

/** 表下拉框的搜索 */
object TableSearch {

    /**
     * 过滤表键:查询为空(或全空白)时返回全部并保持原顺序;否则要求每个空格分隔的关键词
     * 都出现在键中 —— 于是 `public user` 这种写法既能跨 schema,也能跨库名命中。
     * 不区分大小写。
     */
    fun filter(keys: Collection<String>, query: String?): List<String> {
        val all = keys.toList()
        val tokens = query.orEmpty().trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return all
        return all.filter { key ->
            val text = key.lowercase()
            tokens.all { text.contains(it) }
        }
    }
}
