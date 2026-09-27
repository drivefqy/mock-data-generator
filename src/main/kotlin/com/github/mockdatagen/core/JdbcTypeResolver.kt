package com.github.mockdatagen.core

import java.sql.Types

/**
 * JDBC 目标类型归一化。
 *
 * 踩过的坑:DataGrip 内省模型 `DasType.jdbcType` 对一部分数据库类型(如 jsonb、用户自定义类型)
 * 返回 0,也就是 `java.sql.Types.NULL`。把 0 原样当作
 * `PreparedStatement.setObject(i, value, targetType)` 的 targetType 交给驱动会被直接拒绝:
 *
 *     java.sql.SQLException: 未被支持的类型值:0
 *
 * 而且不只是 0:驱动的 targetType 都是白名单制。反编译 PostgreSQL 42.7.13 可见
 * `PgPreparedStatement.setObject` 的 lookupswitch 只有 27 个常量,连标准 JDBC 类型
 * `NVARCHAR(-9)`、`NCHAR(-15)`、`LONGNVARCHAR(-16)`、`NCLOB(2011)`、
 * `TIME_WITH_TIMEZONE(2013)` 都会落到 default 分支抛异常。
 *
 * 因此绑定前必须归一化:
 *  1. 驱动支持 → 原样使用(必要时换成语义等价的受支持类型);
 *  2. 不支持或为 0 → 按数据库类型名补全;
 *  3. 仍补不出来 → [Types.OTHER],驱动会按「无类型参数」绑定,由数据库自行推断目标列类型。
 */
object JdbcTypeResolver {

    /**
     * `setObject(i, value, targetType)` 可安全使用的目标类型。
     * 取 PostgreSQL 42.7 lookupswitch 的常量集(其余类型一律走 OTHER 或两参重载)。
     */
    private val BINDABLE_TARGETS = setOf(
        Types.BIT, Types.TINYINT, Types.BIGINT,
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
        Types.CHAR, Types.VARCHAR,
        Types.NUMERIC, Types.DECIMAL,
        Types.INTEGER, Types.SMALLINT,
        Types.FLOAT, Types.REAL, Types.DOUBLE,
        Types.BOOLEAN, Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE,
        Types.OTHER, Types.BLOB, Types.CLOB, Types.SQLXML,
    )

    /** 数据库类型名 → JDBC 类型(仅覆盖能确定语义的标准类型,其余交给 OTHER) */
    private val BY_NAME: Map<String, Int> = mapOf(
        // 字符
        "char" to Types.CHAR, "bpchar" to Types.CHAR, "character" to Types.CHAR,
        "varchar" to Types.VARCHAR, "varchar2" to Types.VARCHAR, "character varying" to Types.VARCHAR,
        "citext" to Types.VARCHAR, "name" to Types.VARCHAR,
        "text" to Types.VARCHAR, "ntext" to Types.VARCHAR, "tinytext" to Types.VARCHAR,
        "mediumtext" to Types.VARCHAR, "longtext" to Types.VARCHAR,
        "clob" to Types.CLOB, "nclob" to Types.CLOB,
        // 整数
        "tinyint" to Types.TINYINT, "int1" to Types.TINYINT,
        "smallint" to Types.SMALLINT, "int2" to Types.SMALLINT,
        "smallserial" to Types.SMALLINT, "serial2" to Types.SMALLINT,
        "int" to Types.INTEGER, "integer" to Types.INTEGER, "int4" to Types.INTEGER,
        "mediumint" to Types.INTEGER, "serial" to Types.INTEGER, "serial4" to Types.INTEGER,
        "year" to Types.INTEGER,
        "bigint" to Types.BIGINT, "int8" to Types.BIGINT,
        "bigserial" to Types.BIGINT, "serial8" to Types.BIGINT, "oid" to Types.BIGINT,
        // 小数
        "real" to Types.REAL, "float4" to Types.REAL,
        "float" to Types.DOUBLE, "float8" to Types.DOUBLE,
        "double" to Types.DOUBLE, "double precision" to Types.DOUBLE,
        "numeric" to Types.DECIMAL, "decimal" to Types.DECIMAL,
        "dec" to Types.DECIMAL, "number" to Types.DECIMAL,
        // 布尔
        "bool" to Types.BOOLEAN, "boolean" to Types.BOOLEAN,
        // 时间
        "date" to Types.DATE,
        "time" to Types.TIME, "timetz" to Types.TIME, "time without time zone" to Types.TIME,
        "time with time zone" to Types.TIME,
        "timestamp" to Types.TIMESTAMP, "timestamptz" to Types.TIMESTAMP,
        "datetime" to Types.TIMESTAMP, "datetime2" to Types.TIMESTAMP,
        "smalldatetime" to Types.TIMESTAMP,
        "timestamp without time zone" to Types.TIMESTAMP, "timestamp with time zone" to Types.TIMESTAMP,
        // 位
        "bit" to Types.BIT, "varbit" to Types.BIT,

        // 数据库专有类型:明确落到 OTHER,让驱动按「无类型参数」绑定、由数据库自行推断列类型。
        // jsonb 这类列强转成 VARCHAR 参数反而可能被数据库拒绝,不要改成字符串类型。
        "json" to Types.OTHER, "jsonb" to Types.OTHER, "xml" to Types.OTHER,
        "uuid" to Types.OTHER, "uniqueidentifier" to Types.OTHER,
        "interval" to Types.OTHER,
        "inet" to Types.OTHER, "cidr" to Types.OTHER, "macaddr" to Types.OTHER,
        "bytea" to Types.OTHER, "blob" to Types.OTHER, "longblob" to Types.OTHER,
        "mediumblob" to Types.OTHER, "tinyblob" to Types.OTHER,
        "binary" to Types.OTHER, "varbinary" to Types.OTHER,
        "image" to Types.OTHER, "raw" to Types.OTHER,
        "money" to Types.OTHER, "smallmoney" to Types.OTHER,
        "enum" to Types.OTHER, "set" to Types.OTHER,
        "tsvector" to Types.OTHER, "tsquery" to Types.OTHER,
        "hstore" to Types.OTHER, "ltree" to Types.OTHER,
    )

