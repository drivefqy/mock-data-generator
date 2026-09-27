import com.github.mockdatagen.core.ColumnDiff
import com.github.mockdatagen.core.ColumnMeta
import com.github.mockdatagen.core.I18n
import com.github.mockdatagen.core.InsertFailure
import com.github.mockdatagen.core.JdbcTypeResolver
import com.github.mockdatagen.core.JdbcUrlUtil
import com.github.mockdatagen.core.Lang
import com.github.mockdatagen.core.MockDataGenerator
import com.github.mockdatagen.core.MockDictionaries
import com.github.mockdatagen.core.MockRuleException
import com.github.mockdatagen.core.ParamCompat
import com.github.mockdatagen.core.PrimaryKeys
import com.github.mockdatagen.core.RawSqlLiteral
import com.github.mockdatagen.core.RuleConfig
import com.github.mockdatagen.core.RuleParamSpec
import com.github.mockdatagen.core.RuleRepairs
import com.github.mockdatagen.core.RuleSuggester
import com.github.mockdatagen.core.RuleType
import com.github.mockdatagen.core.StructureSql
import com.github.mockdatagen.core.TableRef
import com.github.mockdatagen.core.TableSearch
import com.github.mockdatagen.core.TableTarget
import com.github.mockdatagen.core.TableTargets
import java.sql.Types

private var pass = 0
private var fail = 0
private val log = StringBuilder()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
    } else {
        fail++
        log.append("FAIL: ").append(name).append("  ").append(detail).append('\n')
    }
}

private fun vcol(size: Int = 64) = ColumnMeta("c", Types.VARCHAR, "varchar", true, false, size, 0)
private fun icol() = ColumnMeta("c", Types.INTEGER, "int4", true, false, 10, 0)
private fun dcol() = ColumnMeta("c", Types.DECIMAL, "numeric", true, false, 18, 2)
private fun ts() = ColumnMeta("c", Types.TIMESTAMP, "timestamp", true, false, 29, 6)
private fun dt() = ColumnMeta("c", Types.DATE, "date", true, false, 13, 0)
private fun tm() = ColumnMeta("c", Types.TIME, "time", true, false, 15, 0)
private fun bcol() = ColumnMeta("c", Types.BOOLEAN, "bool", true, false, 1, 0)

private fun gen(type: RuleType, param: String, col: ColumnMeta, row: Long = 0): Any? =
    MockDataGenerator().generateRow(listOf(RuleConfig(col, true, type, param, 0)), row)[col.name]

private fun genOrNull(type: RuleType, param: String, col: ColumnMeta, row: Long = 0): Any? = try {
    gen(type, param, col, row)
} catch (e: Exception) {
    null
}

fun main() {
    try {
        runAllChecks()
    } catch (e: Throwable) {
        check("测试执行异常", false, "${e.javaClass.simpleName}: ${e.message}")
    }
    val summary = "通过 $pass 项,失败 $fail 项"
    java.io.File("smoke_result.txt").writeText(
        buildString {
            append(summary).append("\n\n")
            if (log.isNotEmpty()) append(log) else append("全部通过\n")
        },
    )
    println(summary)
}

