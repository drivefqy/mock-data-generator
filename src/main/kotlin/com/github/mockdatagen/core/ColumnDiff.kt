package com.github.mockdatagen.core

/**
 * 列结构比对:界面上的列(来自 DataGrip 内省缓存) vs 数据库实际列。
 *
 * 为什么需要比对:列名/类型直接决定 INSERT 语句与参数绑定。DataGrip 的内省缓存可能落后于
 * 数据库实际结构(典型场景:建表后又 `ALTER TABLE ADD COLUMN`),此时界面少掉的那几列不会
 * 参与生成;若少掉的恰好是 NOT NULL 列,数据库会直接拒绝整批插入,报
 * `null value in column "xxx" violates not-null constraint`。
 *
 * 插入走的是真实数据库连接,所以列的权威来源也必须是数据库本身。这里只做纯逻辑比对,
 * 由调用方决定是否按数据库结果重建表格。
 */
object ColumnDiff {

    data class Result(
        /** 数据库有、界面没有的列(通常是内省缓存落后) */
        val missing: List<String>,
        /** 界面有、数据库没有的列(列已被删除,或表引用指向了别的对象) */
        val extra: List<String>,
        /** 同名但类型不同的列:列名 -> "界面类型 -> 数据库类型" */
        val retyped: Map<String, String>,
    ) {
        /** 列名集合是否一致;类型差异单独提示,不触发重建 */
        val sameColumns: Boolean get() = missing.isEmpty() && extra.isEmpty()

        val same: Boolean get() = sameColumns && retyped.isEmpty()
    }

    /** 列名比对不区分大小写(不同驱动返回的大小写约定不同) */
    fun compare(ui: List<ColumnMeta>, live: List<ColumnMeta>): Result {
        val uiByName = ui.associateBy { it.name.lowercase() }
        val liveByName = live.associateBy { it.name.lowercase() }

        val missing = live.filter { it.name.lowercase() !in uiByName }.map { it.name }
        val extra = ui.filter { it.name.lowercase() !in liveByName }.map { it.name }

        val retyped = LinkedHashMap<String, String>()
        live.forEach { l ->
            val u = uiByName[l.name.lowercase()] ?: return@forEach
            val a = typeKey(u)
            val b = typeKey(l)
            if (a != b) retyped[l.name] = "${u.typeName} -> ${l.typeName}"
        }

        return Result(missing, extra, retyped)
    }

    /** 差异的可读描述;完全一致时返回 null */
    fun describe(ui: List<ColumnMeta>, live: List<ColumnMeta>): String? {
        val r = compare(ui, live)
        val parts = ArrayList<String>()
        if (r.missing.isNotEmpty()) {
            parts += "数据库有 ${r.missing.size} 列未出现在界面上:${r.missing.joinToString("、")}"
        }
        if (r.extra.isNotEmpty()) {
            parts += "界面多出 ${r.extra.size} 列(数据库中已不存在):${r.extra.joinToString("、")}"
        }
        if (r.retyped.isNotEmpty()) {
            parts += "类型不一致:" + r.retyped.entries.joinToString("、") { "${it.key}(${it.value})" }
        }
        return if (parts.isEmpty()) null else parts.joinToString(";")
    }

    /**
     * 是否需要按数据库结果重建表格。
     *
     * 除列名集合外,**主键/唯一/自增标记**也算结构性差异:DataGrip 内省模型可能没标出主键,
     * 而规则推断(整数主键 → 自增序列)完全依赖这些标记,不重建就会继续按"随机整数"生成,
     * 插入时撞库中已有数据。
     */
    fun needsRebuild(ui: List<ColumnMeta>, live: List<ColumnMeta>): Boolean {
        if (!compare(ui, live).sameColumns) return true
        val uiByName = ui.associateBy { it.name.lowercase() }
        return live.any { l -> uiByName[l.name.lowercase()]?.let { !PrimaryKeys.sameFlags(it, l) } == true }
    }

    /**
     * 归一化类型名后再比较,避免"同一类型的两种写法"被误判成类型变化:
     * 去掉长度/精度 (`numeric(10,2)`)、空格、时区修饰。
     */
    private fun typeKey(c: ColumnMeta): String = c.typeName.lowercase()
        .substringBefore('(')
        .replace("without time zone", "")
        .replace("with time zone", "tz")
        .replace(" ", "")
        .trim()
}
