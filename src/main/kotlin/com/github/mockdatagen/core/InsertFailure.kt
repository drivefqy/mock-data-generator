package com.github.mockdatagen.core

/**
 * 插入失败信息 -> 可操作提示(纯字符串处理,无平台依赖,可直接单测)。
 *
 * 数据库抛出的原文对使用者没有指导意义(尤其 NOT NULL / 唯一约束这两类),而它们几乎总是
 * 由同几种配置问题引起,所以统一转成"哪一列 + 怎么改"。
 */
object InsertFailure {

    /** 把异常链上的 message 拼成一段文本 */
    fun text(error: Throwable): String =
        generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")

    fun hint(messageText: String): String? = notNullHint(messageText) ?: duplicateKeyHint(messageText)

    /** 各数据库/驱动的 NOT NULL 违规消息(PostgreSQL 报错信息是中文的) */
    private val NOT_NULL_PATTERNS = listOf(
        Regex("null value in column [\"`']([^\"`']+)[\"`']"),
        Regex("列 ?[\"`']([^\"`']+)[\"`'] ?中的空值"),
        Regex("Column '([^']+)' cannot be null"),
    )

    /**
     * NOT NULL 违规几乎不是"规则配错",而是「参与生成的列」与「数据库实际列」不一致 ——
     * 典型来源是 DataGrip 内省缓存落后于数据库(表被 ALTER 加了 NOT NULL 列)。
     * 直接给可操作提示,比把数据库原文抛给用户更有用。
     */
    fun notNullHint(text: String): String? {
        val violation = NOT_NULL_PATTERNS.any { it.containsMatchIn(text) } ||
            text.contains("not-null constraint") || text.contains("非空约束") || text.contains("cannot be null")
        if (!violation) return null

        val column = NOT_NULL_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
        val which = if (column.isNullOrBlank()) "存在不允许为空的列" else "列「$column」"
        return "$which 没有参与本次生成,该列不允许为空。" +
            "通常是列结构与数据库实际不一致所致,请点顶部「刷新」重新读取表结构后重试。"
    }

    /**
     * 从报错文本里抠出冲突的列名(唯一约束冲突时数据库会点名列)。
     *
     * PostgreSQL:`详细：键值"(id)=(17)" 已经存在` / 标准 JDBC 与 MySQL:`Key (id)=(17) already exists`。
     * 抠不出来时返回 null —— 调用方据此放弃自动修复,回退到提示文案。
     */
    fun conflictColumn(text: String): String? =
        DUP_COLUMN_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.substringAfterLast('.')
            ?.trim()
            ?.trim('"', '`', '[', ']', '\'')
            ?.takeIf { it.isNotBlank() }

    /** 唯一约束冲突的识别特征(PostgreSQL 报错信息是中文的) */
    private val DUP_KEY_PATTERNS = listOf(
        Regex("""violates unique constraint"""),
        Regex("""违反唯一约束"""),
        Regex("""Duplicate entry"""),
        Regex("""ORA-00001"""),
    )

    /** 从错误里抠出冲突列名 */
    private val DUP_COLUMN_PATTERNS = listOf(
        // PostgreSQL 详细行:键值"(id)=(17)" 已经存在
        Regex("""键值\s*[“"']?\(([^()]+)\)\s*="""),
        // 标准 JDBC / MySQL:Key (id)=(17) already exists
        Regex("""Key \(([^()]+)\)\s*="""),
    )

    /**
     * 唯一约束冲突:
     * PostgreSQL `重复键违反唯一约束"xxx_pkey"`、MySQL `Duplicate entry 'x' for key 'x'`。
     *
     * 最常见的原因是主键/唯一列用了「随机整数」之类的规则 —— 值域窄,必然与库中已有数据撞,
     * 而且失败的是整批。这个提示只在**自动修复也没能救回来**时才给到用户。
     */
    fun duplicateKeyHint(text: String): String? {
        if (DUP_KEY_PATTERNS.none { it.containsMatchIn(text) }) return null

        val column = conflictColumn(text)
        val which = if (column.isNullOrBlank()) "某列" else "列「$column」"
        return buildString {
            append("唯一约束冲突:$which 的新值与表中已有数据(或本批内部)重复。")
            append("\n主键/唯一列的值必须互不相同 —— 建议把该列规则改为「自增数字」,起始值保留 ")
            append(AUTO_START).append("(自动接续库中最大值 + 1);")
            append("若该列本身由数据库自增生成,改用「不生成(使用数据库默认值)」。")
        }
    }
}
