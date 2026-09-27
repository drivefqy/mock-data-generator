package com.github.mockdatagen.core

import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 从 JDBC 元数据 / DataGrip 内省模型读取的表列信息 */
data class ColumnMeta(
    val name: String,
    val jdbcType: Int,
    val typeName: String,
    val nullable: Boolean,
    val autoIncrement: Boolean,
    val columnSize: Int,
    val decimalDigits: Int,
    /** 主键列(单列主键;复合主键的每一列都会标记) */
    val isPrimaryKey: Boolean = false,
    /** 单列唯一约束列(不含主键) */
    val isUnique: Boolean = false,
)

/** 表引用(schema + 表名 + 所属数据库) */
data class TableRef(val schema: String?, val name: String, val catalog: String? = null) {
    val displayName: String = if (schema.isNullOrBlank()) name else "$schema.$name"

    /** 含库名的完整标识:同一数据源内省多个库时,同名表靠它区分 */
    val fullName: String = if (catalog.isNullOrBlank()) displayName else "$catalog.$displayName"
}

/** 单列的生成配置 */
data class RuleConfig(
    val column: ColumnMeta,
    var enabled: Boolean,
    var type: RuleType,
    var param: String,
    var nullRatio: Int, // 0-100,按概率生成 NULL
)

/** 规则参数/值不合法时抛出 */
class MockRuleException(message: String) : Exception(message)

/** 根据列名与 JDBC 类型推断默认规则与默认参数 */
object RuleSuggester {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun suggest(col: ColumnMeta): RuleType {
        // 结构性事实优先于列名:自增列交给数据库,唯一列(主键/唯一约束)必须生成唯一值。
        // 主键若走「随机整数」,值域只有 1~100,插入必然撞库中已有数据 —— 改成自增序列,
        // 起始值在插入前按真实连接解析为"库中最大值 + 1"。
        if (col.autoIncrement) return RuleType.SKIP
        if (PrimaryKeys.mustBeUnique(col) && isIntegerType(col)) return RuleType.AUTO_INCREMENT

        val n = col.name.lowercase().replace('-', '_')
        fun has(vararg keys: String) = keys.any { it in n }
        // 名称类规则都是文字,数字列(如 brand_id / city_id)不参与,交给类型兜底
        val textish = !isNumeric(col)
        return when {
            // 联系方式(先匹配具体前缀,避免被下面的通用规则抢走)
            has("email", "mail", "邮箱") -> RuleType.EMAIL
            has("phone", "mobile", "tel", "手机", "电话", "联系方式") -> RuleType.PHONE
            has("wechat", "weixin", "微信") -> RuleType.WECHAT
            n == "qq" || n.endsWith("_qq") || n.startsWith("qq_") -> RuleType.QQ
            n == "ip" || n.endsWith("_ip") || n.startsWith("ip_") || n.contains("ip_addr") || n.contains("ipaddress") -> RuleType.IP
            n == "mac" || n.endsWith("_mac") || n.startsWith("mac_") || n.contains("mac_addr") -> RuleType.MAC
            has("url", "link", "site", "网址") -> RuleType.URL

            // 身份/凭证
            has("idcard", "id_card", "identity_no", "identity", "身份证") -> RuleType.ID_CARD
            has("password", "passwd", "pwd", "密码") -> RuleType.PASSWORD
            has("username", "user_name", "login_name", "login", "account", "账号", "用户名") -> RuleType.USERNAME
            has("uuid", "guid") -> RuleType.UUID
            has("bank_card", "bankcard", "card_no", "cardno", "银行卡") || n == "card" -> RuleType.BANK_CARD
            has("plate", "车牌") -> RuleType.PLATE_NUMBER

            // 品牌 / 产品(必须在 product 与通用 name 之前判断)
            textish && has("brand", "trademark", "品牌") -> RuleType.BRAND
            textish && has("category", "cate", "class", "类别", "类目", "品类", "分类") -> RuleType.PRODUCT_CATEGORY

            // 地理
            textish && has("province", "省份") -> RuleType.PROVINCE
            textish && has("district", "county", "区县", "行政区", "县") -> RuleType.DISTRICT
            textish && has("city", "城市") -> RuleType.CITY

            // 业务
            textish && has("company", "corp", "firm", "公司") -> RuleType.COMPANY
            textish && has("job", "position", "职位", "岗位") -> RuleType.JOB_TITLE
            textish && has("dept", "department", "部门") -> RuleType.DEPARTMENT
            textish && has("university", "college", "school", "学校", "院校") -> RuleType.UNIVERSITY
            textish && has("product", "goods", "商品", "产品") -> RuleType.PRODUCT_NAME
            has("color", "colour", "颜色") -> RuleType.COLOR_HEX

            // uuid 列(常见于主键/外键,列名不一定带 uuid)必须生成合法 UUID,否则插入失败
            isUuidType(col) -> RuleType.UUID

            // json/jsonb 列直接给合法的空对象,避免生成随机串导致插入失败
            isJson(col) -> RuleType.FIXED_VALUE

            // 地址:放在 ip/mac 之后,且要求是独立的 addr/address 词位,避免 address 类列名误判
            textish && isAddressName(n) -> RuleType.ADDRESS

            // 姓名
            has("name", "姓名", "昵称", "nick") ->
                if (isNumeric(col)) RuleType.RANDOM_INT else RuleType.CHINESE_NAME

            // 按类型兜底
            isDateType(col) -> when (col.jdbcType) {
                Types.DATE -> RuleType.DATE
                Types.TIME, Types.TIME_WITH_TIMEZONE -> RuleType.TIME
                else -> RuleType.DATETIME
            }

            isBoolType(col) -> RuleType.BOOLEAN
            isIntegerType(col) -> RuleType.RANDOM_INT
            isDecimalType(col) -> RuleType.RANDOM_DECIMAL
            else -> RuleType.RANDOM_TEXT
        }
    }

