package com.github.mockdatagen.core

/** 界面语言 */
enum class Lang(val code: String, val label: String) {
    ZH("zh", "中文"),
    EN("en", "English"),
}

/**
 * 极简 i18n:不做资源文件与 key 管理,调用点直接写两份文案。
 *
 * 纯逻辑(不依赖平台),可被冒烟测试直接覆盖。
 *
 * 两条约定:
 * 1. **参数值**(如「带省份」「家电类」「完整地址」)是内部编码,由 [ParamChoice.code] 承载,
 *    **不参与翻译** —— 只翻译给人看的 [ParamChoice.label]。
 * 2. 数据本体(城市名、公司名、产品名)不翻译:它们生成出来的就是中文数据。
 */
object I18n {

    var lang: Lang = Lang.ZH

    fun t(zh: String, en: String): String = if (lang == Lang.ZH) zh else en

    fun isZh(): Boolean = lang == Lang.ZH

    /** 数字格式化:中文按千分位,英文同(便于对齐读数) */
    fun num(n: Long): String = if (lang == Lang.ZH) String.format("%,d", n) else String.format("%,d", n)
}
