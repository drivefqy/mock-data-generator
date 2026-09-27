package com.github.mockdatagen.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ParamKind { TEXT, MULTILINE, INT, DECIMAL, DATE, TIME, DATETIME, CHOICE }

/**
 * 下拉选项。
 *
 * [code] 是**参数值**,写进参数串并交给生成引擎,永不翻译;
 * [label] 是给人看的,按当前语言求值。
 */
data class ParamChoice(val code: String, val label: String)

data class ParamField(
    val label: String,
    val kind: ParamKind,
    val hint: String = "",
    val choices: List<ParamChoice> = emptyList(),
    /** 该字段可否留空(必须显式声明:不要从 [label] 文案里判断,文案随语言切换) */
    val optional: Boolean = false,
)

/**
 * 规则参数的结构化描述:把 [ParamFormat] 翻译成"若干输入控件 + 解析/序列化/摘要/校验"。
 * 参数在磁盘/内存里仍然是简单字符串,保证生成引擎与既有规则完全兼容。
 *
 * 所有给人看的文案都走 [I18n.t];choices 用 getter 实现,保证语言切换后拿到新 label。
 */
object RuleParamSpec {

    val textCharsets: List<ParamChoice>
        get() = listOf(
            ParamChoice("cn", I18n.t("中英文", "Chinese + English")),
            ParamChoice("en", I18n.t("仅英文", "English only")),
            ParamChoice("num", I18n.t("仅数字", "Digits only")),
            ParamChoice("en_num", I18n.t("英文+数字", "English + digits")),
        )

    val passwordCharsets: List<ParamChoice>
        get() = listOf(
            ParamChoice("num", I18n.t("仅数字", "Digits only")),
            ParamChoice("en", I18n.t("仅字母", "Letters only")),
            ParamChoice("en_num", I18n.t("字母+数字", "Letters + digits")),
            ParamChoice("all", I18n.t("字母+数字+符号", "Letters + digits + symbols")),
        )

    val cityChoices: List<ParamChoice>
        get() = listOf(
            ParamChoice("带省份", I18n.t("带省份(如 浙江省杭州市)", "With province")),
            ParamChoice("仅城市", I18n.t("仅城市(如 杭州市)", "City only")),
        )

    val provinceChoices: List<ParamChoice>
        get() = listOf(
            ParamChoice("完整名称", I18n.t("完整名称(如 浙江省)", "Full name")),
            ParamChoice("简称", I18n.t("简称(如 浙江)", "Short form")),
        )

    val districtChoices: List<ParamChoice>
        get() = listOf(
            ParamChoice("仅区县", I18n.t("仅区县(如 西湖区)", "District only")),
            ParamChoice("带城市", I18n.t("带城市(如 杭州市西湖区)", "With city")),
        )

    val addressChoices: List<ParamChoice>
        get() = listOf(
            ParamChoice("完整地址", I18n.t("完整地址(省市区 + 街道 + 门牌)", "Full (region + street + number)")),
            ParamChoice("省市区", I18n.t("仅省市区", "Region only")),
            ParamChoice("街道门牌", I18n.t("仅街道 + 门牌", "Street + number only")),
        )

    val productLevelChoices: List<ParamChoice>
        get() = listOf(
            ParamChoice("一级类目", I18n.t("一级类目(如 家电类)", "Level 1")),
            ParamChoice("二级类目", I18n.t("二级类目(如 厨房电器)", "Level 2")),
            ParamChoice("完整路径", I18n.t("完整路径(如 家电类-厨房电器)", "Full path")),
        )

    val brandPrefixChoices: List<ParamChoice>
        get() = listOf(
            ParamChoice("不带品牌", I18n.t("只出产品名(如 变频空调)", "Product name only")),
            ParamChoice("带品牌", I18n.t("品牌 + 产品名(如 美的变频空调)", "Brand + product name")),
        )

