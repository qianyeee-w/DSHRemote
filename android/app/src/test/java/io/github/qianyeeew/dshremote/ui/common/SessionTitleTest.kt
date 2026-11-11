package io.github.qianyeeew.dshremote.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 会话标题去掉开头的 Markdown 标记（M3 T5）。DSH 生成的标题已经压成一行。 */
class SessionTitleTest {
    @Test
    fun stripsHeadingMarkers() {
        assertEquals("修复登录问题", sessionDisplayTitle("# 修复登录问题"))
        assertEquals("修复登录问题", sessionDisplayTitle("### 修复登录问题"))
        assertEquals("修复登录问题", sessionDisplayTitle("###### 修复登录问题"))
        assertEquals("修复登录问题", sessionDisplayTitle("  #   修复登录问题  "))
        assertEquals("标题 - 第一项 - 第二项", sessionDisplayTitle("## 标题 - 第一项 - 第二项"))
    }

    @Test
    fun stripsQuoteListAndTaskMarkers() {
        assertEquals("引用的内容", sessionDisplayTitle("> 引用的内容"))
        assertEquals("引用的内容", sessionDisplayTitle(">引用的内容"))
        assertEquals("第一项", sessionDisplayTitle("- 第一项"))
        assertEquals("第一项", sessionDisplayTitle("* 第一项"))
        assertEquals("第一项", sessionDisplayTitle("+ 第一项"))
        assertEquals("写测试", sessionDisplayTitle("- [ ] 写测试"))
        assertEquals("写测试", sessionDisplayTitle("- [x] 写测试"))
    }

    @Test
    fun stripsStackedMarkers() {
        assertEquals("任务", sessionDisplayTitle("> - [ ] 任务"))
        assertEquals("嵌套引用", sessionDisplayTitle(">> # 嵌套引用"))
        assertEquals("标题", sessionDisplayTitle("- # 标题"))
    }

    @Test
    fun onlyMarkersOrBlankIsUntitled() {
        assertNull(sessionDisplayTitle(null))
        assertNull(sessionDisplayTitle(""))
        assertNull(sessionDisplayTitle("   "))
        assertNull(sessionDisplayTitle("#"))
        assertNull(sessionDisplayTitle("# "))
        assertNull(sessionDisplayTitle("## > -"))
        assertNull(sessionDisplayTitle("- [ ]"))
    }

    @Test
    fun leavesNonMarkersAlone() {
        // 正文中间的符号不动
        assertEquals("修复 #123 和 > 号的问题", sessionDisplayTitle("修复 #123 和 > 号的问题"))
        assertEquals("C# 项目 - 重构", sessionDisplayTitle("C# 项目 - 重构"))
        // 开头不是块标记的（CommonMark 中 # 和列表符号后面要有空白）
        assertEquals("#123 修复", sessionDisplayTitle("#123 修复"))
        assertEquals("*强调* 的开头", sessionDisplayTitle("*强调* 的开头"))
        assertEquals("--- 分隔线", sessionDisplayTitle("--- 分隔线"))
        assertEquals("####### 七个井号不是标题", sessionDisplayTitle("####### 七个井号不是标题"))
        assertEquals("[链接](https://example.com)", sessionDisplayTitle("[链接](https://example.com)"))
        assertEquals("1. 有序列表保留序号", sessionDisplayTitle("1. 有序列表保留序号"))
        assertEquals("-5 度", sessionDisplayTitle("-5 度"))
    }
}