    /**
     * 把驱动不认识、但语义明确的类型换成等价的受支持类型。
     * 映射后仍是 [Types.OTHER] 的表示「无法确定」,由调用方继续按类型名补全。
     */
    private fun normalize(jdbcType: Int): Int = when (jdbcType) {
        Types.NCHAR -> Types.CHAR
        Types.NVARCHAR, Types.LONGNVARCHAR -> Types.VARCHAR
        Types.NCLOB -> Types.CLOB
        Types.TIME_WITH_TIMEZONE -> Types.TIME
        Types.NULL -> Types.OTHER
        else -> jdbcType
    }

    /**
     * 该类型能否原样作为 `setObject(i, value, targetType)` 的 targetType。
     * 注意这里是严格判断,不做归一化:[resolve] 的返回值才应该用它来判定。
     */
    fun isBindableTarget(jdbcType: Int) = jdbcType in BINDABLE_TARGETS

    /**
     * 得到可用于参数绑定的 JDBC 类型。幂等:对已归一化的值再调用结果不变。
     * @param jdbcType 驱动/内省模型给出的原始类型,可能为 0
     * @param typeName 数据库类型名(如 `jsonb`、`numeric`、`varchar2`)
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        jdbcType: Int,
        typeName: String? = null,
        columnSize: Int = 0,
        decimalDigits: Int = 0,
    ): Int {
        val normalized = normalize(jdbcType)
        if (normalized != Types.OTHER && normalized in BINDABLE_TARGETS) return normalized

        val name = normalizeName(typeName)
        if (name != null) {
            BY_NAME[name]?.let { return it }
            // 数组类型不能按标量处理(否则 int4[] 会被下面的 "int" 关键字命中成 BIGINT),
            // 交给数据库按无类型参数推断
            if (name.endsWith("[]") || "array" in name) return Types.OTHER
        }

        // 名字也不认识:按关键字兜底,再不行交给数据库推断
        return when {
            name == null -> Types.OTHER
            NUMERIC_HINTS.any { it in name } -> Types.DECIMAL
            INT_HINTS.any { it in name } -> Types.BIGINT
            BOOL_HINTS.any { it in name } -> Types.BOOLEAN
            DATE_HINTS.any { it in name } -> Types.TIMESTAMP
            TEXT_HINTS.any { it in name } -> Types.VARCHAR
            else -> Types.OTHER
        }
    }

    private val NUMERIC_HINTS = listOf("numeric", "decimal", "real", "float", "double")
    private val INT_HINTS = listOf("int", "serial")
    private val BOOL_HINTS = listOf("bool")
    private val DATE_HINTS = listOf("date", "time")
    private val TEXT_HINTS = listOf("char", "text", "string", "clob", "enum")

    /** 去参数/去空格的类型名:`character varying(64)`、`NUMERIC (10, 2)` → 规范化串 */
    private fun normalizeName(raw: String?): String? {
        val cleaned = raw
            ?.substringBefore('(')
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() && it != "?" }
        return cleaned
    }
}