    /** 与 [parse] 返回的列表一一对应 */
    fun fields(type: RuleType): List<ParamField> = when (type.format) {
        ParamFormat.NONE -> emptyList()
        ParamFormat.RAW -> listOf(
            ParamField(
                if (type.nullableParam) I18n.t("值 / 前缀(可留空)", "Value / prefix (optional)") else I18n.t("值", "Value"),
                ParamKind.TEXT,
                type.paramHint,
                optional = type.nullableParam,
            ),
        )

        ParamFormat.CHOICE -> listOf(
            ParamField(I18n.t("选项", "Option"), ParamKind.CHOICE, type.paramHint, choicesFor(type)),
        )

        ParamFormat.CHOICE_PAIR -> pairFields(type)
        ParamFormat.TEXT_LIST -> listOf(
            ParamField(
                I18n.t("候选值(每行一个)", "Candidates (one per line)"),
                ParamKind.MULTILINE,
                I18n.t("支持从 Excel / 文本直接粘贴多行", "Paste multiple lines from Excel or text"),
            ),
        )

        ParamFormat.INT_PAIR -> listOf(
            ParamField(I18n.t("最小值", "Min"), ParamKind.INT, I18n.t("含边界", "Inclusive")),
            ParamField(I18n.t("最大值", "Max"), ParamKind.INT, I18n.t("含边界", "Inclusive")),
        )

        ParamFormat.DECIMAL_RANGE -> listOf(
            ParamField(I18n.t("最小值", "Min"), ParamKind.DECIMAL),
            ParamField(I18n.t("最大值", "Max"), ParamKind.DECIMAL),
            ParamField(I18n.t("小数位", "Scale"), ParamKind.INT, "0-10"),
        )

        ParamFormat.LENGTH_RANGE -> listOf(
            ParamField(I18n.t("最小长度", "Min length"), ParamKind.INT, I18n.t("按字符数", "By character count")),
            ParamField(I18n.t("最大长度", "Max length"), ParamKind.INT),
            ParamField(I18n.t("字符集", "Charset"), ParamKind.CHOICE, "", textCharsets),
        )

        ParamFormat.START_STEP -> listOf(
            ParamField(
                if (type == RuleType.AUTO_INCREMENT) {
                    I18n.t("起始值(整数 / auto / 可留空)", "Start (integer / auto / optional)")
                } else {
                    I18n.t("起始值", "Start")
                },
                ParamKind.TEXT,
                if (type == RuleType.AUTO_INCREMENT) {
                    I18n.t(
                        "auto 或留空 = 自动接续库中该列最大值 + 1(主键、唯一列推荐)",
                        "auto or empty = continue from max + 1 (recommended for keys)",
                    )
                } else {
                    ""
                },
                optional = type == RuleType.AUTO_INCREMENT,
            ),
            ParamField(I18n.t("步长", "Step"), ParamKind.INT, I18n.t("每行递增多少", "Increment per row")),
        )

        ParamFormat.SERIAL_NO -> listOf(
            ParamField(I18n.t("前缀(可留空)", "Prefix (optional)"), ParamKind.TEXT, I18n.t("数字列请留空", "Leave empty for numeric columns"), optional = true),
            ParamField(I18n.t("起始值", "Start"), ParamKind.INT),
            ParamField(I18n.t("步长", "Step"), ParamKind.INT),
            ParamField(I18n.t("补零位数", "Zero padding"), ParamKind.INT, I18n.t("如 6 → 000001", "e.g. 6 → 000001")),
        )

        ParamFormat.DATE_RANGE -> listOf(
            ParamField(I18n.t("开始日期", "Start date"), ParamKind.DATE, "yyyy-MM-dd"),
            ParamField(I18n.t("结束日期", "End date"), ParamKind.DATE, "yyyy-MM-dd"),
        )

        ParamFormat.TIME_RANGE -> listOf(
            ParamField(I18n.t("开始时间", "Start time"), ParamKind.TIME, I18n.t("HH:mm 或 HH:mm:ss", "HH:mm or HH:mm:ss")),
            ParamField(I18n.t("结束时间", "End time"), ParamKind.TIME),
        )

        ParamFormat.DATETIME_RANGE -> listOf(
            ParamField(I18n.t("开始时间", "Start time"), ParamKind.DATETIME, "yyyy-MM-dd HH:mm:ss"),
            ParamField(I18n.t("结束时间", "End time"), ParamKind.DATETIME, "yyyy-MM-dd HH:mm:ss"),
        )

        ParamFormat.DATETIME_STEP -> listOf(
            ParamField(I18n.t("起始时间", "Start time"), ParamKind.DATETIME, "yyyy-MM-dd HH:mm:ss"),
            ParamField(I18n.t("步长(分钟)", "Step (minutes)"), ParamKind.INT, I18n.t("每行递增的分钟数", "Minutes added per row")),
        )

        ParamFormat.DAY_OFFSET -> listOf(
            ParamField(
                I18n.t("偏移天数", "Offset days"),
                ParamKind.INT,
                I18n.t("0 = 当前时间,-7 = 七天前,30 = 三十天后", "0 = now, -7 = 7 days ago, 30 = 30 days later"),
            ),
        )

        ParamFormat.INT_CHOICE -> listOf(
            ParamField(I18n.t("长度", "Length"), ParamKind.INT),
            ParamField(I18n.t("字符集", "Charset"), ParamKind.CHOICE, "", choicesFor(type)),
        )
    }