private fun runAllChecks() {
    // ---------- 1. 全规则冒烟:每种规则用默认参数生成 5 行 ----------
    RuleType.entries.forEach { type ->
        if (type == RuleType.SKIP) return@forEach
        val col = if (type == RuleType.RANDOM_INT || type == RuleType.AUTO_INCREMENT) icol() else vcol(32)
        val param = RuleSuggester.defaultParam(type, col)
        try {
            val values = (0L until 5L).map { gen(type, param, col, it) }
            check("默认参数生成:${type.name}", values.all { it != null }, "param=$param values=$values")
        } catch (e: Exception) {
            check("默认参数生成:${type.name}", false, "param=$param ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    // 数字列上的序列号默认参数必须可用(无前缀 + 按列宽补零)
    val numSerialParam = RuleSuggester.defaultParam(RuleType.SERIAL_NUMBER, icol())
    check("数字列序列号默认参数可生成", (0L until 3L).all { gen(RuleType.SERIAL_NUMBER, numSerialParam, icol(), it) is Int }, numSerialParam)

    // ---------- 2. 枚举 / 循环枚举 ----------
    val enums = listOf("启用", "停用", "待审")
    val randEnum = (0 until 40).map { gen(RuleType.ENUM, enums.joinToString("|"), vcol()).toString() }
    check("随机枚举只在候选值内", randEnum.all { it in enums }, randEnum.toSet().toString())
    check("随机枚举覆盖全部候选值", randEnum.toSet().size >= 2, randEnum.toSet().toString())
    val seqEnum = (0L until 7L).map { gen(RuleType.SEQUENCE_ENUM, enums.joinToString("|"), vcol(), it).toString() }
    check("循环枚举按行轮转", seqEnum == listOf("启用", "停用", "待审", "启用", "停用", "待审", "启用"), seqEnum.toString())

    // ---------- 3. 自增 / 序列号 ----------
    check("自增数字 步长2", (0L until 3L).map { gen(RuleType.AUTO_INCREMENT, "10,2", icol(), it) } == listOf(10, 12, 14))
    check("序列号补零", (0L until 2L).map { gen(RuleType.SERIAL_NUMBER, "EMP,7,1,4", vcol(), it).toString() } == listOf("EMP0007", "EMP0008"))

    // ---------- 4. 数值区间 ----------
    val ints = (0 until 200).map { (gen(RuleType.RANDOM_INT, "5,9", icol()) as Number).toInt() }
    check("随机整数在区间内", ints.all { it in 5..9 } && ints.toSet().size > 1, ints.toSet().toString())
    val decs = (0 until 100).map { gen(RuleType.RANDOM_DECIMAL, "0,100,2", dcol()) }
    check("随机小数=BigDecimal 且 2 位", decs.all { it is java.math.BigDecimal && it.toString().substringAfter('.').length <= 2 }, decs.take(3).toString())
    check("随机小数在区间内", decs.all { (it as java.math.BigDecimal).toDouble() in 0.0..100.0 })

    // ---------- 5. 文本/正则 ----------
    val texts = (0 until 60).map { gen(RuleType.RANDOM_TEXT, "5,8,en", vcol()).toString() }
    check("随机文本长度 5-8", texts.all { it.length in 5..8 }, texts.firstOrNull { it.length !in 5..8 } ?: "")
    check("随机文本仅英文", texts.all { it.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' } })
    val nums = (0 until 30).map { gen(RuleType.RANDOM_TEXT, "3,3,num", vcol()).toString() }
    check("随机文本仅数字", nums.all { it.all { ch -> ch in '0'..'9' } })
    val cns = (0 until 30).map { gen(RuleType.RANDOM_TEXT, "2,4,cn", vcol()).toString() }
    check("随机文本中文集含中文", cns.any { it.any { ch -> ch.code > 0x4E00 } }, cns.take(3).toString())

    val phones11 = (0 until 40).map { gen(RuleType.PHONE, "", vcol(32)).toString() }
    check("手机号 11 位", phones11.all { it.length == 11 }, phones11.firstOrNull { it.length != 11 } ?: "")
    check("手机号指定号段", (0 until 10).all { gen(RuleType.PHONE, "186", vcol(32)).toString().startsWith("186") })
    check("邮箱域名可配", (0 until 10).all { gen(RuleType.EMAIL, "demo.cn", vcol(64)).toString().endsWith("@demo.cn") })
    val cityFull = (0 until 20).map { gen(RuleType.CITY, "带省份", vcol(64)).toString() }
    check("城市带省份", cityFull.all { it.endsWith("市") && it.length >= 3 }, cityFull.firstOrNull() ?: "")
    check("直辖市不重复拼接", (0 until 60).none { gen(RuleType.CITY, "带省份", vcol(64)).toString().contains("市市") })
    val phones = (0 until 50).map { gen(RuleType.PATTERN, "1[3-9]\\d{9}", vcol(32)).toString() }
    check("正则模板手机号形态", phones.all { it.length == 11 && it.startsWith("1") && it[1] in '3'..'9' }, phones.take(2).toString())
    val codes = (0 until 50).map { gen(RuleType.PATTERN, "EMP-[A-Z]{2}\\d{4}", vcol(32)).toString() }
    check("正则模板前缀固定", codes.all { it.startsWith("EMP-") && it.length == 10 }, codes.firstOrNull() ?: "")
    val alts = (0 until 60).map { gen(RuleType.PATTERN, "A|B|C", vcol()).toString() }
    check("正则分支可用", alts.all { it in listOf("A", "B", "C") } && alts.toSet().size == 3, alts.toSet().toString())
    check("正则非法模板报错", genOrNull(RuleType.PATTERN, "[a-", vcol()) == null)
    check("正则量词过大报错", genOrNull(RuleType.PATTERN, "\\d{99999}", vcol()) == null)

    // ---------- 6. 时间 ----------
    val dates = (0 until 40).map { gen(RuleType.DATE, "2020-01-01,2020-12-31", dt()).toString() }
    check("随机日期在区间且格式正确", dates.all { it.length == 10 && it.startsWith("2020-") }, dates.take(2).toString())
    val times = (0 until 40).map { gen(RuleType.TIME, "08:00:00,09:00:00", tm()).toString() }
    check("随机时间在区间", times.all { it >= "08:00:00" && it <= "09:00:00" }, times.take(2).toString())
    val dts = (0 until 40).map { gen(RuleType.DATETIME, "2024-01-01 00:00:00,2024-01-02 00:00:00", ts()) as java.sql.Timestamp }
    check("随机日期时间在区间", dts.all {
        val t = it.toLocalDateTime()
        !t.isBefore(java.time.LocalDateTime.of(2024, 1, 1, 0, 0, 0)) &&
            !t.isAfter(java.time.LocalDateTime.of(2024, 1, 2, 0, 0, 0))
    }, dts.firstOrNull()?.toString() ?: "")
    val seqDt = (0L until 3L).map { gen(RuleType.SEQUENCE_DATETIME, "2024-01-01 00:00:00,30", ts(), it).toString() }
    check("递增时间每行+30分钟", seqDt == listOf("2024-01-01 00:00:00.0", "2024-01-01 00:30:00.0", "2024-01-01 01:00:00.0"), seqDt.toString())
    val nows = (0 until 5).map { gen(RuleType.NOW, "-7", ts()) as java.sql.Timestamp }
    val weekAgo = System.currentTimeMillis() - 7 * 86400_000L
    check("当前时间偏移 -7 天", nows.all { kotlin.math.abs(it.time - weekAgo) < 120_000 }, "${nows.firstOrNull()}")
    check("日期区间反了要报错", genOrNull(RuleType.DATE, "2025-01-01,2020-01-01", dt()) == null)

    // ---------- 7. 编码类 ----------
    val cards = (0 until 30).map { gen(RuleType.BANK_CARD, "", vcol(32)).toString() }
    check("银行卡 16 位且 Luhn 校验通过", cards.all { it.length == 16 && luhnOk(it) }, cards.firstOrNull() ?: "")
    val plates = (0 until 30).map { gen(RuleType.PLATE_NUMBER, "", vcol(16)).toString() }
    check("车牌 7 位", plates.all { it.length == 7 }, plates.firstOrNull() ?: "")
    check("车牌自定义前缀", (0 until 10).all { gen(RuleType.PLATE_NUMBER, "京A", vcol(16)).toString().startsWith("京A") })
    val macs = (0 until 20).map { gen(RuleType.MAC, "", vcol(32)).toString() }
    check("MAC 格式", macs.all { Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(it) }, macs.firstOrNull() ?: "")
    val colors = (0 until 20).map { gen(RuleType.COLOR_HEX, "", vcol(16)).toString() }
    check("颜色格式", colors.all { Regex("^#[0-9A-F]{6}$").matches(it) }, colors.firstOrNull() ?: "")
    val ips = (0 until 20).map { gen(RuleType.IP, "", vcol(32)).toString() }
    check("IP 格式", ips.all { Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(it) }, ips.firstOrNull() ?: "")
    val urls = (0 until 10).map { gen(RuleType.URL, "a.com", vcol(64)).toString() }
    check("URL 使用固定域名", urls.all { it.startsWith("https://a.com/") }, urls.firstOrNull() ?: "")
    val pwds = (0 until 20).map { gen(RuleType.PASSWORD, "16,all", vcol(32)).toString() }
    check("密码长度 16", pwds.all { it.length == 16 }, pwds.firstOrNull() ?: "")
    val qqs = (0 until 20).map { gen(RuleType.QQ, "", vcol(16)).toString() }
    check("QQ 长度 5-11 且首位非 0", qqs.all { it.length in 5..11 && it[0] != '0' }, qqs.firstOrNull() ?: "")

    // ---------- 8. 身份/中文业务 ----------
    check("身份证 18 位", (0 until 20).all { gen(RuleType.ID_CARD, "", vcol(32)).toString().length == 18 })
    check("手机号 11 位", (0 until 20).all { gen(RuleType.PHONE, "", vcol(32)).toString().length == 11 })
    check("手机号指定号段", (0 until 10).all { gen(RuleType.PHONE, "186", vcol(32)).toString().startsWith("186") })
    check("邮箱域名可配", (0 until 10).all { gen(RuleType.EMAIL, "demo.cn", vcol(64)).toString().endsWith("@demo.cn") })
    check("城市带省份", gen(RuleType.CITY, "带省份", vcol(64)).toString().endsWith("市"))
    check("城市仅城市", gen(RuleType.CITY, "仅城市", vcol(64)).toString().endsWith("市"))
    val provList = (0 until 40).map { gen(RuleType.PROVINCE, "", vcol(32)).toString() }
    check(
        "省份在省列表内",
        provList.all { it.endsWith("省") || it.endsWith("市") || it.endsWith("区") },
        provList.firstOrNull { !(it.endsWith("省") || it.endsWith("市") || it.endsWith("区")) } ?: "",
    )
    check("固定值", (0 until 5).all { gen(RuleType.FIXED_VALUE, "hello", vcol()).toString() == "hello" })
    check("布尔值", (0 until 20).all { gen(RuleType.BOOLEAN, "", bcol()) is Boolean })

    // ---------- 9. SQL 表达式 ----------
    val raw = gen(RuleType.SQL_EXPRESSION, "now()", ts())
    check("SQL 表达式原样传递", raw is RawSqlLiteral && raw.sql == "now()", "$raw")
    check("SQL 表达式预览加引号正确", MockDataGenerator().toSqlLiteral(raw) == "now()")
    check("预览字面量:单引号翻倍", MockDataGenerator().toSqlLiteral("o'brien") == "'o''brien'")
    check(
        "预览字面量:MySQL 下反斜杠同时被转义(防 `\\'` 逃逸)",
        MockDataGenerator().toSqlLiteral("""a\' OR 1=1 --""", mysql = true) == """'a\\'' OR 1=1 --'""",
        MockDataGenerator().toSqlLiteral("""a\' OR 1=1 --""", mysql = true),
    )
    check(
        "预览字面量:非 MySQL 不动反斜杠(避免篡改数据)",
        MockDataGenerator().toSqlLiteral("""a\'b""") == """'a\''b'""",
        MockDataGenerator().toSqlLiteral("""a\'b"""),
    )
    check("SQL 表达式不生成 NULL", (0 until 200).all { r ->
        MockDataGenerator().generateRow(listOf(RuleConfig(ts(), true, RuleType.SQL_EXPRESSION, "now()", 50)), r.toLong())["c"] != null
    })

    // ---------- 10. 类型强转 ----------
    check("整型列转 Int", gen(RuleType.RANDOM_INT, "1,5", icol()) is Int)
    check("小数列转 BigDecimal", gen(RuleType.RANDOM_INT, "1,5", dcol()) is java.math.BigDecimal)
    check("日期列转 java.sql.Date", gen(RuleType.DATE, "2024-05-01,2024-05-02", dt()) is java.sql.Date)
    check("时间列转 java.sql.Time", gen(RuleType.TIME, "08:00:00,08:10:00", tm()) is java.sql.Time)
    check("时间戳列转 Timestamp", gen(RuleType.DATETIME, "2024-05-01,2024-05-02", ts()) is java.sql.Timestamp)
    check("超长文本被截断", gen(RuleType.RANDOM_TEXT, "20,30,en", vcol(10)).toString().length <= 10)

    // ---------- 11. 参数编辑器模型 ----------
    val specCases = listOf(
        RuleType.ENUM to "a|b|c",
        RuleType.RANDOM_INT to "1,100",
        RuleType.DATE to "2024-01-01,2024-12-31",
        RuleType.DATETIME to "2024-01-01 00:00:00,2024-12-31 23:59:59",
        RuleType.RANDOM_TEXT to "5,20,cn",
        RuleType.PASSWORD to "12,all",
        // 单选/双选下拉类规则
        RuleType.PROVINCE to "简称",
        RuleType.CITY to "带省份",
        RuleType.DISTRICT to "带城市",
        RuleType.ADDRESS to "省市区",
        RuleType.BRAND to "家电类",
        RuleType.PRODUCT_NAME to "水果生鲜类,带品牌",
        RuleType.PRODUCT_CATEGORY to "完整路径,家电类",
    )
    specCases.forEach { (t, param) ->
        val parsed = RuleParamSpec.parse(t, param)
        check("参数解析:${t.name}", parsed.size == RuleParamSpec.fields(t).size, "parsed=$parsed fields=${RuleParamSpec.fields(t).size}")
        check("参数回写幂等:${t.name}", RuleParamSpec.compose(t, parsed) == param, "compose=${RuleParamSpec.compose(t, parsed)}")
        check("参数摘要非空:${t.name}", RuleParamSpec.summary(t, param).isNotBlank(), RuleParamSpec.summary(t, param))
        check("参数校验通过:${t.name}", RuleParamSpec.validate(t, parsed) == null, RuleParamSpec.validate(t, parsed) ?: "")
    }
    check("枚举多行编辑回写", RuleParamSpec.compose(RuleType.ENUM, listOf("甲\n乙\n丙")) == "甲|乙|丙")
    check("枚举粘贴逗号也能识别", RuleParamSpec.compose(RuleType.ENUM, listOf("甲,乙;丙\n丁")) == "甲|乙|丙|丁")
    check("非法数值被拦截", RuleParamSpec.validate(RuleType.RANDOM_INT, listOf("abc", "100")) != null)
    check("反区间被拦截", RuleParamSpec.validate(RuleType.RANDOM_INT, listOf("100", "1")) != null)
    check("非法日期被拦截", RuleParamSpec.validate(RuleType.DATE, listOf("2024/01/01", "2024-12-31")) != null)
    check("正则模板参数校验", RuleParamSpec.validate(RuleType.PATTERN, listOf("[a-z]{3}")) == null)
    check("SQL 表达式参数校验", RuleParamSpec.validate(RuleType.SQL_EXPRESSION, listOf("now()")) == null)
    check("可留空参数允许为空", RuleParamSpec.validate(RuleType.EMAIL, listOf("")) == null)
    check("日期预设可用", RuleParamSpec.presets(RuleType.DATETIME).isNotEmpty())
    check("预设还原为合法参数", RuleType.entries.all { t ->
        RuleParamSpec.presets(t).all { p ->
            RuleParamSpec.validate(t, RuleParamSpec.parse(t, p.second)) == null &&
                RuleParamSpec.summary(t, p.second).isNotBlank()
        }
    })
    check("每个可配置规则都有参数摘要", RuleType.entries.filter { it.paramHint.isNotBlank() }.all { RuleParamSpec.summary(it, it.defaultParam).isNotBlank() })

    // ---------- 11.5 参数与列类型兼容性(ParamCompat:纯静态判断,不跑生成引擎) ----------
    check("数值列 + 文本枚举 = 不兼容", ParamCompat.issue(icol(), RuleType.ENUM, "男|女") != null)
    check("数值列 + 数字枚举 = 兼容", ParamCompat.issue(icol(), RuleType.ENUM, "1|2|3") == null)
    check("字符列 + 文本枚举 = 兼容", ParamCompat.issue(vcol(), RuleType.ENUM, "男|女") == null)
    check("小数数值列 + 文本枚举 = 不兼容", ParamCompat.issue(dcol(), RuleType.SEQUENCE_ENUM, "甲|乙") != null)
    check("空候选值被拦下", ParamCompat.issue(vcol(), RuleType.ENUM, "") != null)
    check("整数区间参数合法", ParamCompat.issue(icol(), RuleType.RANDOM_INT, "18,65") == null)
    check("整数区间格式错误被拦下", ParamCompat.issue(icol(), RuleType.RANDOM_INT, "abc") != null)
    check("整数区间反向被拦下", ParamCompat.issue(icol(), RuleType.RANDOM_INT, "100,1") != null)
    check("自增起始值 auto 合法", ParamCompat.issue(icol(), RuleType.AUTO_INCREMENT, "auto,1") == null)
    check("自增起始值非整数被拦下", ParamCompat.issue(icol(), RuleType.AUTO_INCREMENT, "x,1") != null)
    check("小数区间反向被拦下", ParamCompat.issue(dcol(), RuleType.RANDOM_DECIMAL, "9.9,1.1,2") != null)
    check("不相关规则不误报", ParamCompat.issue(vcol(), RuleType.CHINESE_NAME, "") == null)
    check(
        "数值列判定只认数值类型",
        ParamCompat.isNumericColumn(icol()) && !ParamCompat.isNumericColumn(vcol()) && !ParamCompat.isNumericColumn(ts()),
    )

    // ---------- 11.6 中英文切换(i18n) ----------
    // 关键回归点:「可留空」以前靠 paramHint.startsWith("可选") / label.contains("可留空") 判断,
    // 文案一翻译这两个判断就静默失效 —— 现在必须由 nullableParam / ParamField.optional 显式承载。
    check("中文模式规则名", RuleType.ENUM.display == "随机枚举(候选值可配置)")
    check("中文模式可留空规则允许空值", RuleParamSpec.validate(RuleType.EMAIL, listOf("")) == null)
    I18n.lang = Lang.EN
    check("英文模式规则名切换", RuleType.ENUM.display == "Random enum (configurable)")
    check("英文模式参数提示切换", RuleType.ENUM.paramHint.isNotBlank() && RuleType.ENUM.paramHint != "候选值,每行一个")
    check("英文模式分组名切换", RuleType.RANDOM_INT.group.label == "Number")
    check("英文模式校验消息为英文", RuleParamSpec.validate(RuleType.ENUM, listOf(""))?.contains("cannot be empty") == true)
    check("英文模式参数摘要为英文", RuleParamSpec.summary(RuleType.ENUM, "a|b|c").startsWith("Enum"))
    check("英文模式兼容性提示为英文", ParamCompat.issue(icol(), RuleType.ENUM, "男|女")?.contains("numeric") == true)
    check("英文模式仍允许留空参数", RuleParamSpec.validate(RuleType.EMAIL, listOf("")) == null)
    check("英文模式 auto 起始值仍合法", ParamCompat.issue(icol(), RuleType.AUTO_INCREMENT, "auto,1") == null)
    check("英文模式自增起始值可为空", RuleParamSpec.validate(RuleType.AUTO_INCREMENT, listOf("", "1")) == null)
    check("英文模式序列号前缀可为空", RuleParamSpec.validate(RuleType.SERIAL_NUMBER, listOf("", "1", "1", "6")) == null)
    check("英文模式必填项仍被拦下", RuleParamSpec.validate(RuleType.ENUM, listOf("")) != null)
    I18n.lang = Lang.ZH
    check("切回中文后文案恢复", RuleType.ENUM.display == "随机枚举(候选值可配置)" && RuleType.RANDOM_INT.group.label == "数字")
    check(
        "nullableParam 不依赖文案",
        RuleType.EMAIL.nullableParam && RuleType.USERNAME.nullableParam && !RuleType.CHINESE_NAME.nullableParam,
    )
    check("可留空字段被标记 optional", RuleParamSpec.fields(RuleType.EMAIL).first().optional)
    check("无参规则没有字段", RuleParamSpec.fields(RuleType.CHINESE_NAME).isEmpty())

    // ---------- 12. 品牌 / 产品 / 类目 ----------
    val brandAll = (0 until 120).map { gen(RuleType.BRAND, "全部", vcol(64)).toString() }
    check("品牌名称非空", brandAll.all { it.isNotBlank() })
    check("品牌覆盖多个品类", brandAll.toSet().size > 20, "种类=${brandAll.toSet().size}")
    check(
        "指定品类品牌属于该品类",
        (0 until 30).all { gen(RuleType.BRAND, "家电类", vcol(64)).toString() in MockDictionaries.categoryOf("家电类")!!.brands },
    )
    check(
        "品牌未知品类回退到全部",
        (0 until 30).all { gen(RuleType.BRAND, "不存在的品类", vcol(64)).toString() in MockDictionaries.productCategories.flatMap { it.brands } },
    )

    val fruit = MockDictionaries.categoryOf("水果生鲜类")!!
    val fruitNames = (0 until 60).map { gen(RuleType.PRODUCT_NAME, "水果生鲜类,不带品牌", vcol(64)).toString() }
    check("产品名称属于所选品类", fruitNames.all { it in fruit.products }, fruitNames.firstOrNull { it !in fruit.products } ?: "")
    check("不带品牌产品名无品牌前缀", fruitNames.none { s -> fruit.brands.any { s.startsWith(it) } })
    val fruitBranded = (0 until 60).map { gen(RuleType.PRODUCT_NAME, "水果生鲜类,带品牌", vcol(64)).toString() }
    check("带品牌产品名含品牌前缀", fruitBranded.all { s -> fruit.brands.any { s.startsWith(it) } }, fruitBranded.take(2).toString())
    val anyProduct = (0 until 120).map { gen(RuleType.PRODUCT_NAME, "全部,不带品牌", vcol(64)).toString() }
    check("全部品类产品名覆盖多个大类", anyProduct.toSet().size > 30, "种类=${anyProduct.toSet().size}")

    val level1 = (0 until 60).map { gen(RuleType.PRODUCT_CATEGORY, "一级类目,全部", vcol(32)).toString() }
    check(
        "一级类目都在类目表内",
        level1.all { it in MockDictionaries.productCategoryLabels },
        level1.firstOrNull { it !in MockDictionaries.productCategoryLabels } ?: "",
    )
    val level2 = (0 until 60).map { gen(RuleType.PRODUCT_CATEGORY, "二级类目,食品饮料类", vcol(32)).toString() }
    check("二级类目属于所选品类", level2.all { it in MockDictionaries.categoryOf("食品饮料类")!!.subCategories })
    val paths = (0 until 60).map { gen(RuleType.PRODUCT_CATEGORY, "完整路径,全部", vcol(64)).toString() }
    check(
        "完整路径=一级-二级",
        paths.all { s ->
            val c = MockDictionaries.productCategories.firstOrNull { s.startsWith(it.label + "-") }
            c != null && s.removePrefix(c.label + "-") in c.subCategories
        },
        paths.take(2).toString(),
    )
    check(
        "语料库每个品类都有产品/二级类目/品牌",
        MockDictionaries.productCategories.all { it.products.isNotEmpty() && it.subCategories.isNotEmpty() && it.brands.isNotEmpty() },
    )

    // ---------- 13. 省 / 市 / 区县 / 地址 ----------
    check("区县数据的城市都在省市表内", MockDictionaries.districtsByCity.keys.all { it in MockDictionaries.cityToProvince })
    check("区县数据非空且无空值", MockDictionaries.allDistricts.size > 100 && MockDictionaries.allDistricts.none { it.isBlank() })
    check("城市都属于已知省份", MockDictionaries.cityToProvince.values.all { it in MockDictionaries.provinces })

    val provFull = (0 until 60).map { gen(RuleType.PROVINCE, "完整名称", vcol(32)).toString() }
    check("省份完整名称带后缀", provFull.all { it.endsWith("省") || it.endsWith("市") || it.endsWith("区") }, provFull.take(3).toString())
    val provShort = (0 until 60).map { gen(RuleType.PROVINCE, "简称", vcol(32)).toString() }
    check("省份简称不带后缀", provShort.all { !it.endsWith("省") && !it.endsWith("市") && !it.endsWith("区") }, provShort.take(5).toString())
    check("内蒙古简称", MockDictionaries.shortProvince("内蒙古自治区") == "内蒙古")
    check("广西简称", MockDictionaries.shortProvince("广西壮族自治区") == "广西")
    check("新疆简称", MockDictionaries.shortProvince("新疆维吾尔自治区") == "新疆")
    check("香港简称", MockDictionaries.shortProvince("香港特别行政区") == "香港")
    check("北京市简称", MockDictionaries.shortProvince("北京市") == "北京")
    check("黑龙江省简称", MockDictionaries.shortProvince("黑龙江省") == "黑龙江")

    val onlyDistrict = (0 until 60).map { gen(RuleType.DISTRICT, "仅区县", vcol(32)).toString() }
    check(
        "仅区县不带城市前缀",
        onlyDistrict.all { it in MockDictionaries.allDistricts },
        onlyDistrict.firstOrNull { it !in MockDictionaries.allDistricts } ?: "",
    )
    val withCity = (0 until 60).map { gen(RuleType.DISTRICT, "带城市", vcol(64)).toString() }
    check(
        "带城市的区县=城市+区县",
        withCity.all { s -> MockDictionaries.districtsByCity.any { (c, ds) -> ds.any { s == c + it } } },
        withCity.take(2).toString(),
    )
    check(
        "行政区前缀:直辖市不重复、普通市带省",
        MockDictionaries.regionPrefix("北京市") == "北京市" && MockDictionaries.regionPrefix("杭州市") == "浙江省杭州市",
    )

    val addrRegion = (0 until 60).map { gen(RuleType.ADDRESS, "省市区", vcol(200)).toString() }
    check("省市区地址以省份开头", addrRegion.all { s -> MockDictionaries.provinces.any { s.startsWith(it) } }, addrRegion.take(2).toString())
    check("省市区地址无街道门牌", addrRegion.none { it.contains("号") })
    val addrStreet = (0 until 60).map { gen(RuleType.ADDRESS, "街道门牌", vcol(200)).toString() }
    check("街道门牌无省份", addrStreet.none { s -> MockDictionaries.provinces.any { s.startsWith(it) } })
    check("街道门牌含门牌号", addrStreet.all { it.contains("号") })
    val addrFull = (0 until 60).map { gen(RuleType.ADDRESS, "完整地址", vcol(200)).toString() }
    check(
        "完整地址以省份开头且含门牌号",
        addrFull.all { s -> MockDictionaries.provinces.any { s.startsWith(it) } && s.contains("号") },
        addrFull.firstOrNull() ?: "",
    )

    // ---------- 14. 规则推断 ----------
    val suggestCases = mapOf(
        "user_email" to RuleType.EMAIL, "mobile_no" to RuleType.PHONE, "id_card_no" to RuleType.ID_CARD,
        "bank_card_no" to RuleType.BANK_CARD, "plate_no" to RuleType.PLATE_NUMBER, "mac_address" to RuleType.MAC,
        "user_name" to RuleType.USERNAME, "password_hash" to RuleType.PASSWORD, "company_name" to RuleType.COMPANY,
        "province_name" to RuleType.PROVINCE, "city_name" to RuleType.CITY, "job_title" to RuleType.JOB_TITLE,
        "dept_name" to RuleType.DEPARTMENT, "university" to RuleType.UNIVERSITY, "product_name" to RuleType.PRODUCT_NAME,
        "color_hex" to RuleType.COLOR_HEX, "ip_addr" to RuleType.IP, "uuid" to RuleType.UUID,
        "wechat_id" to RuleType.WECHAT, "qq_no" to RuleType.QQ, "address" to RuleType.ADDRESS,
        // 新增:品牌 / 产品类别 / 区县
        "brand_name" to RuleType.BRAND, "product_brand" to RuleType.BRAND,
        "product_category" to RuleType.PRODUCT_CATEGORY, "category_name" to RuleType.PRODUCT_CATEGORY,
        "goods_class" to RuleType.PRODUCT_CATEGORY,
        "district_name" to RuleType.DISTRICT, "county_name" to RuleType.DISTRICT, "goods_name" to RuleType.PRODUCT_NAME,
    )
    suggestCases.forEach { (name, expected) ->
        val actual = RuleSuggester.suggest(ColumnMeta(name, Types.VARCHAR, "varchar", true, false, 64, 0))
        check("规则推断 $name", actual == expected, "实际=$actual 期望=$expected")
    }
    check("int 列推断随机整数", RuleSuggester.suggest(icol()) == RuleType.RANDOM_INT)
    check("numeric 列推断随机小数", RuleSuggester.suggest(dcol()) == RuleType.RANDOM_DECIMAL)
    check("date 列推断随机日期", RuleSuggester.suggest(dt()) == RuleType.DATE)
    check("time 列推断随机时间", RuleSuggester.suggest(tm()) == RuleType.TIME)
    check("timestamp 列推断日期时间", RuleSuggester.suggest(ts()) == RuleType.DATETIME)
    check("jsonb 列推断固定值", RuleSuggester.suggest(ColumnMeta("data", Types.OTHER, "jsonb", true, false, 0, 0)) == RuleType.FIXED_VALUE)
    check("jsonb 默认值为 {}", RuleSuggester.defaultParam(RuleType.FIXED_VALUE, ColumnMeta("data", Types.OTHER, "jsonb", true, false, 0, 0)) == "{}")
    check("varchar(8) 默认文本长度不超列宽", RuleSuggester.defaultParam(RuleType.RANDOM_TEXT, vcol(8)) == "1,8,cn")
    // 数字列不能被"名称类"规则抢走
    check("brand_id 推断随机整数", RuleSuggester.suggest(ColumnMeta("brand_id", Types.INTEGER, "int4", true, false, 10, 0)) == RuleType.RANDOM_INT)
    check("city_id 推断随机整数", RuleSuggester.suggest(ColumnMeta("city_id", Types.INTEGER, "int4", true, false, 10, 0)) == RuleType.RANDOM_INT)
    check("category_id 推断随机整数", RuleSuggester.suggest(ColumnMeta("category_id", Types.INTEGER, "int4", true, false, 10, 0)) == RuleType.RANDOM_INT)
    check("uuid 类型列推断 UUID 规则", RuleSuggester.suggest(ColumnMeta("id", Types.OTHER, "uuid", true, false, 36, 0)) == RuleType.UUID)
    check("uniqueidentifier 列推断 UUID 规则", RuleSuggester.suggest(ColumnMeta("id", Types.OTHER, "uniqueidentifier", true, false, 36, 0)) == RuleType.UUID)
    check(
        "uuid 列生成合法 UUID",
        gen(RuleType.UUID, "", ColumnMeta("id", Types.OTHER, "uuid", true, false, 36, 0))
            .toString().matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
    )

    // ---------- 15. JDBC 类型归一化(修复「未被支持的类型值:0」) ----------
    // DataGrip 内省模型对部分类型返回 0(Types.NULL),而驱动 setObject 的 targetType 是白名单制
    check("0 + jsonb → OTHER(无类型绑定)", JdbcTypeResolver.resolve(Types.NULL, "jsonb") == Types.OTHER)
    check("0 + 类型名未知 → OTHER", JdbcTypeResolver.resolve(0, "my_custom_type") == Types.OTHER)
    check("0 + 无类型名 → OTHER", JdbcTypeResolver.resolve(0, null) == Types.OTHER)
    check("0 + int4 → INTEGER", JdbcTypeResolver.resolve(0, "int4") == Types.INTEGER)
    check("0 + numeric(10, 2) → DECIMAL", JdbcTypeResolver.resolve(0, "numeric(10, 2)") == Types.DECIMAL)
    check("0 + character varying(64) → VARCHAR", JdbcTypeResolver.resolve(0, "character varying(64)") == Types.VARCHAR)
    check("0 + timestamptz → TIMESTAMP", JdbcTypeResolver.resolve(0, "timestamptz") == Types.TIMESTAMP)
    check("0 + bool → BOOLEAN", JdbcTypeResolver.resolve(0, "bool") == Types.BOOLEAN)
    check("0 + 空类型名 → OTHER", JdbcTypeResolver.resolve(0, "?") == Types.OTHER)
    check("数组类型 → OTHER(不按标量强转)", JdbcTypeResolver.resolve(Types.ARRAY, "int4[]") == Types.OTHER)
    check("数组类型(无 jdbcType) → OTHER", JdbcTypeResolver.resolve(0, "character varying[]") == Types.OTHER)
    check("Types.NULL 单独出现也换掉", JdbcTypeResolver.resolve(Types.NULL, "varchar") == Types.VARCHAR)

    // 标准 JDBC 类型里驱动不认的几种必须换成等价类型,否则同样报「未被支持的类型值」
    check("NVARCHAR → VARCHAR", JdbcTypeResolver.resolve(Types.NVARCHAR, "nvarchar") == Types.VARCHAR)
    check("NCHAR → CHAR", JdbcTypeResolver.resolve(Types.NCHAR, "nchar") == Types.CHAR)
    check("LONGNVARCHAR → VARCHAR", JdbcTypeResolver.resolve(Types.LONGNVARCHAR, "longnvarchar") == Types.VARCHAR)
    check("NCLOB → CLOB", JdbcTypeResolver.resolve(Types.NCLOB, "nclob") == Types.CLOB)
    check("TIME_WITH_TIMEZONE → TIME", JdbcTypeResolver.resolve(Types.TIME_WITH_TIMEZONE, "timetz") == Types.TIME)

    // 正常类型不能被改动
    check("VARCHAR 保持", JdbcTypeResolver.resolve(Types.VARCHAR, "varchar") == Types.VARCHAR)
    check("INTEGER 保持", JdbcTypeResolver.resolve(Types.INTEGER, "int4") == Types.INTEGER)
    check("DECIMAL 保持", JdbcTypeResolver.resolve(Types.DECIMAL, "numeric") == Types.DECIMAL)
    check("BOOLEAN 保持", JdbcTypeResolver.resolve(Types.BOOLEAN, "bool") == Types.BOOLEAN)
    check("TIMESTAMP 保持", JdbcTypeResolver.resolve(Types.TIMESTAMP, "timestamp") == Types.TIMESTAMP)
    check("TIMESTAMP_WITH_TIMEZONE 保持", JdbcTypeResolver.resolve(Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz") == Types.TIMESTAMP_WITH_TIMEZONE)
    check("OTHER 保持", JdbcTypeResolver.resolve(Types.OTHER, "jsonb") == Types.OTHER)

    val typeProbes = listOf(
        0 to "jsonb", 0 to null, 0 to "?", Types.NULL to "", Types.OTHER to "jsonb",
        Types.NVARCHAR to "nvarchar", Types.NCHAR to null, Types.NCLOB to "nclob",
        Types.TIME_WITH_TIMEZONE to "timetz", Types.VARCHAR to "varchar", Types.INTEGER to "int4",
        Types.BOOLEAN to "bool", Types.DATE to "date", Types.TIME to "time", Types.DECIMAL to "numeric",
        Types.BIGINT to "int8", Types.BINARY to "bytea", Types.ROWID to "rowid", 2000 to "java_object",
        Types.LONGNVARCHAR to "text", Types.SMALLINT to "int2", Types.ARRAY to "int4[]",
        Types.TIMESTAMP to "timestamp without time zone", Types.DOUBLE to "double precision",
    )
    check(
        "0 与驱动不认的类型都不允许直接绑定",
        listOf(Types.NULL, Types.NVARCHAR, Types.NCHAR, Types.NCLOB, Types.TIME_WITH_TIMEZONE, Types.ROWID)
            .none { JdbcTypeResolver.isBindableTarget(it) },
    )
    check(
        "归一化幂等",
        typeProbes.all { (t, n) ->
            val once = JdbcTypeResolver.resolve(t, n)
            JdbcTypeResolver.resolve(once, n) == once
        },
    )
    check(
        "归一化结果全部可安全绑定",
        typeProbes.all { (t, n) -> JdbcTypeResolver.isBindableTarget(JdbcTypeResolver.resolve(t, n)) },
        typeProbes.joinToString { JdbcTypeResolver.resolve(it.first, it.second).toString() },
    )
    check(
        "解析结果不会落在驱动拒绝的类型上",
        typeProbes.none {
            JdbcTypeResolver.resolve(it.first, it.second) in listOf(
                Types.NULL, Types.NVARCHAR, Types.NCHAR, Types.LONGNVARCHAR,
                Types.NCLOB, Types.TIME_WITH_TIMEZONE, Types.ROWID,
            )
        },
    )

    // 穷尽 java.sql.Types 常量:无论驱动/内省给出什么数字,归一化后都必须能安全交给 setObject
    val allSqlTypes = listOf(
        Types.BIT, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
        Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL,
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.DATE, Types.TIME, Types.TIMESTAMP,
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.NULL, Types.OTHER,
        Types.JAVA_OBJECT, Types.DISTINCT, Types.STRUCT, Types.ARRAY, Types.BLOB, Types.CLOB,
        Types.REF, Types.DATALINK, Types.BOOLEAN, Types.ROWID, Types.NCHAR, Types.NVARCHAR,
        Types.LONGNVARCHAR, Types.NCLOB, Types.SQLXML, Types.REF_CURSOR,
        Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
    )
    check(
        "所有 java.sql.Types 常量归一化后都可绑定(无名)",
        allSqlTypes.all { JdbcTypeResolver.isBindableTarget(JdbcTypeResolver.resolve(it, null)) },
        allSqlTypes.filterNot { JdbcTypeResolver.isBindableTarget(JdbcTypeResolver.resolve(it, null)) }.toString(),
    )
    check(
        "所有 java.sql.Types 常量归一化后都可绑定(带名)",
        allSqlTypes.all { JdbcTypeResolver.isBindableTarget(JdbcTypeResolver.resolve(it, "varchar")) },
        allSqlTypes.filterNot { JdbcTypeResolver.isBindableTarget(JdbcTypeResolver.resolve(it, "varchar")) }.toString(),
    )

    // ---- JDBC URL 库名改写:一个数据源内省多个库时,表在 A 库而数据源默认连 B 库 ----
    check(
        "PG 改写库名",
        JdbcUrlUtil.withDatabase("jdbc:postgresql://localhost:5432/postgres", "uni_grid") ==
            "jdbc:postgresql://localhost:5432/uni_grid",
    )
    check(
        "PG 改写库名保留查询参数",
        JdbcUrlUtil.withDatabase("jdbc:postgresql://localhost:5432/postgres?ssl=true", "uni_grid") ==
            "jdbc:postgresql://localhost:5432/uni_grid?ssl=true",
    )
    check(
        "已是目标库则不改写",
        JdbcUrlUtil.withDatabase("jdbc:postgresql://localhost:5432/uni_grid", "uni_grid") == null,
    )
    check(
        "MySQL 改写库名",
        JdbcUrlUtil.withDatabase("jdbc:mysql://127.0.0.1:3306/work", "metadata") ==
            "jdbc:mysql://127.0.0.1:3306/metadata",
    )
    check(
        "SQL Server databaseName 参数改写",
        JdbcUrlUtil.withDatabase("jdbc:sqlserver://host:1433;databaseName=master;encrypt=false", "sales") ==
            "jdbc:sqlserver://host:1433;databaseName=sales;encrypt=false",
    )
    check("Oracle thin 无 // 形式不改写", JdbcUrlUtil.withDatabase("jdbc:oracle:thin:@host:1521:orcl", "o") == null)
    check("多层路径不改写", JdbcUrlUtil.withDatabase("jdbc:h2:tcp://localhost/~/test", "o") == null)
    check("无库名段不改写", JdbcUrlUtil.withDatabase("jdbc:postgresql://localhost:5432", "uni_grid") == null)
    check("空库名不改写", JdbcUrlUtil.withDatabase("jdbc:postgresql://h:5432/a", null) == null)
    check("空 URL 不改写", JdbcUrlUtil.withDatabase(null, "a") == null)
    check(
        "库名特殊字符会转义",
        JdbcUrlUtil.withDatabase("jdbc:postgresql://h:5432/a", "my db/x") ==
            "jdbc:postgresql://h:5432/my%20db%2Fx",
    )
    check(
        "databaseOf 读出当前库",
        JdbcUrlUtil.databaseOf("jdbc:postgresql://localhost:5432/postgres?x=1") == "postgres",
    )
    check("databaseOf 无库名返回 null", JdbcUrlUtil.databaseOf("jdbc:postgresql://localhost:5432") == null)
    val rewrittenOnce = JdbcUrlUtil.withDatabase("jdbc:postgresql://h:5432/postgres", "uni_grid")
    check("改写收敛(再改一次为 null)", JdbcUrlUtil.withDatabase(rewrittenOnce, "uni_grid") == null)

    // ---- TableRef:带库名才能区分不同库里的同名表 ----
    check("TableRef 显示 schema.table", TableRef("public", "t_user").displayName == "public.t_user")
    check("TableRef 无 schema 只显示表名", TableRef(null, "t_user").displayName == "t_user")
    check(
        "TableRef fullName 含库名",
        TableRef("public", "t_user", "uni_grid").fullName == "uni_grid.public.t_user",
    )
    check("TableRef 无库名时 fullName 回退", TableRef("public", "t_user").fullName == "public.t_user")

    // ---- 列结构比对:界面列(DataGrip 内省缓存) vs 数据库实际列 ----
    // 真实翻车场景:表建好后又 ALTER TABLE 加了 NOT NULL 列 register_time,内省缓存没刷新,
    // 界面少这一列 → 生成的 INSERT 不含该列 → 数据库报 not-null 约束,整批回滚。
    val uiCols = listOf(
        ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0),
        ColumnMeta("username", Types.VARCHAR, "varchar", true, false, 64, 0),
        ColumnMeta("age", Types.INTEGER, "int4", true, false, 10, 0),
    )
    val liveCols = uiCols + ColumnMeta("register_time", Types.TIMESTAMP, "timestamp", false, false, 29, 6)

    check("列完全一致时无差异", ColumnDiff.describe(uiCols, uiCols) == null)
    check("列完全一致时 same 为真", ColumnDiff.compare(uiCols, uiCols).same)
    check("列完全一致时 sameColumns 为真", ColumnDiff.compare(uiCols, uiCols).sameColumns)

    val missingDiff = ColumnDiff.compare(uiCols, liveCols)
    check("识别出数据库多出的列", missingDiff.missing == listOf("register_time"), missingDiff.missing.toString())
    check("多出的列不会被当成界面多余列", missingDiff.extra.isEmpty())
    check("列名集合不一致 → sameColumns 为假", !missingDiff.sameColumns)
    check(
        "差异描述包含缺失列名",
        ColumnDiff.describe(uiCols, liveCols)?.contains("register_time") == true,
        ColumnDiff.describe(uiCols, liveCols).orEmpty(),
    )

    val extraDiff = ColumnDiff.compare(liveCols, uiCols)
    check("识别出界面多余列", extraDiff.extra == listOf("register_time"), extraDiff.extra.toString())
    check("多余列不会被当成缺失列", extraDiff.missing.isEmpty())

    check(
        "同类型不同精度不算类型变化",
        ColumnDiff.compare(
            listOf(ColumnMeta("amount", Types.NUMERIC, "numeric(10,2)", true, false, 10, 2)),
            listOf(ColumnMeta("amount", Types.NUMERIC, "numeric(10,4)", true, false, 10, 4)),
        ).retyped.isEmpty(),
    )
    val retypedDiff = ColumnDiff.compare(
        listOf(ColumnMeta("code", Types.VARCHAR, "varchar", true, false, 32, 0)),
        listOf(ColumnMeta("code", Types.INTEGER, "int4", true, false, 10, 0)),
    )
    check("同名不同类型被识别", retypedDiff.retyped.keys == setOf("code"), retypedDiff.retyped.toString())
    check("类型差异时 sameColumns 仍为真(仅提示不重建)", retypedDiff.sameColumns)
    check("类型差异时 same 为假", !retypedDiff.same)
    check(
        "类型差异描述给出前后类型",
        ColumnDiff.describe(
            listOf(ColumnMeta("code", Types.VARCHAR, "varchar", true, false, 32, 0)),
            listOf(ColumnMeta("code", Types.INTEGER, "int4", true, false, 10, 0)),
        )?.contains("varchar -> int4") == true,
    )
    check(
        "列名比对不区分大小写",
        ColumnDiff.compare(
            listOf(ColumnMeta("USERNAME", Types.VARCHAR, "VARCHAR", true, false, 64, 0)),
            listOf(ColumnMeta("username", Types.VARCHAR, "varchar", true, false, 64, 0)),
        ).same,
    )
    check(
        "列顺序不同不算差异(INSERT 显式列出列名)",
        ColumnDiff.compare(
            listOf(
                ColumnMeta("b", Types.INTEGER, "int4", true, false, 10, 0),
                ColumnMeta("a", Types.INTEGER, "int4", true, false, 10, 0),
            ),
            listOf(
                ColumnMeta("a", Types.INTEGER, "int4", true, false, 10, 0),
                ColumnMeta("b", Types.INTEGER, "int4", true, false, 10, 0),
            ),
        ).same,
    )
    check("两边都空无差异", ColumnDiff.describe(emptyList(), emptyList()) == null)
    check(
        "空界面列可识别出全部真实列",
        ColumnDiff.compare(emptyList(), liveCols).missing.size == liveCols.size,
    )

    // ---- 主键 / 唯一列:整数必须用自增序列,不能用随机整数 ----
    // 真实翻车场景:表 t_user_manual 的 id 是主键,却按「随机整数 1~100」生成,
    // 与库中已有数据撞车 → PostgreSQL 报「重复键违反唯一约束 t_user_manual_pkey」,整批失败。
    val pkCol = ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0, isPrimaryKey = true)
    val uniqueCol = ColumnMeta("seq_no", Types.INTEGER, "int4", false, false, 10, 0, isUnique = true)
    val pkTextCol = ColumnMeta("code", Types.VARCHAR, "varchar", false, false, 32, 0, isPrimaryKey = true)
    val autoCol = ColumnMeta("id", Types.BIGINT, "int8", false, true, 19, 0, isPrimaryKey = true)

    check("整数主键推断为自增数字", RuleSuggester.suggest(pkCol) == RuleType.AUTO_INCREMENT, RuleSuggester.suggest(pkCol).name)
    check("唯一整数列推断为自增数字", RuleSuggester.suggest(uniqueCol) == RuleType.AUTO_INCREMENT)
    check("普通整数列仍是随机整数", RuleSuggester.suggest(icol()) == RuleType.RANDOM_INT)
    check("自增主键交给数据库(SKIP)", RuleSuggester.suggest(autoCol) == RuleType.SKIP)
    check(
        "自增判定优先于列名规则",
        RuleSuggester.suggest(ColumnMeta("phone", Types.VARCHAR, "varchar", false, true, 20, 0)) == RuleType.SKIP,
    )
    check("文本主键不改用自增", RuleSuggester.suggest(pkTextCol) != RuleType.AUTO_INCREMENT)
    check(
        "uuid 主键仍用 UUID 规则",
        RuleSuggester.suggest(ColumnMeta("id", Types.OTHER, "uuid", false, false, 36, 0, isPrimaryKey = true)) == RuleType.UUID,
    )
    check("主键默认参数起始值 auto", RuleSuggester.defaultParam(RuleType.AUTO_INCREMENT, pkCol) == "auto,1")
    check("普通自增列默认参数仍是 1", RuleSuggester.defaultParam(RuleType.AUTO_INCREMENT, icol()) == "1,1")

    check("auto 起始值:纯生成时按 1 递增", (0L until 3L).map { gen(RuleType.AUTO_INCREMENT, "auto,1", pkCol, it) } == listOf(1L, 2L, 3L))
    check("auto 起始值:步长仍生效", (0L until 3L).map { gen(RuleType.AUTO_INCREMENT, "auto,2", pkCol, it) } == listOf(1L, 3L, 5L))
    check("留空起始值等同 auto", (0L until 2L).map { gen(RuleType.AUTO_INCREMENT, "", pkCol, it) } == listOf(1L, 2L))
    check("auto 大小写不敏感", (0L until 2L).map { gen(RuleType.AUTO_INCREMENT, "AUTO,1", pkCol, it) } == listOf(1L, 2L))
    check("中文「自动」也认", (0L until 2L).map { gen(RuleType.AUTO_INCREMENT, "自动,1", pkCol, it) } == listOf(1L, 2L))
    check("显式起始值仍然可用", (0L until 3L).map { gen(RuleType.AUTO_INCREMENT, "100,1", pkCol, it) } == listOf(100L, 101L, 102L))
    check("非数字起始值报错", genOrNull(RuleType.AUTO_INCREMENT, "abc,1", pkCol) == null)

    check("参数摘要显示自动接续", RuleParamSpec.summary(RuleType.AUTO_INCREMENT, "auto,1").contains("接续"), RuleParamSpec.summary(RuleType.AUTO_INCREMENT, "auto,1"))
    check("参数摘要显示显式起始值", RuleParamSpec.summary(RuleType.AUTO_INCREMENT, "100,1").contains("100"))
    check("auto 参数通过校验", RuleParamSpec.validate(RuleType.AUTO_INCREMENT, listOf("auto", "1")) == null)
    check(
        "参数往返不丢 auto",
        RuleParamSpec.compose(RuleType.AUTO_INCREMENT, RuleParamSpec.parse(RuleType.AUTO_INCREMENT, "auto,2")) == "auto,2",
    )

    // ---- 主键/唯一标记 ----
    val marked = PrimaryKeys.mark(
        listOf(
            ColumnMeta("ID", Types.BIGINT, "int8", false, false, 19, 0),
            ColumnMeta("email", Types.VARCHAR, "varchar", true, false, 64, 0),
            ColumnMeta("plain", Types.VARCHAR, "varchar", true, false, 64, 0),
        ),
        listOf("id"),
        listOf("EMAIL"),
    )
    check("主键标记不区分大小写", marked[0].isPrimaryKey)
    check("唯一列标记不区分大小写", marked[1].isUnique)
    check("无关列不打标记", !marked[2].isPrimaryKey && !marked[2].isUnique)
    check("主键优先于唯一标记", !marked[0].isUnique)
    check("mustBeUnique:主键为真", PrimaryKeys.mustBeUnique(pkCol))
    check("mustBeUnique:唯一列为真", PrimaryKeys.mustBeUnique(uniqueCol))
    check("mustBeUnique:普通列为假", !PrimaryKeys.mustBeUnique(icol()))
    check(
        "主键标记不同的两份列元数据不算同结构",
        !PrimaryKeys.sameFlags(pkCol, ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0)),
    )
    check("标记描述可读", PrimaryKeys.describe(pkCol).contains("主键"), PrimaryKeys.describe(pkCol))
    check("空标记描述为空", PrimaryKeys.describe(icol()).isBlank())

    // ---- 列结构核对:主键标记也算结构性差异(否则不会重新推断规则) ----
    check(
        "仅主键标记不同 → 需要重建",
        ColumnDiff.needsRebuild(listOf(ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0)), listOf(pkCol)),
    )
    check(
        "仅唯一标记不同 → 需要重建",
        ColumnDiff.needsRebuild(listOf(ColumnMeta("seq_no", Types.INTEGER, "int4", false, false, 10, 0)), listOf(uniqueCol)),
    )
    check(
        "仅自增标记不同 → 需要重建",
        ColumnDiff.needsRebuild(listOf(ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0)), listOf(autoCol)),
    )
    check("标记一致 → 不需要重建", !ColumnDiff.needsRebuild(listOf(pkCol), listOf(pkCol)))
    check(
        "仅类型表述不同 → 不需要重建",
        !ColumnDiff.needsRebuild(
            listOf(ColumnMeta("amount", Types.NUMERIC, "numeric(10,2)", true, false, 10, 2)),
            listOf(ColumnMeta("amount", Types.NUMERIC, "numeric(10,4)", true, false, 10, 4)),
        ),
    )
    check("少列 → 需要重建", ColumnDiff.needsRebuild(uiCols, liveCols))

    // ---- 插入失败提示(用真实报错原文做输入) ----
    val pgDupKey = """
        java.sql.SQLException: 向表 uni_grid.public.t_user_manual 插入失败(连接数据库:uni_grid)
        Caused by: SQLExceptionWithProperties: 错误: 重复键违反唯一约束"t_user_manual_pkey"
        详细：键值"(id)=(17)" 已经存在
    """.trimIndent()
    val dupHint = InsertFailure.hint(pgDupKey)
    check("重复键能给出提示", dupHint != null, "hint=null")
    check("重复键提示点名冲突列", dupHint?.contains("「id」") == true, dupHint.orEmpty())
    check("重复键提示指向自增数字", dupHint?.contains("自增数字") == true, dupHint.orEmpty())
    check("重复键提示给出 auto", dupHint?.contains("auto") == true, dupHint.orEmpty())
    check(
        "MySQL Duplicate entry 也能识别",
        InsertFailure.duplicateKeyHint("SQLException: Duplicate entry '17' for key 'PRIMARY'") != null,
    )
    check(
        "英文 Key (id)=(17) 也能抠出列名",
        InsertFailure.duplicateKeyHint(
            "ERROR: duplicate key value violates unique constraint \"t_pkey\"\n  Detail: Key (id)=(17) already exists.",
        )?.contains("「id」") == true,
    )
    check(
        "NOT NULL 提示仍然生效",
        InsertFailure.notNullHint("null value in column \"register_time\" violates not-null constraint")
            ?.contains("register_time") == true,
    )
    check("无关错误不给提示", InsertFailure.hint("syntax error at or near \"INSERT\"") == null)
    check(
        "重复键判定不误报 NOT NULL",
        InsertFailure.duplicateKeyHint("null value in column \"a\" violates not-null constraint") == null,
    )

    // ---- 表结构 SQL 探测:DataGrip 远程元数据在 PG 上静默失灵,只能自己查 information_schema ----
    // 表名 / schema 一律走参数绑定(`?` + params),不拼进 SQL 字符串
    val constraintQuery = StructureSql.constraintSql("public", "t_user_manual")
    check(
        "结构 SQL:约束查询命中 table_constraints + key_column_usage",
        constraintQuery.text.contains("information_schema.table_constraints") &&
            constraintQuery.text.contains("key_column_usage") &&
            constraintQuery.text.contains("'PRIMARY KEY','UNIQUE'"),
        constraintQuery.text,
    )
    check(
        "结构 SQL:库/表走参数绑定而非拼接",
        constraintQuery.text.contains("tc.table_name = ?") &&
            constraintQuery.text.contains("tc.table_schema = ?") &&
            constraintQuery.params == listOf("t_user_manual", "public"),
        "text=${constraintQuery.text} params=${constraintQuery.params}",
    )
    check(
        "结构 SQL:schema 为空时不加 schema 条件",
        StructureSql.constraintSql(null, "t").let {
            !it.text.contains("table_schema = ?") && it.params == listOf("t")
        },
    )
    check(
        "结构 SQL:列查询取 is_identity 与 column_default,表名走绑定",
        StructureSql.columnSql("public", "t").let {
            it.text.contains("is_identity") && it.text.contains("column_default") &&
                it.text.contains("table_name = ?") && it.params == listOf("t", "public")
        },
    )
    check(
        "结构 SQL:MySQL 自增查询表名走绑定",
        StructureSql.mysqlAutoSql(null, "t").let { it.text.contains("extra LIKE") && it.params == listOf("t") },
    )
    check("结构 SQL:字面量转义单引号", StructureSql.literal("o'brien") == "'o''brien'", StructureSql.literal("o'brien"))
    check(
        "结构 SQL:注入载荷不进 SQL 文本(参数绑定)",
        StructureSql.constraintSql("public", "t' OR 1=1 --").let {
            !it.text.contains("OR 1=1") && it.params == listOf("t' OR 1=1 --", "public")
        },
    )
    check("只对 MySQL 系查 extra", StructureSql.isMysql("MySQL") && StructureSql.isMysql("MariaDB") && !StructureSql.isMysql("PostgreSQL"))

    // 真实 PostgreSQL 的返回形态:单列主键 / 单列唯一 / 联合唯一 / 联合主键 / 外键
    val structure = StructureSql.fromConstraints(
        listOf(
            StructureSql.ConstraintRow("PRIMARY KEY", "t_user_manual_pkey", "id"),
            StructureSql.ConstraintRow("UNIQUE", "t_user_manual_email_key", "email"),
            StructureSql.ConstraintRow("UNIQUE", "t_multi_uq", "a"),
            StructureSql.ConstraintRow("UNIQUE", "t_multi_uq", "b"),
            StructureSql.ConstraintRow("PRIMARY KEY", "t_multi_pk", "x"),
            StructureSql.ConstraintRow("PRIMARY KEY", "t_multi_pk", "y"),
            StructureSql.ConstraintRow("FOREIGN KEY", "t_fk", "owner_id"),
        ),
    )
    check("单列主键被识别", structure.primaryKeys == setOf("id"), "${structure.primaryKeys}")
    check("单列唯一被识别", structure.uniqueColumns == setOf("email"), "${structure.uniqueColumns}")
    check("联合唯一被忽略(单列保证不了唯一)", "a" !in structure.uniqueColumns && "b" !in structure.uniqueColumns)
    check("联合主键被忽略", "x" !in structure.primaryKeys && "y" !in structure.primaryKeys)
    check("外键不参与", "owner_id" !in structure.primaryKeys && "owner_id" !in structure.uniqueColumns)

    check(
        "自增识别:serial 的 nextval 默认值",
        StructureSql.isAuto(StructureSql.ColumnRow("id", false, "nextval('t_user_manual_id_seq'::regclass)")),
    )
    check("自增识别:GENERATED AS IDENTITY", StructureSql.isAuto(StructureSql.ColumnRow("id", true, null)))
    check("自增识别:MySQL auto_increment", StructureSql.isAuto(StructureSql.ColumnRow("id", false, null, "auto_increment")))
    check("普通列不算自增", !StructureSql.isAuto(StructureSql.ColumnRow("age", false, null)))
    check(
        "列查询结果转结构",
        StructureSql.fromColumns(
            listOf(
                StructureSql.ColumnRow("id", false, "nextval('s'::regclass)"),
                StructureSql.ColumnRow("age", false, null),
            ),
        ).autoColumns == setOf("id"),
    )
    check(
        "结构合并是取并集",
        (StructureSql.Structure(setOf("a")) + StructureSql.Structure(uniqueColumns = setOf("b"))).let {
            it.primaryKeys == setOf("a") && it.uniqueColumns == setOf("b")
        },
    )
    check("空结构判定", StructureSql.Structure().isEmpty && !StructureSql.Structure(setOf("id")).isEmpty)
    check("结构摘要含自增", StructureSql.Structure(autoColumns = setOf("id")).describe().contains("自增=[id]"))

    // SQL 探测结果要能落到列元数据上:bigserial 主键 → 交给数据库;普通整数主键 → 自增数字
    val viaSqlAuto = PrimaryKeys.mark(
        listOf(ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0)),
        emptySet(),
        emptyList(),
        setOf("ID"),
    )
    check("SQL 探测的自增标记合并成功", viaSqlAuto[0].autoIncrement)
    check("自增列 → 不生成(交给数据库)", RuleSuggester.suggest(viaSqlAuto[0]) == RuleType.SKIP, "${RuleSuggester.suggest(viaSqlAuto[0])}")

    val viaSqlPk = PrimaryKeys.mark(
        listOf(ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0)),
        structure.primaryKeys,
    )
    check("SQL 探测的主键标记合并成功", viaSqlPk[0].isPrimaryKey)
    check(
        "整数主键 → 自增数字(不再是会撞库的随机整数)",
        RuleSuggester.suggest(viaSqlPk[0]) == RuleType.AUTO_INCREMENT,
        "${RuleSuggester.suggest(viaSqlPk[0])}",
    )
    check("主键默认参数为 auto", RuleSuggester.defaultParam(RuleType.AUTO_INCREMENT, viaSqlPk[0]) == "auto,1")

    // ---- 冲突列名提取:插入失败后自动修复靠它定位改哪一列 ----
    check("中文报错里抠出冲突列", InsertFailure.conflictColumn(pgDupKey) == "id", "${InsertFailure.conflictColumn(pgDupKey)}")
    check("英文报错里抠出冲突列", InsertFailure.conflictColumn("Detail: Key (id)=(17) already exists.") == "id")
    check(
        "带库名前缀的列名只留列",
        InsertFailure.conflictColumn("""详细：键值"(t_user_manual.id)=(17)" 已经存在""") == "id",
    )
    check("无关错误不误报冲突列", InsertFailure.conflictColumn("syntax error at or near \"INSERT\"") == null)

    // ---- 唯一冲突自动修复:改哪一列 + 取值必须用改后的规则 ----
    val plainIntCol = ColumnMeta("id", Types.BIGINT, "int8", false, false, 19, 0)
    val textCol = ColumnMeta("email", Types.VARCHAR, "varchar", true, false, 64, 0)
    val repairRules = listOf(
        RuleConfig(plainIntCol, true, RuleType.RANDOM_INT, "1,100", 0),
        RuleConfig(textCol, true, RuleType.FIXED_VALUE, "x@y.com", 0),
        RuleConfig(ColumnMeta("skipme", Types.INTEGER, "int4", true, false, 10, 0), false, RuleType.SKIP, "", 0),
    )
    check("修复:列名匹配不区分大小写", RuleRepairs.contains(repairRules, "ID") && !RuleRepairs.contains(repairRules, "nope"))
    check("修复:整数列可接续", RuleRepairs.canContinue(repairRules, "id"))
    check("修复:文本列不能接续", !RuleRepairs.canContinue(repairRules, "email"))
    check("修复:不存在的列不能接续", !RuleRepairs.canContinue(repairRules, "nope"))

    val continueRules = RuleRepairs.continueFrom(repairRules, "id", 5000L)
    check(
        "修复:冲突列改成自增数字并带上起始值",
        continueRules[0].type == RuleType.AUTO_INCREMENT && continueRules[0].param == "5000,1",
        "${continueRules[0].type}/${continueRules[0].param}",
    )
    check("修复:其他列不受影响", continueRules[1].type == RuleType.FIXED_VALUE && continueRules[1].param == "x@y.com")

    // 起始值取 5000:与旧规则的值域 1~100 不重叠,断言不会因随机数偶发通过
    val oldValue = MockDataGenerator().generateRow(repairRules, 1L)["id"]
    val newValue = MockDataGenerator().generateRow(RuleRepairs.withActive(repairRules, continueRules), 1L)["id"]
    check("修复后取值来自新规则(接续最大值)", newValue == 5001L, "$newValue")
    check("不合并新规则就只能拿到旧值域的值", (oldValue as Number).toLong() in 1L..100L, "$oldValue")

    val droppedRules = RuleRepairs.drop(repairRules, "ID")
    check("修复:移除列时不区分大小写", droppedRules.size == 2 && droppedRules.none { it.column.name == "id" })

    // ---------- 表搜索与「右击表默认选中」 ----------
    val tableKeys = listOf(
        "public.t_user_manual",
        "public.t_order",
        "uni_grid.public.t_user_manual",
        "public.t_user_role",
    )
    check("搜索:空查询返回全部且顺序不变", TableSearch.filter(tableKeys, "") == tableKeys)
    check("搜索:null 查询等同空", TableSearch.filter(tableKeys, null) == tableKeys)
    check("搜索:纯空白等同空", TableSearch.filter(tableKeys, "   ") == tableKeys)
    check(
        "搜索:按片段过滤且不区分大小写",
        TableSearch.filter(tableKeys, "USER") ==
            listOf("public.t_user_manual", "uni_grid.public.t_user_manual", "public.t_user_role"),
    )
    check("搜索:schema.表 精确收敛到一张", TableSearch.filter(tableKeys, "public.t_order") == listOf("public.t_order"))
    check(
        "搜索:空格分隔的多关键词需全部命中(可一个来自库名)",
        TableSearch.filter(tableKeys, "uni user") == listOf("uni_grid.public.t_user_manual"),
    )
    check("搜索:无匹配返回空", TableSearch.filter(tableKeys, "zzz").isEmpty())
    check("搜索:不存在的表返回空", TableSearch.filter(tableKeys, "zzz_nope").isEmpty())

    check(
        "默认选中:含库名的完整标识优先",
        TableTargets.match(tableKeys, TableTarget("uni_grid", "public", "t_user_manual", "uni_grid")) ==
            "uni_grid.public.t_user_manual",
    )
    check(
        "默认选中:无库名信息时命中 schema.表",
        TableTargets.match(tableKeys, TableTarget("uni_grid", "public", "t_user_manual", null)) == "public.t_user_manual",
    )
    check(
        "默认选中:库名写法不同时按 .schema.表 后缀兜底",
        TableTargets.match(
            listOf("uni_grid.public.t_user_manual", "uni_grid.public.t_order"),
            TableTarget("ds", "public", "t_user_manual", "OTHERDB"),
        ) == "uni_grid.public.t_user_manual",
    )
    check(
        "默认选中:schema 对不上时退回表名唯一匹配",
        TableTargets.match(listOf("t_user_manual", "t_order"), TableTarget(null, "public", "t_order")) == "t_order",
    )
    check(
        "默认选中:同名多张时不猜(返回 null)",
        TableTargets.match(listOf("a.t_user", "b.t_user"), TableTarget(null, null, "t_user")) == null,
    )
    check("默认选中:表不在当前数据源里返回 null", TableTargets.match(tableKeys, TableTarget(null, "public", "nope")) == null)
    check("默认选中:菜单打开(上下文无表)返回 null", TableTargets.match(tableKeys, null) == null)
    check("默认选中:空表列表返回 null", TableTargets.match(emptyList(), TableTarget(null, null, "t_order")) == null)
    check(
        "默认选中:同名候选用于提示用户手动选择",
        TableTargets.nameCandidates(listOf("a.t_user", "b.t_user", "c.other"), TableTarget(null, null, "t_user")) ==
            listOf("a.t_user", "b.t_user"),
    )

    val summary = "通过 $pass 项,失败 $fail 项"
    java.io.File("smoke_result.txt").writeText(buildString {
        append(summary).append("\n\n")
        if (log.isNotEmpty()) append(log) else append("全部通过\n")
    })
    println(summary)
}

private fun luhnOk(number: String): Boolean {
    var sum = 0
    var double = false
    for (i in number.indices.reversed()) {
        var d = number[i] - '0'
        if (double) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
        double = !double
    }
    return sum % 10 == 0
}
