package com.github.mockdatagen.core

import java.util.Random

/**
 * 极简「正则模板 → 随机串」生成器,用于 [RuleType.PATTERN]。
 *
 * 支持:字面量、转义(\d \w \s \t \n 及 \x 转义)、字符类 [a-z0-9_]、量词 {n} {n,} {n,m} * + ?、
 * 分组 () 与分支 |、通配 . 。不支持前后瞻/反向引用等高级语法。
 * 生成结果长度上限 [MAX_LENGTH],防止 {100000} 之类模板打爆内存。
 */
object RegexRandom {

    fun generate(pattern: String, random: Random): String {
        val parser = Parser(pattern)
        val node = parser.parseExpression()
        parser.expectEnd()
        val sb = StringBuilder()
        node.render(sb, random)
        return sb.toString()
    }

    private interface Node {
        fun render(sb: StringBuilder, random: Random)
    }

    private class Literal(private val text: String) : Node {
        override fun render(sb: StringBuilder, random: Random) {
            sb.append(text)
            checkLength(sb)
        }
    }

    private class Pick(private val chars: String) : Node {
        override fun render(sb: StringBuilder, random: Random) {
            sb.append(chars[random.nextInt(chars.length)])
            checkLength(sb)
        }
    }

    private class Sequence(private val nodes: List<Node>) : Node {
        override fun render(sb: StringBuilder, random: Random) = nodes.forEach { it.render(sb, random) }
    }

    private class Alternate(private val branches: List<Node>) : Node {
        override fun render(sb: StringBuilder, random: Random) =
            branches[random.nextInt(branches.size)].render(sb, random)
    }

    private class Repeat(private val node: Node, private val min: Int, private val max: Int) : Node {
        override fun render(sb: StringBuilder, random: Random) {
            val n = if (max <= min) min else min + random.nextInt(max - min + 1)
            repeat(n) { node.render(sb, random) }
        }
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun expectEnd() {
            if (pos < src.length) {
                throw MockRuleException("正则模板存在无法解析的部分「${src.substring(pos)}」")
            }
        }

        fun parseExpression(): Node {
            val branches = ArrayList<Node>()
            branches.add(parseConcat())
            while (pos < src.length && src[pos] == '|') {
                pos++
                branches.add(parseConcat())
            }
            return if (branches.size == 1) branches[0] else Alternate(branches)
        }

        private fun parseConcat(): Node {
            val items = ArrayList<Node>()
            while (pos < src.length && src[pos] != '|' && src[pos] != ')') {
                items.add(parseQuantified())
            }
            return when (items.size) {
                0 -> Literal("")
                1 -> items[0]
                else -> Sequence(items)
            }
        }

        private fun parseQuantified(): Node {
            val atom = parseAtom()
            if (pos >= src.length) return atom
            return when (src[pos]) {
                '*' -> { pos++; Repeat(atom, 0, UNBOUNDED_MAX) }
                '+' -> { pos++; Repeat(atom, 1, UNBOUNDED_MAX + 1) }
                '?' -> { pos++; Repeat(atom, 0, 1) }
                '{' -> parseBrace(atom)
                else -> atom
            }
        }

        private fun parseBrace(atom: Node): Node {
            val close = src.indexOf('}', pos)
            if (close < 0) throw MockRuleException("正则模板量词缺少右花括号:{...}")
            val body = src.substring(pos + 1, close).trim()
            pos = close + 1
            val parts = body.split(',')
            val min = parts[0].trim().toIntOrNull()
                ?: throw MockRuleException("正则模板量词不合法:{$body}")
            val max = when {
                parts.size == 1 -> min
                parts[1].isBlank() -> maxOf(min, UNBOUNDED_MAX)
                else -> parts[1].trim().toIntOrNull()
                    ?: throw MockRuleException("正则模板量词不合法:{$body}")
            }
            if (min < 0 || max < min) throw MockRuleException("正则模板量词范围不合法:{$body}")
            if (max > MAX_LENGTH) throw MockRuleException("正则模板量词过大:{$body}(上限 $MAX_LENGTH)")
            return Repeat(atom, min, max)
        }

        private fun parseAtom(): Node {
            if (pos >= src.length) throw MockRuleException("正则模板意外结束")
            val c = src[pos]
            return when (c) {
                '\\' -> { pos++; parseEscape() }
                '[' -> parseClass()
                '(' -> {
                    pos++
                    val inner = parseExpression()
                    if (pos >= src.length || src[pos] != ')') throw MockRuleException("正则模板括号不匹配,缺少 )")
                    pos++
                    inner
                }
                '.' -> { pos++; Pick(PRINTABLE) }
                else -> { pos++; Literal(c.toString()) }
            }
        }

        private fun parseEscape(): Node {
            if (pos >= src.length) throw MockRuleException("正则模板以反斜杠结尾")
            val c = src[pos++]
            return when (c) {
                'd' -> Pick("0123456789")
                'w' -> Pick("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
                's' -> Pick(" ")
                'n' -> Literal("\n")
                't' -> Literal("\t")
                'r' -> Literal("\r")
                'D', 'W', 'S' -> Pick(PRINTABLE)
                else -> Literal(c.toString())
            }
        }

        private fun parseClass(): Node {
            pos++ // 吃掉 '['
            val negate = pos < src.length && src[pos] == '^'
            if (negate) pos++
            val chars = StringBuilder()
            var closed = false
            while (pos < src.length) {
                val c = src[pos]
                if (c == ']') { closed = true; pos++; break }
                if (c == '\\' && pos + 1 < src.length) {
                    val e = src[pos + 1]
                    pos += 2
                    when (e) {
                        'd' -> chars.append("0123456789")
                        'w' -> chars.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
                        's' -> chars.append(' ')
                        else -> chars.append(e)
                    }
                    continue
                }
                // 区间 a-z
                if (pos + 2 < src.length && src[pos + 1] == '-' && src[pos + 2] != ']') {
                    val from = c
                    val to = src[pos + 2]
                    pos += 3
                    if (to < from) throw MockRuleException("正则模板字符区间不合法:$from-$to")
                    for (ch in from..to) chars.append(ch)
                    continue
                }
                chars.append(c)
                pos++
            }
            if (!closed) throw MockRuleException("正则模板字符类缺少 ]")
            if (chars.isEmpty()) throw MockRuleException("正则模板字符类为空")
            if (!negate) return Pick(chars.toString())
            val excluded = chars.toString().toSet()
            val rest = PRINTABLE.filter { it !in excluded }
            if (rest.isEmpty()) throw MockRuleException("正则模板字符类取反后没有可用字符")
            return Pick(rest)
        }
    }
}

private const val MAX_LENGTH = 4096
private const val UNBOUNDED_MAX = 8
private const val PRINTABLE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private fun checkLength(sb: StringBuilder) {
    if (sb.length > MAX_LENGTH) {
        throw MockRuleException("正则模板生成结果超过 $MAX_LENGTH 字符,请检查量词设置")
    }
}
