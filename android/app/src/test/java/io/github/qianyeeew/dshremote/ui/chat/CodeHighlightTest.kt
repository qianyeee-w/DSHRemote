package io.github.qianyeeew.dshremote.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeHighlightTest {
    private fun kinds(
        code: String,
        language: String?,
    ): List<Pair<String, TokenKind>> = codeTokens(code, language).map { code.substring(it.start, it.end) to it.kind }

    @Test
    fun keywordsStringsAndComments() {
        assertEquals(
            listOf(
                "suspend" to TokenKind.KEYWORD,
                "fun" to TokenKind.KEYWORD,
                "val" to TokenKind.KEYWORD,
                "\"host:\$id\"" to TokenKind.STRING,
                "// 去重" to TokenKind.COMMENT,
            ),
            kinds("suspend fun key() {\n    val k = \"host:\$id\" // 去重\n}", "kotlin"),
        )
    }

    @Test
    fun keywordsInsideStringsAndCommentsAreNotHighlighted() {
        assertEquals(
            listOf("\"if else\"" to TokenKind.STRING, "/* return */" to TokenKind.COMMENT),
            kinds("\"if else\" /* return */", "js"),
        )
    }

    @Test
    fun hashCommentsOnlyForHashLanguages() {
        assertEquals(listOf("# 安装" to TokenKind.COMMENT), kinds("# 安装\nnpm ci", "bash"))
        // C 的预处理指令、不知道语言时都不当注释
        assertEquals(emptyList<Pair<String, TokenKind>>(), kinds("#include <stdio.h>", "c"))
        assertEquals(emptyList<Pair<String, TokenKind>>(), kinds("# 标题", null))
        // shell 里的 $# 不是注释
        assertEquals(emptyList<Pair<String, TokenKind>>(), kinds("echo \$#", "sh"))
    }

    @Test
    fun urlsInPythonAreNotCommentsButSlashesAre() {
        assertEquals(listOf("# x" to TokenKind.COMMENT), kinds("a // b # x", "python"))
    }

    @Test
    fun unclosedQuoteStopsAtEndOfLine() {
        assertEquals(listOf("'t work" to TokenKind.STRING, "if" to TokenKind.KEYWORD), kinds("don't work\nif", "kotlin"))
    }

    @Test
    fun escapesAndTripleQuotes() {
        assertEquals(listOf("\"a\\\"b\"" to TokenKind.STRING), kinds("\"a\\\"b\"", "kotlin"))
        assertEquals(listOf("\"\"\"x\ny\"\"\"" to TokenKind.STRING), kinds("\"\"\"x\ny\"\"\"", "python"))
    }

    @Test
    fun plainTextAndNumbersAreNotHighlighted() {
        assertEquals(emptyList<Pair<String, TokenKind>>(), kinds("don't \"quote\" if", "text"))
        assertEquals(emptyList<Pair<String, TokenKind>>(), kinds("0x1F 1000L 3in", "kotlin"))
    }

    @Test
    fun sqlKeywordsAndDashComments() {
        assertEquals(
            listOf("SELECT" to TokenKind.KEYWORD, "FROM" to TokenKind.KEYWORD, "-- all" to TokenKind.COMMENT),
            kinds("SELECT * FROM t -- all", "sql"),
        )
    }
}
