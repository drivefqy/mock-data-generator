package com.github.mockdatagen.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Types
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.abs

/** 需要原样写入 SQL 的值(来自 [RuleType.SQL_EXPRESSION],不参与参数绑定、不加引号) */
class RawSqlLiteral(val sql: String) {
    override fun toString(): String = sql
}

/**
 * Mock 数据生成引擎:按 [RuleConfig] 生成单行的列值(已按列 JDBC 类型完成类型转换)。
 */
class MockDataGenerator(private val random: Random = Random()) {

    /** 生成一行数据;返回 列名 -> 值(可能为 null 表示 SQL NULL)。只包含 enabled 且非 SKIP 的列。 */
    fun generateRow(rules: List<RuleConfig>, rowIndex: Long): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        for (r in rules) {
            if (!r.enabled || r.type == RuleType.SKIP) continue
            // SQL 表达式列无法用 NULL 占位替换,固定不生成 NULL
            val nullIt = r.type != RuleType.SQL_EXPRESSION &&
                r.nullRatio in 1..99 && random.nextInt(100) < r.nullRatio
            row[r.column.name] = if (nullIt) null else coerce(generate(r, rowIndex), r.column, r)
        }
        return row
    }

    // ---------- 规则分发 ----------

    private fun generate(r: RuleConfig, rowIndex: Long): Any? {
        val p = r.param.trim()
        return when (r.type) {
            RuleType.AUTO_INCREMENT -> {
                val parts = csv(p.ifBlank { "$AUTO_START,1" })
                val step = parts.getOrNull(1)?.let { parseLong(it) } ?: 1L
                // auto 表示接续库中最大值:纯生成场景(预览、参数试跑)拿不到数据库,
                // 这里先按 1 生成;真正插入前由 DatabaseAccess 用真实连接解析出确切起始值。
                val start = if (isAutoStartValue(parts[0])) 1L
                else parseLong(parts[0]) ?: throw badParam(
                    r,
                    I18n.t("起始值必须是整数或 $AUTO_START", "start must be an integer or $AUTO_START"),
                )
                start + rowIndex * step
            }

            RuleType.SERIAL_NUMBER -> {
                val parts = csv(p.ifBlank { "NO,1,1,6" })
                val prefix = parts[0]
                val start = parts.getOrNull(1)?.let { parseLong(it) } ?: 1L
                val step = parts.getOrNull(2)?.let { parseLong(it) } ?: 1L
                val pad = (parts.getOrNull(3)?.let { parseLong(it) } ?: 6L).coerceIn(1, 18).toInt()
                prefix + (start + rowIndex * step).toString().padStart(pad, '0')
            }

            RuleType.RANDOM_INT -> {
                val (min, max) = twoLongs(r)
                if (max < min) throw badParam(r, I18n.t("最大值不能小于最小值", "max must not be less than min"))
                randomLong(min, max)
            }

            RuleType.RANDOM_DECIMAL -> {
                val parts = csv(p)
                if (parts.size < 2) throw badParam(r, I18n.t("参数格式:最小值,最大值,小数位", "format: min,max,scale"))
                val min = parseDouble(parts[0]) ?: throw badParam(r, I18n.t("最小值不合法", "invalid min"))
                val max = parseDouble(parts[1]) ?: throw badParam(r, I18n.t("最大值不合法", "invalid max"))
                val scale = parts.getOrNull(2)?.let { parseLong(it)?.toInt() }?.coerceIn(0, 10) ?: 2
                if (max < min) throw badParam(r, I18n.t("最大值不能小于最小值", "max must not be less than min"))
                BigDecimal.valueOf(min + random.nextDouble() * (max - min)).setScale(scale, RoundingMode.HALF_UP)
            }

            RuleType.UUID -> java.util.UUID.randomUUID().toString()

            RuleType.CHINESE_NAME -> chineseName()
            RuleType.ENGLISH_NAME -> englishName()
            RuleType.PHONE -> phone(p)
            RuleType.EMAIL -> email(p)
            RuleType.WECHAT -> wechat(p)
            RuleType.QQ -> qq()
            RuleType.USERNAME -> username(p)
            RuleType.PASSWORD -> password(r, p)
            RuleType.ADDRESS -> address(p)
            RuleType.COMPANY -> company()
            RuleType.BRAND -> brand(p)
            RuleType.JOB_TITLE -> pick(jobTitles)
            RuleType.DEPARTMENT -> pick(departments)
            RuleType.UNIVERSITY -> pick(universities)
            RuleType.PRODUCT_NAME -> productName(p)
            RuleType.PRODUCT_CATEGORY -> productCategory(p)

            RuleType.PROVINCE -> province(p)
            RuleType.DISTRICT -> district(p)
            RuleType.CITY -> {
                val city = pick(cities)
                if (p.contains("仅")) city else MockDictionaries.regionPrefix(city)
            }

            RuleType.RANDOM_TEXT -> {
                val parts = csv(p)
                if (parts.size < 2) {
                    throw badParam(r, I18n.t("参数格式:最小长度,最大长度[,字符集]", "format: minLen,maxLen[,charset]"))
                }
                val min = parseLong(parts[0]) ?: throw badParam(r, I18n.t("最小长度不合法", "invalid min length"))
                val max = parseLong(parts[1]) ?: throw badParam(r, I18n.t("最大长度不合法", "invalid max length"))
                if (max < min) {
                    throw badParam(r, I18n.t("最大长度不能小于最小长度", "max length must not be less than min length"))
                }
                text(min.toInt().coerceAtLeast(1), max.toInt(), parts.getOrNull(2).orEmpty().ifBlank { "cn" })
            }

            RuleType.PATTERN -> {
                if (p.isBlank()) throw badParam(r, I18n.t("正则模板不能为空", "regex pattern cannot be empty"))
                try {
                    RegexRandom.generate(p, random)
                } catch (e: MockRuleException) {
                    throw badParam(r, e.message ?: I18n.t("正则模板不合法", "invalid regex pattern"))
                }
            }

            RuleType.FIXED_VALUE -> p.ifBlank {
                throw badParam(r, I18n.t("固定值不能为空", "fixed value cannot be empty"))
            }

            RuleType.ENUM -> {
                val list = enumValues(r)
                list[random.nextInt(list.size)]
            }

            RuleType.SEQUENCE_ENUM -> {
                val list = enumValues(r)
                list[(rowIndex % list.size).toInt()]
            }

            RuleType.BOOLEAN -> random.nextBoolean()

            RuleType.DATE -> formatDate(randomInstant(r))
            RuleType.TIME -> formatTime(randomClockSeconds(r))
            RuleType.DATETIME -> formatDateTime(randomInstant(r))

            RuleType.SEQUENCE_DATETIME -> {
                val parts = csv(p)
                if (parts.size < 2) throw badParam(r, I18n.t("参数格式:起始时间,步长(分钟)", "format: startTime,stepMinutes"))
                val start = parseDateTimeFlexible(parts[0])
                    ?: throw badParam(r, I18n.t("起始时间不合法(${parts[0]})", "invalid start time (${parts[0]})"))
                val step = parseLong(parts[1]) ?: throw badParam(r, I18n.t("步长必须是整数(分钟)", "step must be an integer (minutes)"))
                formatDateTime(start.plusMinutes(rowIndex * step))
            }

            RuleType.NOW -> {
                val offset = p.ifBlank { "0" }.let {
                    parseLong(it) ?: throw badParam(r, I18n.t("偏移天数必须是整数", "offset days must be an integer"))
                }
                formatDateTime(LocalDateTime.now().plusDays(offset))
            }

            RuleType.ID_CARD -> idCard()
            RuleType.IP -> "${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(256)}.${1 + random.nextInt(254)}"
            RuleType.MAC -> mac()
            RuleType.URL -> url(p)
            RuleType.BANK_CARD -> bankCard()
            RuleType.PLATE_NUMBER -> plateNumber(p)
            RuleType.COLOR_HEX -> "#%06X".format(random.nextInt(0x1000000))

            RuleType.SQL_EXPRESSION -> {
                if (p.isBlank()) throw badParam(r, I18n.t("表达式不能为空", "expression cannot be empty"))
                RawSqlLiteral(p)
            }

            RuleType.SKIP -> null
        }
    }

    // ---------- 类型转换 ----------

    private fun coerce(value: Any?, col: ColumnMeta, rule: RuleConfig): Any? {
        if (value == null) return null
        // SQL 表达式原样交给数据库,不参与 JDBC 类型绑定
        if (value is RawSqlLiteral) return value
        try {
            return when (col.jdbcType) {
                Types.TINYINT, Types.SMALLINT, Types.INTEGER -> toLong(value, rule)?.toInt()
                Types.BIGINT -> toLong(value, rule)
                Types.FLOAT, Types.REAL, Types.DOUBLE -> toDouble(value, rule)
                Types.DECIMAL, Types.NUMERIC ->
                    BigDecimal.valueOf(toDouble(value, rule))
                        .setScale(maxOf(col.decimalDigits, 0), RoundingMode.HALF_UP)
                Types.BOOLEAN -> toBool(value, rule)
                Types.BIT -> if (RuleSuggester.isBoolType(col)) toBool(value, rule) else if (toBool(value, rule)) 1L else 0L
                Types.DATE -> java.sql.Date.valueOf(value.toString().take(10))
                Types.TIME -> java.sql.Time.valueOf(expandTime(value.toString()))
                Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.valueOf(expandDateTime(value.toString()))
                Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB ->
                    truncate(value.toString(), col)
                else -> value
            }
        } catch (e: MockRuleException) {
            throw e
        } catch (e: Exception) {
            throw MockRuleException(
                I18n.t(
                    "列 [${col.name}](${col.typeName}) 使用规则「${rule.type.display}」生成的值" +
                        "「$value」无法转换为数据库类型:${e.message}",
                    "Column [${col.name}](${col.typeName}) value \"$value\" generated by " +
                        "\"${rule.type.display}\" cannot be converted to the column type: ${e.message}",
                ),
            )
        }
    }

    private fun truncate(s: String, col: ColumnMeta): String {
        val limit = if (col.columnSize > 0) col.columnSize else s.length
        // 字符数与字节数可能有差异,粗略按字符数截断,再留一点余量
        return if (s.length > limit) s.take(limit) else s
    }

    private fun toLong(v: Any, rule: RuleConfig): Long = when (v) {
        is Number -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v.toLongOrNull() ?: throw MockRuleException(
            I18n.t(
                "列使用规则「${rule.type.display}」生成了非数字值「$v」",
                "Rule \"${rule.type.display}\" produced a non-numeric value \"$v\"",
            ),
        )

        else -> throw MockRuleException(
            I18n.t("无法把 ${v::class.simpleName} 转为数字", "Cannot convert ${v::class.simpleName} to a number"),
        )
    }

    private fun toDouble(v: Any, rule: RuleConfig): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: throw MockRuleException(
            I18n.t(
                "列使用规则「${rule.type.display}」生成了非数字值「$v」",
                "Rule \"${rule.type.display}\" produced a non-numeric value \"$v\"",
            ),
        )

        else -> throw MockRuleException(
            I18n.t("无法把 ${v::class.simpleName} 转为小数", "Cannot convert ${v::class.simpleName} to a decimal"),
        )
    }

    private fun toBool(v: Any, rule: RuleConfig): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toLong() != 0L
        is String -> v.equals("true", true) || v == "1" || v.equals("是", true) || v.equals("Y", true)
        else -> throw MockRuleException(
            I18n.t("无法把 ${v::class.simpleName} 转为布尔值", "Cannot convert ${v::class.simpleName} to a boolean"),
        )
    }

    // ---------- 字库 ----------

    private val surnames = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳唐罗薛雷贺倪汤滕殷常乐皮卞齐康伍余元卜顾孟平黄穆萧尹姚邵湛汪祁毛狄米贝明臧计伏成戴宋庞熊纪舒屈项祝董梁".toCharArray()
    private val givenChars = "芳华明建国志强伟秀英磊鑫浩然宇轩梓涵雨欣怡佳静文博雅婷玉兰桂香梅凤洁军平保世永康福德喜庆吉祥安顺和富贵荣昌盛兴隆发达".toCharArray()

    private val firstNames = listOf("James", "Mary", "John", "Linda", "Michael", "Emma", "David", "Sophia", "Daniel", "Olivia", "Kevin", "Grace", "Eric", "Lily", "Tony", "Ivy")
    private val lastNames = listOf("Smith", "Johnson", "Brown", "Davis", "Wilson", "Taylor", "Thomas", "Lee", "Clark", "Hall", "King", "Wright", "Scott", "Green")

    private val emailDomains = listOf("gmail.com", "qq.com", "163.com", "outlook.com", "126.com", "foxmail.com")

    // 行政区划数据统一放在 MockDictionaries,便于扩充与单测
    private val provinces: List<String> = MockDictionaries.provinces
    private val cityToProvince = MockDictionaries.cityToProvince
    private val cities: List<String> = cityToProvince.keys.toList()

    private val jobTitles = listOf(
        "软件工程师", "高级软件工程师", "架构师", "技术总监", "产品经理", "项目经理", "测试工程师",
        "运维工程师", "数据分析师", "算法工程师", "销售经理", "市场专员", "人力资源专员", "财务主管",
        "客户成功经理", "前端开发工程师", "后端开发工程师", "数据库管理员", "实施顾问", "解决方案专家",
    )

    private val departments = listOf(
        "研发中心", "技术部", "产品部", "测试部", "运维部", "市场部", "销售部", "人力资源部",
        "财务部", "法务部", "客户服务部", "供应链管理部", "战略投资部", "行政部", "数据智能部",
    )

    private val universities = listOf(
        "北京大学", "清华大学", "复旦大学", "上海交通大学", "浙江大学", "南京大学", "中国科学技术大学",
        "武汉大学", "华中科技大学", "中山大学", "四川大学", "西安交通大学", "哈尔滨工业大学",
        "同济大学", "南开大学", "天津大学", "厦门大学", "山东大学", "吉林大学", "中国人民大学",
    )

    private val companyWords = listOf("科技", "网络", "信息", "软件", "数据", "智能", "传媒", "贸易", "实业", "教育", "医疗", "环保")
    private val companyNames = listOf("云", "星辰", "蓝天", "智联", "畅想", "卓越", "启航", "宏图", "瑞祥", "博远", "天翼", "恒信")

    private val phonePrefixes = listOf("130", "131", "132", "133", "135", "136", "137", "138", "139", "150", "151", "152", "155", "158", "166", "170", "176", "177", "178", "180", "181", "185", "186", "187", "188", "189", "191", "199")
    private val plateProvinces = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼".toCharArray()

    // ---------- 各规则实现 ----------

    private fun pick(list: List<String>): String = list[random.nextInt(list.size)]

    private fun chineseName(): String {
        val sb = StringBuilder().append(surnames[random.nextInt(surnames.size)])
        val n = 1 + random.nextInt(2)
        repeat(n) { sb.append(givenChars[random.nextInt(givenChars.size)]) }
        return sb.toString()
    }

    private fun englishName() = "${pick(firstNames)} ${pick(lastNames)}"

    private fun phone(prefixParam: String): String {
        val prefix = when {
            prefixParam.isBlank() -> pick(phonePrefixes)
            else -> prefixParam.substringBefore(',').trim()
        }
        // 号段 3 位 + 后 8 位 = 11 位手机号
        return prefix + (10_000_000L + random.nextLong(90_000_000L)).toString()
    }

    private fun email(domainParam: String): String {
        val domain = domainParam.ifBlank { pick(emailDomains) }
        return lowerLetters(5 + random.nextInt(8)) + random.nextInt(999) + "@" + domain
    }

    private fun username(prefix: String) = prefix + lowerLetters(4 + random.nextInt(5)) + random.nextInt(100)

    private fun wechat(prefix: String): String =
        (prefix.ifBlank { lowerLetters(1) }) + lowerLetters(6 + random.nextInt(7)) + random.nextInt(100)

    private fun qq(): String {
        val len = 5 + random.nextInt(7)
        val sb = StringBuilder((1 + random.nextInt(9)).toString())
        repeat(len - 1) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    private fun password(r: RuleConfig, param: String): String {
        val parts = csv(param.ifBlank { "12,all" })
        val len = (parseLong(parts[0]) ?: throw badParam(r, "长度必须是整数")).coerceIn(4, 128).toInt()
        val pool = when (parts.getOrNull(1).orEmpty().ifBlank { "all" }) {
            "num" -> "0123456789"
            "en" -> lowerLetters(26) + lowerLetters(26).uppercase()
            "en_num" -> "0123456789" + lowerLetters(26) + lowerLetters(26).uppercase()
            else -> "0123456789" + lowerLetters(26) + lowerLetters(26).uppercase() + "!@#$%^&*()-_=+[]{}"
        }
        val sb = StringBuilder()
        repeat(len) { sb.append(pool[random.nextInt(pool.length)]) }
        return sb.toString()
    }

    private fun address(param: String): String {
        val city = pick(MockDictionaries.districtCities)
        val region = MockDictionaries.regionPrefix(city)
        val district = pick(MockDictionaries.districtsOf(city))
        val street = pick(roads) + (1 + random.nextInt(999)) + "号"
        val building = if (random.nextBoolean()) "某某大厦${random.nextInt(30) + 1}层"
        else "某某小区${random.nextInt(20) + 1}栋${random.nextInt(6) + 1}单元"
        return when {
            param.contains("街道") -> street + building
            param.contains("省市区") || param.contains("行政区") -> region + district
            else -> region + district + street + building
        }
    }

    private val roads = listOf("人民路", "中山路", "解放路", "建设路", "和平路", "科技路", "文化路", "长江路", "黄河路", "望江路")

    private fun company(): String {
        val head = pick(provinces).dropLast(1).take(2)
        return head + pick(companyNames) + pick(companyWords) + "有限公司"
    }

    /** 省份:完整名称 / 简称 */
    private fun province(param: String): String {
        val p = pick(provinces)
        return if (param.contains("简称")) MockDictionaries.shortProvince(p) else p
    }

    /** 区县:仅区县 / 带城市 */
    private fun district(param: String): String {
        val city = pick(MockDictionaries.districtCities)
        val d = pick(MockDictionaries.districtsOf(city))
        return if (param.contains("带")) city + d else d
    }

    /** 品牌名称:按品类取;「全部」/未知品类在全量品牌中随机 */
    private fun brand(param: String): String =
        pick(MockDictionaries.categoriesOf(param).flatMap { it.brands })

    /** 产品名称:品类 + 可选品牌前缀 */
    private fun productName(param: String): String {
        val parts = csv(param)
        val cats = MockDictionaries.categoriesOf(parts.getOrNull(0).orEmpty())
        val product = pick(cats.flatMap { it.products })
        // 注意:「不带品牌」也包含「带品牌」子串,必须先排除「不」
        val prefix = parts.getOrNull(1).orEmpty()
        val withBrand = prefix.contains("带品牌") && !prefix.contains("不")
        return if (withBrand) pick(cats.flatMap { it.brands }) + product else product
    }

    /** 产品类别:一级类目 / 二级类目 / 完整路径(一级-二级) */
    private fun productCategory(param: String): String {
        val parts = csv(param)
        val level = parts.getOrNull(0).orEmpty().ifBlank { "二级类目" }
        val cats = MockDictionaries.categoriesOf(parts.getOrNull(1).orEmpty())
        return when {
            level.contains("一级") -> pick(cats.map { it.label })
            level.contains("完整") || level.contains("路径") -> {
                val c = cats[random.nextInt(cats.size)]
                "${c.label}-${pick(c.subCategories)}"
            }

            else -> pick(cats.flatMap { it.subCategories })
        }
    }

    private fun text(minLen: Int, maxLen: Int, charset: String): String {
        val pool = when (charset) {
            "en" -> englishLetters
            "num" -> digits
            "en_num" -> englishLetters + digits
            else -> String(givenChars) + englishLetters
        }
        var len = if (maxLen <= minLen) minLen else minLen + random.nextInt(maxLen - minLen + 1)
        if (len < 1) len = 1
        val sb = StringBuilder()
        repeat(len) { sb.append(pool[random.nextInt(pool.length)]) }
        return sb.toString()
    }

    private fun lowerLetters(n: Int) = buildString { repeat(n) { append('a' + random.nextInt(26)) } }

    private fun mac(): String = (0 until 6).joinToString(":") { "%02X".format(random.nextInt(256)) }

    private fun url(domainParam: String): String {
        val host = domainParam.ifBlank { "www.${lowerLetters(6 + random.nextInt(6))}.com" }
        return "https://$host/${lowerLetters(4 + random.nextInt(8))}"
    }

    private fun bankCard(): String {
        val bins = listOf("622202", "621226", "622848", "621661", "622588", "622609", "621700", "622700", "621559")
        val payload = StringBuilder(pick(bins))
        repeat(9) { payload.append(random.nextInt(10)) }
        return payload.toString() + luhnCheckDigit(payload.toString())
    }

    private fun luhnCheckDigit(payload: String): Char {
        var sum = 0
        var double = true
        for (i in payload.indices.reversed()) {
            var d = payload[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return ('0' + (10 - sum % 10) % 10)
    }

    private fun plateNumber(prefixParam: String): String {
        val prefix = if (prefixParam.isNotBlank()) prefixParam.trim().take(2)
        else plateProvinces[random.nextInt(plateProvinces.size)].toString() + ('A' + random.nextInt(26))
        val bodyPool = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val body = buildString { repeat(5) { append(bodyPool[random.nextInt(bodyPool.length)]) } }
        return prefix + body
    }

    private fun idCard(): String {
        val areas = listOf("110101", "310104", "440305", "330106", "510107", "420102", "610102", "320102")
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        val codes = "10X98765432"
        val area = pick(areas)
        val year = 1960 + random.nextInt(46)
        val month = 1 + random.nextInt(12)
        val day = 1 + random.nextInt(27)
        val seq = "%03d".format(random.nextInt(1000))
        val body = "$area$year%02d%02d$seq".format(month, day)
        var sum = 0
        body.forEachIndexed { i, c -> sum += (c - '0') * weights[i] }
        return body + codes[sum % 11]
    }

    // ---------- 时间 ----------

    private fun randomInstant(r: RuleConfig): LocalDateTime {
        val parts = csv(r.param)
        if (parts.size < 2) throw badParam(r, "参数格式:开始时间,结束时间")
        val start = parseDateTimeFlexible(parts[0]) ?: throw badParam(r, "开始时间不合法(${parts[0]})")
        val end = parseDateTimeFlexible(parts[1]) ?: throw badParam(r, "结束时间不合法(${parts[1]})")
        if (end.isBefore(start)) throw badParam(r, "结束时间不能早于开始时间")
        val seconds = java.time.Duration.between(start, end).seconds
        if (seconds <= 0) return start
        return start.plusSeconds(abs(random.nextLong()) % (seconds + 1))
    }

    private fun randomClockSeconds(r: RuleConfig): Int {
        val parts = csv(r.param)
        if (parts.size < 2) throw badParam(r, "参数格式:开始时间,结束时间")
        val start = parseClockSeconds(parts[0]) ?: throw badParam(r, "开始时间不合法(${parts[0]})")
        val end = parseClockSeconds(parts[1]) ?: throw badParam(r, "结束时间不合法(${parts[1]})")
        if (end < start) throw badParam(r, "结束时间不能早于开始时间")
        return start + random.nextInt(end - start + 1)
    }

    private fun formatDate(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    private fun formatTime(sec: Int): String = "%02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    private fun formatDateTime(t: LocalDateTime): String = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    private fun parseDateTimeFlexible(s: String): LocalDateTime? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            when {
                t.contains(':') -> LocalDateTime.parse(
                    if (t.length <= 16) "${t.take(16).replace(' ', 'T')}:00" else t.take(19).replace(' ', 'T'),
                )

                else -> LocalDate.parse(t.take(10)).atStartOfDay()
            }
        } catch (_: Exception) {
            // 退回旧解析器,容忍 "2020/01/01" 之类写法
            try {
                val fmt = if (t.length <= 10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss"
                SimpleDateFormat(fmt).parse(t)?.let { LocalDateTime.ofInstant(it.toInstant(), java.time.ZoneId.systemDefault()) }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseClockSeconds(s: String): Int? {
        val parts = s.trim().split(':')
        if (parts.size < 2 || parts.size > 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val sec = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59 || sec !in 0..59) return null
        return h * 3600 + m * 60 + sec
    }

    // ---------- 工具 ----------

    private fun enumValues(r: RuleConfig): List<String> {
        val list = r.param.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) {
            throw badParam(r, I18n.t("候选值不能为空,请在参数中配置枚举值", "candidates cannot be empty — configure them in the parameter"))
        }
        return list
    }

    private fun expandDateTime(s: String): String = if (s.length <= 10) "$s 00:00:00" else s.take(19)

    private fun expandTime(s: String): String =
        when {
            s.contains(':') && s.split(':').size == 2 -> "$s:00"
            s.contains(':') -> s
            else -> "00:00:00"
        }

    private fun randomLong(min: Long, max: Long): Long {
        if (min == max) return min
        val range = max - min
        return if (range < Int.MAX_VALUE) min + random.nextInt(range.toInt() + 1)
        else min + abs(random.nextLong()) % (range + 1)
    }

    private fun twoLongs(r: RuleConfig): Pair<Long, Long> {
        val parts = csv(r.param)
        if (parts.size < 2) {
            throw badParam(r, I18n.t("参数格式:两个以逗号分隔的数字", "format: two numbers separated by a comma"))
        }
        val a = parseLong(parts[0]) ?: throw badParam(r, I18n.t("「${parts[0]}」不是数字", "\"${parts[0]}\" is not a number"))
        val b = parseLong(parts[1]) ?: throw badParam(r, I18n.t("「${parts[1]}」不是数字", "\"${parts[1]}\" is not a number"))
        return a to b
    }

    private fun csv(s: String): List<String> = s.split(',').map { it.trim() }

    private fun parseLong(s: String): Long? = s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
    private fun parseDouble(s: String): Double? = s.toDoubleOrNull()

    private fun badParam(r: RuleConfig, why: String) = MockRuleException(
        I18n.t(
            "列 [${r.column.name}] 规则「${r.type.display}」参数不合法:$why(当前参数:${r.param})",
            "Column [${r.column.name}] rule \"${r.type.display}\": invalid parameter — $why (current: ${r.param})",
        ),
    )

    private companion object {
        const val englishLetters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val digits = "0123456789"
    }

    /**
     * 把生成的值转为 SQL 字面量 —— **仅用于预览展示**,实际插入走绑定参数。
     *
     * @param mysql MySQL 默认把反斜杠当转义字符,只翻倍单引号挡不住逃逸
     *              (值 `a\' OR 1=1 --` 会提前结束字符串),需要额外转义反斜杠
     */
    fun toSqlLiteral(v: Any?, mysql: Boolean = false): String = when (v) {
        null -> "NULL"
        is RawSqlLiteral -> v.sql
        is Number, is Boolean -> v.toString()
        else -> {
            var s = v.toString().replace("'", "''")
            if (mysql) s = s.replace("\\", "\\\\")
            "'$s'"
        }
    }
}