    private fun choicesFor(type: RuleType): List<ParamChoice> = when (type) {
        RuleType.PASSWORD -> passwordCharsets
        RuleType.CITY -> cityChoices
        RuleType.PROVINCE -> provinceChoices
        RuleType.DISTRICT -> districtChoices
        RuleType.ADDRESS -> addressChoices
        RuleType.RANDOM_TEXT -> textCharsets
        RuleType.BRAND -> MockDictionaries.productKindChoices
        else -> emptyList()
    }

    /** 双下拉规则的字段定义(顺序与参数里的逗号分段一一对应) */
    private fun pairFields(type: RuleType): List<ParamField> = when (type) {
        RuleType.PRODUCT_NAME -> listOf(
            ParamField(I18n.t("品类", "Category"), ParamKind.CHOICE, I18n.t("产品所属大类", "Product category"), MockDictionaries.productKindChoices),
            ParamField(I18n.t("品牌前缀", "Brand prefix"), ParamKind.CHOICE, "", brandPrefixChoices),
        )

        RuleType.PRODUCT_CATEGORY -> listOf(
            ParamField(I18n.t("类目层级", "Category level"), ParamKind.CHOICE, I18n.t("返回一级 / 二级 / 完整路径", "Level 1 / Level 2 / full path"), productLevelChoices),
            ParamField(I18n.t("限定品类", "Category filter"), ParamKind.CHOICE, "", MockDictionaries.productKindChoices),
        )

        else -> emptyList()
    }

    fun parse(type: RuleType, param: String): List<String> = when (type.format) {
        ParamFormat.NONE -> emptyList()
        ParamFormat.RAW, ParamFormat.CHOICE -> listOf(param.trim())
        ParamFormat.TEXT_LIST -> listOf(param.split('|').joinToString("\n") { it.trim() })
        else -> {
            val n = fields(type).size
            val parts = param.split(',').map { it.trim() }
            List(n) { parts.getOrNull(it) ?: "" }
        }
    }

