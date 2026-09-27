package com.github.mockdatagen.core

/**
 * 表结构探测(主键 / 单列唯一 / 自增列)—— 用 SQL 直查,不用驱动元数据。
 *
 * 驱动元数据在 PostgreSQL 上会**静默失效**:`getPrimaryKeys` / `getIndexInfo` 返回空集且不抛异常,
 * `IS_AUTOINCREMENT` 对 `bigserial` 也不是 YES。这个事实一旦丢失,整数主键会被当成普通整数列用
 * 「随机整数」生成,只要表里已有数据,整批插入必然撞唯一约束。
 *
 * `information_schema` 是 ANSI 标准(PG / MySQL / SQL Server / H2 都支持),且与插入走同一条连接,
 * 拿到的是数据库自己的答案。自增的三种形态:`serial` → `column_default = nextval(...)`,
 * `GENERATED AS IDENTITY` → `is_identity = 'YES'`,MySQL → `extra` 含 `auto_increment`。
 */
object StructureSql {

    /** 表结构事实(列名,大小写按数据库返回原样) */
    data class Structure(
        val primaryKeys: Set<String> = emptySet(),
        /** 单列唯一约束列(不含主键);多列联合唯一不入此集合 */
        val uniqueColumns: Set<String> = emptySet(),
        /** 由数据库自动生成的列(serial / identity / auto_increment) */
        val autoColumns: Set<String> = emptySet(),
    ) {
        operator fun plus(other: Structure) = Structure(
            primaryKeys + other.primaryKeys,
            uniqueColumns + other.uniqueColumns,
            autoColumns + other.autoColumns,
        )

        val isEmpty: Boolean
            get() = primaryKeys.isEmpty() && uniqueColumns.isEmpty() && autoColumns.isEmpty()

        /** 日志/界面用的一行摘要 */
        fun describe(): String =
            "主键=[${primaryKeys.joinToString(",")}] " +
                "单列唯一=[${uniqueColumns.joinToString(",")}] " +
                "自增=[${autoColumns.joinToString(",")}]"
    }

    /** 约束查询的一行:某个约束的一列 */
    data class ConstraintRow(val type: String, val name: String, val column: String)

    /** 列查询的一行 */
    data class ColumnRow(
        val name: String,
        val isIdentity: Boolean,
        val columnDefault: String?,
        val extra: String? = null,
    )

    /**
     * 带**绑定参数**的 SQL:表名与 schema 一律走 `?` 绑定,绝不拼进 SQL 字符串 ——
     * 拼接要靠转义覆盖所有数据库的规则,漏一种(如 MySQL 的 `\`)就是注入。
     */
    data class Sql(val text: String, val params: List<String>)

    /**
     * 主键与唯一约束。
     *
     * `key_column_usage` 每个约束的每一列一行,所以联合约束会有多行 —— 由 [fromConstraints]
     * 按约束名分组后只保留单列约束。
     */
    fun constraintSql(schema: String?, table: String): Sql = Sql(
        text = buildString {
            append("SELECT tc.constraint_type, tc.constraint_name, kcu.column_name")
            append(" FROM information_schema.table_constraints tc")
            append(" JOIN information_schema.key_column_usage kcu")
            append(" ON kcu.constraint_name = tc.constraint_name")
            append(" AND kcu.table_schema = tc.table_schema AND kcu.table_name = tc.table_name")
            append(" WHERE tc.constraint_type IN ('PRIMARY KEY','UNIQUE')")
            append(" AND tc.table_name = ?")
            if (!schema.isNullOrBlank()) append(" AND tc.table_schema = ?")
        },
        params = listOfNotNull(table, schema?.takeIf { it.isNotBlank() }),
    )

    /** 列的默认值与 identity 标记(PostgreSQL 的 serial 就是 `nextval(...)` 默认值) */
    fun columnSql(schema: String?, table: String): Sql = Sql(
        text = buildString {
            append("SELECT column_name, is_identity, column_default")
            append(" FROM information_schema.columns")
            append(" WHERE table_name = ?")
            if (!schema.isNullOrBlank()) append(" AND table_schema = ?")
        },
        params = listOfNotNull(table, schema?.takeIf { it.isNotBlank() }),
    )

    /** MySQL 的自增标记在 `extra` 列;其他数据库没有该列,查了会直接报错 */
    fun mysqlAutoSql(schema: String?, table: String): Sql = Sql(
        text = buildString {
            append("SELECT column_name FROM information_schema.columns")
            append(" WHERE extra LIKE '%auto_increment%'")
            append(" AND table_name = ?")
            if (!schema.isNullOrBlank()) append(" AND table_schema = ?")
        },
        params = listOfNotNull(table, schema?.takeIf { it.isNotBlank() }),
    )

    /** 是否 MySQL 系(只有它需要查 `extra`) */
    fun isMysql(productName: String?): Boolean =
        productName?.lowercase()?.let { "mysql" in it || "mariadb" in it } == true

    /**
     * 按约束名分组,只保留**单列**约束。
     *
     * 多列联合唯一(如 `UNIQUE(a, b)`)不能按单列处理:单独一列可以有重复值,标成唯一列会给出
     * 误导性的自动规则(实际约束由多列共同满足)。
     */
    fun fromConstraints(rows: List<ConstraintRow>): Structure {
        val byName = LinkedHashMap<String, MutableList<String>>()
        val typeByName = LinkedHashMap<String, String>()
        rows.forEach { row ->
            if (row.column.isBlank() || row.name.isBlank()) return@forEach
            byName.getOrPut(row.name) { ArrayList() }.add(row.column)
            typeByName[row.name] = row.type.uppercase()
        }

        val pk = LinkedHashSet<String>()
        val unique = LinkedHashSet<String>()
        byName.forEach { (name, columns) ->
            if (columns.size != 1) return@forEach
            val type = typeByName[name].orEmpty()
            when {
                "PRIMARY" in type -> pk.add(columns[0])
                "UNIQUE" in type -> unique.add(columns[0])
            }
        }
        return Structure(primaryKeys = pk, uniqueColumns = unique)
    }

    /** 该列是否由数据库自动生成 */
    fun isAuto(row: ColumnRow): Boolean =
        row.isIdentity ||
            row.columnDefault?.contains("nextval(", ignoreCase = true) == true ||
            row.extra?.contains("auto_increment", ignoreCase = true) == true

    fun fromColumns(rows: List<ColumnRow>): Structure =
        Structure(autoColumns = rows.filter { isAuto(it) }.map { it.name }.toSet())

    /**
     * SQL 字符串字面量:单引号翻倍。
     *
     * 仅用于构造**展示用**文本或测试断言。查询语句一律用 [Sql] 的绑定参数,
     * 不要再拿它拼 SQL:单引号翻倍无法覆盖所有数据库的转义规则
     * (MySQL 默认把 `\` 当转义字符,`\'` 可以逃出字符串)。
     */
    fun literal(value: String): String = "'" + value.replace("'", "''") + "'"
}
