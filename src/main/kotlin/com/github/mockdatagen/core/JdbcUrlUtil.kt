package com.github.mockdatagen.core

/**
 * JDBC URL 的库名改写。
 *
 * 背景:一个 DataGrip 数据源可以内省同一台服务器上的多个数据库。例如 PostgreSQL 数据源的
 * URL 是 `jdbc:postgresql://localhost:5432/postgres`,但 introspection scope 同时包含
 * `uni_grid`。此时表模型来自 `uni_grid`,而数据源默认连接落在 `postgres`,
 * 插入就会报「关系 xxx 不存在」——表和连接根本不在同一个库里。
 *
 * 首选方案是平台 API `JdbcUrlParserUtil.connectedTo(point, path)`,本对象作为确定性兜底,
 * 只在能明确判断「当前库名 != 目标库名」时才改写,其余情况一律返回 null(保持原 URL)。
 */
object JdbcUrlUtil {

    /** SQL Server 风格 URL 的库名参数 */
    private const val MSSQL_DB_PARAM = ";databaseName="

    /** 改写后的库名位置(SQL Server 用参数形式,其余用路径形式) */
    private data class Slot(val start: Int, val end: Int, val current: String?)

    /**
     * 把 JDBC URL 指向指定数据库,返回改写后的 URL。
     * 无法安全改写(形如 `jdbc:oracle:thin:@host:1521:sid` 无 `//`、多层路径、已是目标库)时返回 null。
     */
    fun withDatabase(url: String?, database: String?): String? {
        if (url.isNullOrBlank() || database.isNullOrBlank()) return null
        val slot = locate(url) ?: return null
        if (slot.current != null && slot.current.equals(database, ignoreCase = true)) return null
        return url.substring(0, slot.start) + encode(database) + url.substring(slot.end)
    }

    /** 取 URL 中当前库名,仅用于日志与诊断 */
    fun databaseOf(url: String?): String? = url?.let { locate(it)?.current }

    /**
     * 定位 URL 中「库名」所占的区间。
     * 返回 null 表示无法确定语义,调用方不应改写。
     */
    private fun locate(url: String): Slot? {
        val mssql = url.indexOf(MSSQL_DB_PARAM, ignoreCase = true)
        if (mssql >= 0) {
            val start = mssql + MSSQL_DB_PARAM.length
            val end = url.indexOf(';', start).let { if (it < 0) url.length else it }
            return Slot(start, end, url.substring(start, end).takeIf { it.isNotBlank() })
        }

        val schemeSep = url.indexOf("://")
        if (schemeSep < 0) return null
        val bodyStart = schemeSep + 3

        val queryIdx = url.indexOf('?', bodyStart)
        val bodyEnd = if (queryIdx >= 0) queryIdx else url.length
        val slash = url.indexOf('/', bodyStart)
        if (slash < 0 || slash >= bodyEnd) return null // 没有路径段:库名位置不确定,不改写

        val rest = url.substring(slash + 1, bodyEnd)
        if (rest.contains('/')) return null // 多层路径(如 jdbc:h2:tcp://host/~/db),语义不确定
        return Slot(slash + 1, bodyEnd, rest.takeIf { it.isNotBlank() })
    }

    private fun encode(value: String): String = buildString {
        for (ch in value) {
            val safe = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '$' || ch == '~'
            if (safe) {
                append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    append('%')
                    append(HEX[(b.toInt() shr 4) and 0xF])
                    append(HEX[b.toInt() and 0xF])
                }
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
