package com.github.mockdatagen

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MockDataBundle"

/**
 * 插件级文案(菜单项、动作描述)。
 *
 * 跟随 IDE 语言自动解析(`messages/MockDataBundle*.properties`),
 * 与对话框内的手动语言切换相互独立 —— 后者只管生成器界面本身。
 */
object MockDataBundle : DynamicBundle(BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
