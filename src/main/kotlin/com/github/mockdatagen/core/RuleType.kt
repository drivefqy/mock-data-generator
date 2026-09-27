package com.github.mockdatagen.core

/**
 * 自增起始值的特殊写法:表示"接续数据库中该列的最大值 + 1"。
 *
 * 纯生成场景(预览、参数试跑)拿不到数据库,只能先按 1 生成;真正插入前由
 * `DatabaseAccess.insertRows` 用真实连接解析出确切起始值,否则自增主键会与库中已有数据重复。
 */
const val AUTO_START = "auto"

/** 起始值是否表示"自动接续"(留空 / auto / 自动 都算) */
fun isAutoStartValue(s: String): Boolean {
    val t = s.trim()
    return t.isEmpty() || t.equals(AUTO_START, ignoreCase = true) || t == "自动"
}

/** 规则分组,只用于下拉框分组展示与排序 */
enum class RuleGroup(private val labelZh: String, private val labelEn: String) {
    BASIC("基础", "Basic"),
    NUMBER("数字", "Number"),
    TEXT("文本", "Text"),
    IDENTITY("身份", "Identity"),
    CONTACT("联系方式", "Contact"),
    GEO("地理信息", "Geo"),
    BUSINESS("中文业务", "Business"),
    NETWORK("网络/编码", "Network"),
    TIME("时间日期", "Date & Time"),
    ADVANCED("高级", "Advanced");

    val label: String get() = I18n.t(labelZh, labelEn)
}

/**
 * 参数存储格式。决定两件事:
 * 1. 参数在 [RuleType.defaultParam] 中的默认写法(逗号分隔 / 竖线分隔 / 单值)
 * 2. 参数配置对话框生成哪些输入控件([RuleParamSpec])
 */
enum class ParamFormat {
    NONE,            // 无参数
    RAW,             // 单个原始字符串,可含逗号
    CHOICE,          // 单个选项(下拉)
    CHOICE_PAIR,     // 两个选项(下拉) 逗号分隔,如「二级类目,家电类」
    TEXT_LIST,       // 值1|值2|值3
    INT_PAIR,        // 最小值,最大值
    DECIMAL_RANGE,   // 最小值,最大值,小数位
    LENGTH_RANGE,    // 最小长度,最大长度,字符集
    START_STEP,      // 起始值,步长
    SERIAL_NO,       // 前缀,起始,步长,补零位数
    DATE_RANGE,      // 开始日期,结束日期
    TIME_RANGE,      // 开始时间,结束时间
    DATETIME_RANGE,  // 开始时间,结束时间
    DATETIME_STEP,   // 起始时间,步长(分钟)
    DAY_OFFSET,      // 偏移天数
    INT_CHOICE,      // 长度,字符集
}

/**
 * Mock 数据生成规则类型。
 *
 * 文案(i18n):[display] / [paramHint] / [group] 都是**按当前语言求值**的属性,
 * 语言切换后重新读取即可拿到新文案,不需要重建枚举。
 *
 * [defaultParam] 不翻译 —— 它是参数**值**,最终写进 SQL 的是它本身。
 *
 * @param displayZh   中文显示名
 * @param displayEn   英文显示名
 * @param group       分组
 * @param format      参数格式(决定参数编辑器)
 * @param hintZh      参数格式提示(中文)
 * @param hintEn      参数格式提示(英文)
 * @param defaultParam 默认参数
 */
