package io.github.qianyeeew.dshremote.ui.chat

/**
 * 代码块的高亮只有三种区别（设计规格 6.5）：关键字加粗、字符串、注释。不追求按语言精确解析，
 * 只从左到右扫一遍：先认注释和字符串（里面的内容不再当关键字），再认标识符里的关键字。
 */
enum class TokenKind { KEYWORD, STRING, COMMENT }

data class CodeToken(
    val start: Int,
    val end: Int,
    val kind: TokenKind,
)

/** 用 `#` 开头写注释的语言。不知道语言时不认 `#`，免得把 C 的 `#include`、Markdown 标题当成注释。 */
private val HASH_COMMENT =
    setOf(
        "python",
        "py",
        "sh",
        "bash",
        "shell",
        "zsh",
        "console",
        "yaml",
        "yml",
        "toml",
        "ruby",
        "rb",
        "perl",
        "r",
        "powershell",
        "ps1",
        "pwsh",
        "dockerfile",
        "makefile",
        "make",
        "conf",
        "ini",
        "properties",
        "nix",
        "elixir",
    )

/** 纯文本、日志：不高亮。 */
private val PLAIN = setOf("text", "txt", "plaintext", "plain", "log", "output", "diff")

/** 用 `--` 开头写注释的语言。 */
private val DASH_COMMENT = setOf("sql", "lua", "haskell", "hs")

/** 不用 `//` 和 `/* */` 写注释的语言。 */
private val NO_C_COMMENT = HASH_COMMENT + DASH_COMMENT + setOf("json", "html", "xml", "markdown", "md")

private val KEYWORDS =
    setOf(
        // Kotlin、Java、Swift、C 系
        "fun",
        "val",
        "var",
        "class",
        "object",
        "interface",
        "if",
        "else",
        "when",
        "for",
        "while",
        "do",
        "return",
        "break",
        "continue",
        "try",
        "catch",
        "finally",
        "throw",
        "throws",
        "import",
        "package",
        "suspend",
        "private",
        "public",
        "protected",
        "internal",
        "override",
        "open",
        "abstract",
        "data",
        "sealed",
        "enum",
        "const",
        "static",
        "final",
        "void",
        "new",
        "this",
        "super",
        "null",
        "true",
        "false",
        "in",
        "is",
        "as",
        "typealias",
        "companion",
        "init",
        "struct",
        "union",
        "typedef",
        "sizeof",
        "switch",
        "case",
        "default",
        "goto",
        "extern",
        "inline",
        "namespace",
        "template",
        "typename",
        "using",
        "virtual",
        "operator",
        "nullptr",
        "let",
        "guard",
        "func",
        "self",
        "Self",
        // JavaScript、TypeScript
        "function",
        "export",
        "from",
        "extends",
        "implements",
        "typeof",
        "instanceof",
        "async",
        "await",
        "yield",
        "delete",
        "undefined",
        "type",
        "declare",
        "readonly",
        "keyof",
        // Python
        "def",
        "lambda",
        "pass",
        "with",
        "elif",
        "except",
        "raise",
        "global",
        "nonlocal",
        "and",
        "or",
        "not",
        "None",
        "True",
        "False",
        "assert",
        // Rust、Go
        "fn",
        "mut",
        "impl",
        "trait",
        "pub",
        "mod",
        "use",
        "match",
        "where",
        "crate",
        "move",
        "ref",
        "loop",
        "go",
        "defer",
        "chan",
        "select",
        "range",
        "map",
        // shell
        "then",
        "fi",
        "esac",
        "done",
        "local",
        "export",
        "function",
        // SQL（只认大写）
        "SELECT",
        "FROM",
        "WHERE",
        "INSERT",
        "INTO",
        "UPDATE",
        "DELETE",
        "JOIN",
        "LEFT",
        "RIGHT",
        "INNER",
        "ON",
        "GROUP",
        "BY",
        "ORDER",
        "LIMIT",
        "CREATE",
        "TABLE",
        "VALUES",
        "SET",
        "AND",
        "OR",
        "NOT",
        "NULL",
        "AS",
    )

fun codeTokens(
    code: String,
    language: String?,
): List<CodeToken> {
    val lang = language?.trim()?.lowercase()
    if (lang in PLAIN) return emptyList()
    val hash = lang in HASH_COMMENT
    val dash = lang in DASH_COMMENT
    val cStyle = lang !in NO_C_COMMENT
    val tokens = ArrayList<CodeToken>()
    var i = 0
    val n = code.length

    fun lineEnd(from: Int): Int = code.indexOf('\n', from).let { if (it < 0) n else it }

    while (i < n) {
        val c = code[i]
        when {
            cStyle && code.startsWith("//", i) -> {
                val end = lineEnd(i)
                tokens += CodeToken(i, end, TokenKind.COMMENT)
                i = end
            }
            cStyle && code.startsWith("/*", i) -> {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) n else close + 2
                tokens += CodeToken(i, end, TokenKind.COMMENT)
                i = end
            }
            hash && c == '#' && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '$') -> {
                val end = lineEnd(i)
                tokens += CodeToken(i, end, TokenKind.COMMENT)
                i = end
            }
            dash && code.startsWith("--", i) -> {
                val end = lineEnd(i)
                tokens += CodeToken(i, end, TokenKind.COMMENT)
                i = end
            }
            code.startsWith("\"\"\"", i) || code.startsWith("'''", i) -> {
                val quote = code.substring(i, i + 3)
                val close = code.indexOf(quote, i + 3)
                val end = if (close < 0) n else close + 3
                tokens += CodeToken(i, end, TokenKind.STRING)
                i = end
            }
            c == '"' || c == '\'' || c == '`' -> {
                val end = stringEnd(code, i, multiline = c == '`')
                tokens += CodeToken(i, end, TokenKind.STRING)
                i = end
            }
            c.isLetter() || c == '_' -> {
                var end = i + 1
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                if (code.substring(i, end) in KEYWORDS) tokens += CodeToken(i, end, TokenKind.KEYWORD)
                i = end
            }
            c.isDigit() -> {
                // 数字里的字母（0x1F、1000L）不当成标识符的开头
                var end = i + 1
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '.')) end++
                i = end
            }
            else -> i++
        }
    }
    return tokens
}

/** 字符串的结尾（不含）。认反斜杠转义；除反引号外，到行尾还没闭合就在行尾结束，免得一个落单的引号把后面全染色。 */
private fun stringEnd(
    code: String,
    start: Int,
    multiline: Boolean,
): Int {
    val quote = code[start]
    var i = start + 1
    while (i < code.length) {
        val c = code[i]
        when {
            c == '\\' -> i += 2
            c == quote -> return i + 1
            c == '\n' && !multiline -> return i
            else -> i++
        }
    }
    return code.length
}