    private fun isAddressName(n: String): Boolean =
        n == "addr" || n == "address" || n.startsWith("addr") || n.startsWith("address") ||
            n.endsWith("_addr") || n.endsWith("_address") || n.endsWith("addr") || "地址" in n

    /** 按列信息细化默认参数(长度、小数位、时间范围等) */
    fun defaultParam(type: RuleType, col: ColumnMeta): String {
        val today = LocalDate.now()
        return when (type) {
            RuleType.RANDOM_DECIMAL -> "0,9999,${col.decimalDigits.coerceIn(1, 6)}"

            RuleType.RANDOM_TEXT -> {
                val size = col.columnSize
                when {
                    size in 1..10 -> "1,$size,cn"
                    size in 11..60 -> "5,$size,cn"
                    size > 60 -> "5,${size.coerceAtMost(200)},cn"
                    else -> type.defaultParam
                }
            }

            RuleType.PATTERN -> {
                val size = col.columnSize.coerceIn(4, 24)
                "[a-z0-9]{$size}"
            }

            RuleType.DATE -> "${today.minusYears(3).format(DATE_FMT)},${today.format(DATE_FMT)}"
            RuleType.DATETIME -> "${today.minusYears(1).format(DATE_FMT)} 00:00:00,${today.format(DATE_FMT)} 23:59:59"
            RuleType.SEQUENCE_DATETIME ->
                "${LocalDateTime.now().withNano(0).withMinute(0).withSecond(0).format(DATETIME_FMT)},60"

            RuleType.SERIAL_NUMBER -> {
                // 数字列不能带前缀,否则强转会失败
                val numeric = isIntegerType(col)
                val pad = col.columnSize.coerceIn(4, if (numeric) 18 else 12)
                val prefix = if (numeric) "" else "NO"
                "$prefix,1,1,$pad"
            }

            RuleType.FIXED_VALUE -> if (isJson(col)) "{}" else type.defaultParam

            RuleType.RANDOM_INT -> if (col.columnSize in 1..9 && isIntegerType(col)) "1,${"9".repeat(col.columnSize)}" else "1,100"

            // 主键 / 唯一整数列:起始值写 auto,插入时解析为"库中最大值 + 1",避免与已有数据重复
            RuleType.AUTO_INCREMENT ->
                if (PrimaryKeys.mustBeUnique(col)) "$AUTO_START,1" else type.defaultParam

            else -> type.defaultParam
        }
    }

    fun isNumeric(col: ColumnMeta) = isIntegerType(col) || isDecimalType(col)

    fun isIntegerType(col: ColumnMeta) = col.jdbcType in intArrayOf(
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
    )

    fun isDecimalType(col: ColumnMeta) = col.jdbcType in intArrayOf(
        Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC,
    )

    fun isBoolType(col: ColumnMeta) =
        col.jdbcType == Types.BOOLEAN ||
            (col.jdbcType == Types.BIT && col.columnSize <= 1)

    fun isDateType(col: ColumnMeta) = col.jdbcType in intArrayOf(
        Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE,
    )

    fun isTextType(col: ColumnMeta) = col.jdbcType in intArrayOf(
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
        Types.LONGNVARCHAR, Types.CLOB,
    )

    fun isJson(col: ColumnMeta) = col.typeName.lowercase() in setOf("json", "jsonb")

    /** uuid 类型列(PostgreSQL uuid / SQL Server uniqueidentifier) */
    fun isUuidType(col: ColumnMeta) = col.typeName.lowercase() in setOf("uuid", "uniqueidentifier")
}