enum class RuleType(
    private val displayZh: String,
    private val displayEn: String,
    val group: RuleGroup,
    val format: ParamFormat,
    private val hintZh: String = "",
    private val hintEn: String = "",
    val defaultParam: String = "",
) {
    // ---------------- 基础 ----------------
    SKIP("不生成(使用数据库默认值)", "Skip (database default)", RuleGroup.BASIC, ParamFormat.NONE),
    FIXED_VALUE(
        "固定值", "Fixed value", RuleGroup.BASIC, ParamFormat.RAW,
        "所有行都使用这个值", "Same value for every row", "测试值",
    ),
    ENUM(
        "随机枚举(候选值可配置)", "Random enum (configurable)", RuleGroup.BASIC, ParamFormat.TEXT_LIST,
        "候选值,每行一个", "Candidates, one per line", "值1|值2|值3",
    ),
    SEQUENCE_ENUM(
        "循环枚举(按行依次取)", "Cyclic enum (in order)", RuleGroup.BASIC, ParamFormat.TEXT_LIST,
        "候选值,每行一个", "Candidates, one per line", "值1|值2|值3",
    ),
    BOOLEAN("随机布尔值", "Random boolean", RuleGroup.BASIC, ParamFormat.NONE),
    UUID("UUID", "UUID", RuleGroup.BASIC, ParamFormat.NONE),

    // ---------------- 数字 ----------------
    // 默认起始值保持 1:只有主键/唯一列才会由 RuleSuggester.defaultParam 改成 auto(接续库中最大值),
    // 普通列用户主动选「自增数字」时仍然是 1,2,3…
    AUTO_INCREMENT(
        "自增数字", "Auto increment", RuleGroup.NUMBER, ParamFormat.START_STEP,
        "起始值(整数,或 auto = 接续库中最大值),步长",
        "Start (integer, or auto = continue from max), step", "1,1",
    ),
    SERIAL_NUMBER(
        "序列号(前缀+递增)", "Serial number (prefix + step)", RuleGroup.NUMBER, ParamFormat.SERIAL_NO,
        "前缀,起始,步长,补零位数", "Prefix, start, step, zero padding", "NO,1,1,6",
    ),
    RANDOM_INT(
        "随机整数", "Random integer", RuleGroup.NUMBER, ParamFormat.INT_PAIR,
        "最小值,最大值", "Min, max", "1,100",
    ),
    RANDOM_DECIMAL(
        "随机小数", "Random decimal", RuleGroup.NUMBER, ParamFormat.DECIMAL_RANGE,
        "最小值,最大值,小数位", "Min, max, scale", "0,9999,2",
    ),

    // ---------------- 文本 ----------------
    RANDOM_TEXT(
        "随机文本", "Random text", RuleGroup.TEXT, ParamFormat.LENGTH_RANGE,
        "最小长度,最大长度,字符集", "Min length, max length, charset", "5,20,cn",
    ),
    PATTERN(
        "正则模板随机串", "Regex pattern string", RuleGroup.TEXT, ParamFormat.RAW,
        "如 [a-z]{3}\\d{4}", "e.g. [a-z]{3}\\d{4}", "[a-z]{3}\\d{4}",
    ),

    // ---------------- 身份 ----------------
    CHINESE_NAME("中文姓名", "Chinese name", RuleGroup.IDENTITY, ParamFormat.NONE),
    ENGLISH_NAME("英文姓名", "English name", RuleGroup.IDENTITY, ParamFormat.NONE),
    USERNAME(
        "用户名", "Username", RuleGroup.IDENTITY, ParamFormat.RAW,
        "可选:固定前缀", "Optional: fixed prefix", "",
    ),
    PASSWORD(
        "密码", "Password", RuleGroup.IDENTITY, ParamFormat.INT_CHOICE,
        "长度,字符集", "Length, charset", "12,all",
    ),
    ID_CARD("身份证号", "ID card number", RuleGroup.IDENTITY, ParamFormat.NONE),

    // ---------------- 联系方式 ----------------
    PHONE(
        "手机号", "Mobile number", RuleGroup.CONTACT, ParamFormat.RAW,
        "可选:指定号段前缀", "Optional: number prefix", "",
    ),
    EMAIL(
        "邮箱", "Email", RuleGroup.CONTACT, ParamFormat.RAW,
        "可选:固定域名", "Optional: fixed domain", "",
    ),
    WECHAT(
        "微信号", "WeChat ID", RuleGroup.CONTACT, ParamFormat.RAW,
        "可选:固定前缀", "Optional: fixed prefix", "",
    ),
    QQ("QQ 号", "QQ number", RuleGroup.CONTACT, ParamFormat.NONE),

    // ---------------- 地理信息 ----------------
    PROVINCE(
        "省份", "Province", RuleGroup.GEO, ParamFormat.CHOICE,
        "完整名称 / 简称", "Full name / short form", "完整名称",
    ),
    CITY(
        "城市", "City", RuleGroup.GEO, ParamFormat.CHOICE,
        "带省份 / 仅城市", "With province / city only", "带省份",
    ),
    DISTRICT(
        "区县", "District", RuleGroup.GEO, ParamFormat.CHOICE,
        "仅区县 / 带城市", "District only / with city", "仅区县",
    ),
    ADDRESS(
        "中文地址", "Chinese address", RuleGroup.GEO, ParamFormat.CHOICE,
        "完整地址 / 省市区 / 街道门牌", "Full / region only / street only", "完整地址",
    ),

    // ---------------- 中文业务 ----------------
    COMPANY("公司名称", "Company name", RuleGroup.BUSINESS, ParamFormat.NONE),
    BRAND(
        "品牌名称", "Brand", RuleGroup.BUSINESS, ParamFormat.CHOICE,
        "按品类取品牌", "Brands by category", "全部",
    ),
    JOB_TITLE("职位", "Job title", RuleGroup.BUSINESS, ParamFormat.NONE),
    DEPARTMENT("部门", "Department", RuleGroup.BUSINESS, ParamFormat.NONE),
    UNIVERSITY("学校名称", "School name", RuleGroup.BUSINESS, ParamFormat.NONE),
    PRODUCT_NAME(
        "产品名称", "Product name", RuleGroup.BUSINESS, ParamFormat.CHOICE_PAIR,
        "品类 + 是否带品牌", "Category + brand prefix", "全部,不带品牌",
    ),
    PRODUCT_CATEGORY(
        "产品类别", "Product category", RuleGroup.BUSINESS, ParamFormat.CHOICE_PAIR,
        "类目层级 + 品类", "Category level + category", "二级类目,全部",
    ),

    // ---------------- 网络/编码 ----------------
    IP("IPv4 地址", "IPv4 address", RuleGroup.NETWORK, ParamFormat.NONE),
    MAC("MAC 地址", "MAC address", RuleGroup.NETWORK, ParamFormat.NONE),
    URL(
        "URL", "URL", RuleGroup.NETWORK, ParamFormat.RAW,
        "可选:固定域名", "Optional: fixed domain", "",
    ),
    BANK_CARD("银行卡号", "Bank card number", RuleGroup.NETWORK, ParamFormat.NONE),
    PLATE_NUMBER(
        "车牌号", "License plate", RuleGroup.NETWORK, ParamFormat.RAW,
        "可选:城市简称,如 京A", "Optional: city prefix, e.g. 京A", "",
    ),
    COLOR_HEX("颜色(#RRGGBB)", "Color (#RRGGBB)", RuleGroup.NETWORK, ParamFormat.NONE),

    // ---------------- 时间日期 ----------------
    DATE(
        "随机日期", "Random date", RuleGroup.TIME, ParamFormat.DATE_RANGE,
        "开始日期,结束日期", "Start date, end date", "",
    ),
    TIME(
        "随机时间", "Random time", RuleGroup.TIME, ParamFormat.TIME_RANGE,
        "开始时间,结束时间", "Start time, end time", "08:00:00,20:00:00",
    ),
    DATETIME(
        "随机日期时间", "Random datetime", RuleGroup.TIME, ParamFormat.DATETIME_RANGE,
        "开始,结束", "Start, end", "",
    ),
    SEQUENCE_DATETIME(
        "递增时间", "Sequential datetime", RuleGroup.TIME, ParamFormat.DATETIME_STEP,
        "起始时间,步长(分钟)", "Start time, step (minutes)", "2024-01-01 00:00:00,60",
    ),
    NOW(
        "当前时间(可偏移)", "Current time (offset)", RuleGroup.TIME, ParamFormat.DAY_OFFSET,
        "偏移天数,如 0 / -7 / 30", "Offset days, e.g. 0 / -7 / 30", "0",
    ),

    // ---------------- 高级 ----------------
    SQL_EXPRESSION(
        "数据库表达式(原样输出)", "SQL expression (raw)", RuleGroup.ADVANCED, ParamFormat.RAW,
        "如 now() / gen_random_uuid()", "e.g. now() / gen_random_uuid()", "now()",
    );

    /** 显示名(按当前语言) */
    val display: String get() = I18n.t(displayZh, displayEn)

    /** 参数格式提示(按当前语言) */
    val paramHint: String get() = I18n.t(hintZh, hintEn)

    /**
     * 首个参数可否留空。
     *
     * 必须显式声明,不要从 [paramHint] 文案里判断 —— 文案随语言切换,判断会静默失效。
     */
    val nullableParam: Boolean get() = this in NULLABLE_PARAM_RULES

    companion object {
        /** 首个参数可留空的规则(与 RuleParamSpec 里带"可留空"提示的字段一一对应) */
        private val NULLABLE_PARAM_RULES = setOf(
            USERNAME, PHONE, EMAIL, WECHAT, URL, PLATE_NUMBER,
        )

        /** 按显示名反查:中英文都能命中 */
        fun fromDisplay(s: String): RuleType? =
            entries.firstOrNull { it.displayZh == s || it.displayEn == s || it.display == s }

        /** 按分组排序后的全部规则,用于下拉框(声明顺序在组内保持) */
        val ordered: List<RuleType> get() = entries.sortedBy { it.group.ordinal }
    }
}