    fun compose(type: RuleType, values: List<String>): String = when (type.format) {
        ParamFormat.NONE -> ""
        ParamFormat.RAW, ParamFormat.CHOICE -> values.firstOrNull()?.trim() ?: ""
        ParamFormat.TEXT_LIST -> values.firstOrNull().orEmpty()
            .split('\n', '\r', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("|")

        else -> {
            val parts = values.map { it.trim() }.toMutableList()
            var last = parts.size - 1
            while (last > 0 && parts[last].isEmpty()) last--
            parts.subList(0, last + 1).joinToString(",")
        }
    }

    /** 表格「参数」列展示用的摘要 */
    fun summary(type: RuleType, param: String): String {
        val v = parse(type, param)
        fun at(i: Int) = v.getOrNull(i).orEmpty()
        fun n(i: Int) = at(i).toDoubleOrNull()
        return when (type.format) {
            ParamFormat.NONE -> ""
            ParamFormat.RAW -> at(0).ifBlank { "—" }.let { if (it.length > 48) it.take(45) + "…" else it }
            ParamFormat.CHOICE -> choicesFor(type).firstOrNull { it.code == at(0) }?.label ?: at(0).ifBlank { "—" }
            ParamFormat.CHOICE_PAIR -> listOf(at(0), at(1)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }

            ParamFormat.TEXT_LIST -> {
                val list = param.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (list.isEmpty()) {
                    "—"
                } else {
                    I18n.t("枚举", "Enum") + "[${list.size}]: " +
                        list.take(3).joinToString(" / ").let { if (it.length > 44) it.take(42) + "…" else it } +
                        if (list.size > 3) " …" else ""
                }
            }

            ParamFormat.INT_PAIR, ParamFormat.DECIMAL_RANGE ->
                "${trimNum(at(0))} ~ ${trimNum(at(1))}" +
                    if (type.format == ParamFormat.DECIMAL_RANGE) {
                        I18n.t("(${at(2).ifBlank { "2" }} 位小数)", "(${at(2).ifBlank { "2" }} decimals)")
                    } else {
                        ""
                    }

            ParamFormat.LENGTH_RANGE ->
                I18n.t("长度 ${at(0)}~${at(1)}", "Length ${at(0)}~${at(1)}") + " · " + charsetLabel(textCharsets, at(2))

            ParamFormat.START_STEP ->
                (if (isAutoStartValue(at(0))) I18n.t("起始 接续库中最大值+1", "Start after max + 1") else I18n.t("起始 ${at(0)}", "Start ${at(0)}")) +
                    I18n.t(" · 步长 ${at(1).ifBlank { "1" }}", " · Step ${at(1).ifBlank { "1" }}")

            ParamFormat.SERIAL_NO -> I18n.t(
                "前缀「${at(0)}」 ${at(1)} 起 · 步长 ${at(2).ifBlank { "1" }} · ${at(3)} 位",
                "Prefix \"${at(0)}\" ${at(1)} · Step ${at(2).ifBlank { "1" }} · ${at(3)} digits",
            )

            ParamFormat.DATE_RANGE, ParamFormat.DATETIME_RANGE, ParamFormat.TIME_RANGE -> "${at(0)} ~ ${at(1)}"

            ParamFormat.DATETIME_STEP ->
                I18n.t("${at(0)} 起 · 每行 +${at(1)} 分钟", "From ${at(0)} · +${at(1)} min/row")

            ParamFormat.DAY_OFFSET ->
                if (at(0).isBlank() || at(0) == "0") {
                    I18n.t("当前时间", "Now")
                } else {
                    I18n.t(
                        "当前时间 ${if (at(0).startsWith("-")) at(0) else "+" + at(0)} 天",
                        "Now ${if (at(0).startsWith("-")) at(0) else "+" + at(0)} days",
                    )
                }

            ParamFormat.INT_CHOICE ->
                I18n.t("长度 ${at(0)}", "Length ${at(0)}") + " · " + charsetLabel(passwordCharsets, at(1))
        }
    }

    private fun charsetLabel(choices: List<ParamChoice>, code: String): String =
        choices.firstOrNull { it.code == code }?.label ?: choices.first().label

    private fun trimNum(s: String): String =
        s.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: s

    /** 日期/时间范围快捷预设:名称 -> 参数 */
    fun presets(type: RuleType): List<Pair<String, String>> {
        val today = LocalDate.now()
        val fm = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val fmd = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // LocalDate 不能直接按日期时间格式输出,必须先转成当日零点的 LocalDateTime
        fun rangeOf(start: LocalDate, end: LocalDate) = Pair(
            "${start.format(fm)},${end.format(fm)}",
            "${start.atStartOfDay().format(fmd)},${end.plusDays(1).atStartOfDay().format(fmd)}",
        )
        return when (type.format) {
            ParamFormat.DATE_RANGE -> listOf(
                I18n.t("近 7 天", "Last 7 days") to rangeOf(today.minusDays(7), today).first,
                I18n.t("近 30 天", "Last 30 days") to rangeOf(today.minusDays(30), today).first,
                I18n.t("近 1 年", "Last 1 year") to rangeOf(today.minusYears(1), today).first,
                I18n.t("今年", "This year") to rangeOf(today.withDayOfYear(1), today).first,
            )

            ParamFormat.DATETIME_RANGE -> listOf(
                I18n.t("近 7 天", "Last 7 days") to rangeOf(today.minusDays(7), today).second,
                I18n.t("近 30 天", "Last 30 days") to rangeOf(today.minusDays(30), today).second,
                I18n.t("近 1 年", "Last 1 year") to rangeOf(today.minusYears(1), today).second,
                I18n.t("今年", "This year") to rangeOf(today.withDayOfYear(1), today).second,
            )

            ParamFormat.DATETIME_STEP -> listOf(
                I18n.t("从今天开始 · 每小时", "From today · hourly") to
                    "${LocalDateTime.now().withNano(0).withMinute(0).withSecond(0).format(fmd)},60",
                I18n.t("从今天开始 · 每天", "From today · daily") to
                    "${LocalDateTime.now().withNano(0).withMinute(0).withSecond(0).format(fmd)},1440",
                I18n.t("从 2024-01-01 · 每分钟", "From 2024-01-01 · every minute") to "2024-01-01 00:00:00,1",
            )

            else -> emptyList()
        }
    }

    /** 返回错误信息,合法返回 null */
    fun validate(type: RuleType, values: List<String>): String? {
        val f = fields(type)
        if (f.isEmpty()) return null
        f.forEachIndexed { i, field ->
            val raw = values.getOrNull(i)?.trim().orEmpty()
            if (raw.isEmpty()) {
                if (!field.optional) return I18n.t("「${field.label}」不能为空", "\"${field.label}\" cannot be empty")
                return@forEachIndexed
            }
            when (field.kind) {
                ParamKind.INT -> if (raw.toLongOrNull() == null) {
                    return I18n.t("「${field.label}」必须是整数(当前:$raw)", "\"${field.label}\" must be an integer (current: $raw)")
                }

                ParamKind.DECIMAL -> if (raw.toDoubleOrNull() == null) {
                    return I18n.t("「${field.label}」必须是数字(当前:$raw)", "\"${field.label}\" must be a number (current: $raw)")
                }

                ParamKind.DATE -> if (parseDate(raw) == null) {
                    return I18n.t("「${field.label}」日期格式应为 yyyy-MM-dd(当前:$raw)", "\"${field.label}\" must be yyyy-MM-dd (current: $raw)")
                }

                ParamKind.DATETIME -> if (parseDateTime(raw) == null) {
                    return I18n.t(
                        "「${field.label}」格式应为 yyyy-MM-dd HH:mm:ss(当前:$raw)",
                        "\"${field.label}\" must be yyyy-MM-dd HH:mm:ss (current: $raw)",
                    )
                }

                ParamKind.TIME -> if (parseClock(raw) == null) {
                    return I18n.t(
                        "「${field.label}」格式应为 HH:mm 或 HH:mm:ss(当前:$raw)",
                        "\"${field.label}\" must be HH:mm or HH:mm:ss (current: $raw)",
                    )
                }

                else -> Unit
            }
        }
        val isRange = type.format in listOf(
            ParamFormat.INT_PAIR, ParamFormat.DECIMAL_RANGE, ParamFormat.LENGTH_RANGE,
            ParamFormat.DATE_RANGE, ParamFormat.TIME_RANGE, ParamFormat.DATETIME_RANGE,
        )
        if (isRange) {
            val a = values.getOrNull(0)?.trim().orEmpty()
            val b = values.getOrNull(1)?.trim().orEmpty()
            if (a.isNotEmpty() && b.isNotEmpty()) {
                val bad = when (type.format) {
                    ParamFormat.DATE_RANGE -> compare(parseDate(a), parseDate(b))
                    ParamFormat.DATETIME_RANGE -> compare(parseDateTime(a), parseDateTime(b))
                    ParamFormat.TIME_RANGE -> compare(parseClock(a), parseClock(b))
                    ParamFormat.DECIMAL_RANGE -> compare(a.toDoubleOrNull(), b.toDoubleOrNull())
                    else -> compare(a.toLongOrNull(), b.toLongOrNull())
                }
                if (bad) return I18n.t("结束值不能小于开始值($a > $b)", "End must not be less than start ($a > $b)")
            }
        }
        return null
    }

    private fun compare(a: Any?, b: Any?): Boolean {
        val x = toNumber(a) ?: return false
        val y = toNumber(b) ?: return false
        return x > y
    }

    private fun toNumber(v: Any?): Double? = when (v) {
        is LocalDate -> v.toEpochDay().toDouble()
        is LocalDateTime -> v.toEpochSecond(java.time.ZoneOffset.UTC).toDouble()
        is Number -> v.toDouble()
        else -> null
    }

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim().take(10), DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: Exception) {
        null
    }

    private fun parseDateTime(s: String): LocalDateTime? = try {
        val t = s.trim()
        if (t.length <= 10) LocalDate.parse(t.take(10)).atStartOfDay()
        else LocalDateTime.parse(
            t.take(19).replace(' ', 'T'),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )
    } catch (_: Exception) {
        null
    }

    private fun parseClock(s: String): Int? {
        val parts = s.trim().split(':')
        if (parts.size < 2 || parts.size > 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val sec = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59 || sec !in 0..59) return null
        return h * 3600 + m * 60 + sec
    }
}
