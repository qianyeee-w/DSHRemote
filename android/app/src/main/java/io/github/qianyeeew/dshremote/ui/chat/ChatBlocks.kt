package io.github.qianyeeew.dshremote.ui.chat

import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutputOmission
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.ThinkingPart
import io.github.qianyeeew.dshremote.data.api.ToolPart
import io.github.qianyeeew.dshremote.data.api.ToolStatus
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 会话页上显示的一块（设计规格 7.2、6.6）。[chatBlocks] 从消息列表算出，[key] 在列表中唯一并且稳定：
 * 流式输出、草稿换成正式消息时不变，界面上就是同一项。
 */
sealed interface ChatBlock {
    val key: String

    /** 用户自己的消息：会话日志里的，或者本地发出、还没出现在日志里的（[local] 不为 null）。 */
    data class User(
        override val key: String,
        val text: String,
        val local: LocalMessage? = null,
    ) : ChatBlock

    /** 一段回复正文（Markdown）。 */
    data class Reply(
        override val key: String,
        val text: String,
    ) : ChatBlock

    /** 一段连续的思考和工具调用，默认折成一行（6.6）。 */
    data class Steps(
        override val key: String,
        val steps: List<Step>,
    ) : ChatBlock {
        /** 改了几个文件：`edit`、`write` 的不同文件路径数。 */
        val changedFiles: Int
            get() =
                steps
                    .filter { it.action == StepAction.EDIT || it.action == StepAction.WRITE }
                    .mapNotNullTo(HashSet()) { it.target }
                    .size
    }

    /** 一轮的结尾（6.7）：结束了的轮次才有，放在这一轮的最后一块下面。 */
    data class TurnEnd(
        override val key: String,
        val turn: Turn,
    ) : ChatBlock
}

/** 步骤行左边的动作（6.6 的对应表）。 */
enum class StepAction { RUN, READ, WRITE, EDIT, SEARCH, OPEN, WEB_SEARCH, THINK, CALL }

enum class StepStatus { RUNNING, DONE, FAILED, REJECTED, UNKNOWN }

/** 步骤行右边的结果，只在数据能确定时有。 */
sealed interface StepResult {
    /** 读取：拿到了多少行。 */
    data class Lines(
        val count: Int,
    ) : StepResult

    /** 修改、写入：增加和删除的行数。 */
    data class Changed(
        val added: Int,
        val removed: Int,
    ) : StepResult
}

/**
 * 一步。[target] 是对象（命令的第一行、文件名、搜索内容……），思考和不认识的对象为 null；
 * 「调用」的对象是工具名。[tool] 和 [thinking] 是展开后的详情。
 */
data class Step(
    val key: String,
    val action: StepAction,
    val target: String?,
    val status: StepStatus,
    val result: StepResult? = null,
    val tool: ToolPart? = null,
    val thinking: String? = null,
)

/**
 * 把消息列表换成显示用的块：用户消息一块；回复里的每段文字一块；文字之间连续的思考和工具调用
 * （可以跨越几条回复消息）合成一块。步骤不跨过用户消息，也不跨过轮次的分界。
 *
 * 消息带轮次编号（protocol.md 5.2）时，[turns] 里已经结束的轮次在它最后一块下面加一个 [ChatBlock.TurnEnd]。
 * 不带编号的消息（旧插件，或者一页开头归属不明的消息）算在前面最近的一轮里。
 */
fun chatBlocks(
    items: List<ChatItem>,
    turns: Map<Int, Turn> = emptyMap(),
): List<ChatBlock> {
    val blocks = ArrayList<ChatBlock>()
    var steps = ArrayList<Step>()
    var stepsKey: String? = null
    var openTurn: Int? = null
    val ended = HashSet<Int>()

    fun flush() {
        val key = stepsKey
        if (key != null && steps.isNotEmpty()) blocks += ChatBlock.Steps(key, steps)
        steps = ArrayList()
        stepsKey = null
    }

    fun closeTurn() {
        val number = openTurn ?: return
        val turn = turns[number]?.takeUnless { it.running } ?: return
        if (!ended.add(number)) return
        flush()
        blocks += ChatBlock.TurnEnd("turn-end:$number", turn)
    }

    fun enter(turn: Int?) {
        if (turn == null || turn == openTurn) return
        flush()
        closeTurn()
        openTurn = turn
    }

    for (item in items) {
        when (item) {
            is ChatItem.Local -> {
                flush()
                // 本地发出的消息在日志里出现之前没有轮次：前一轮已经结束的话，结尾先放在它上面
                closeTurn()
                blocks += ChatBlock.User(item.key, item.local.outgoing.text, item.local)
            }
            is ChatItem.Entry -> {
                val message = item.message
                enter(message.turn)
                if (message.role == MessageRole.USER) {
                    flush()
                    blocks += ChatBlock.User(item.key, message.parts.filterIsInstance<TextPart>().joinToString("") { it.text })
                    continue
                }
                message.parts.forEachIndexed { index, part ->
                    val partKey = "${item.key}:$index"
                    when (part) {
                        is TextPart ->
                            if (part.text.isNotBlank()) {
                                flush()
                                blocks += ChatBlock.Reply(partKey, part.text)
                            }
                        is ThinkingPart ->
                            if (part.text.isNotBlank()) {
                                if (stepsKey == null) stepsKey = "steps:$partKey"
                                val live = message.draft && index == message.parts.lastIndex
                                steps +=
                                    Step(
                                        partKey,
                                        StepAction.THINK,
                                        null,
                                        if (live) StepStatus.RUNNING else StepStatus.DONE,
                                        thinking = part.text,
                                    )
                            }
                        is ToolPart -> {
                            if (stepsKey == null) stepsKey = "steps:$partKey"
                            steps += stepOf(partKey, part)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    flush()
    closeTurn()
    return blocks
}

/** 工具调用 → 步骤（6.6 的对应表）。字段名以 DSH 0.2.2-rc.1 的录制数据为准。 */
fun stepOf(
    key: String,
    tool: ToolPart,
): Step {
    fun str(name: String): String? = (tool.input[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    val (action, target) =
        when (tool.name) {
            "bash", "pwsh" -> StepAction.RUN to str("command")?.lineSequence()?.firstOrNull()
            "read", "read_image" -> StepAction.READ to str("file_path")?.let(::fileName)
            "write" -> StepAction.WRITE to str("file_path")?.let(::fileName)
            "edit" -> StepAction.EDIT to str("file_path")?.let(::fileName)
            "grep", "glob" -> StepAction.SEARCH to str("pattern")
            "web_fetch" -> StepAction.OPEN to str("url")
            "web_search" -> StepAction.WEB_SEARCH to str("query")
            else -> StepAction.CALL to tool.name
        }
    val status =
        when (tool.status) {
            ToolStatus.RUNNING -> StepStatus.RUNNING
            ToolStatus.DONE -> StepStatus.DONE
            ToolStatus.ERROR -> StepStatus.FAILED
            ToolStatus.REJECTED -> StepStatus.REJECTED
            ToolStatus.UNKNOWN -> StepStatus.UNKNOWN
        }
    return Step(key, action, target?.takeIf { it.isNotBlank() }, status, resultOf(action, status, tool), tool)
}

/** 只在数据完整时给出结果：被插件截断过的不算，失败和被拒绝的不算。 */
private fun resultOf(
    action: StepAction,
    status: StepStatus,
    tool: ToolPart,
): StepResult? {
    if (status != StepStatus.DONE) return null
    return when (action) {
        StepAction.READ -> tool.output?.takeIf { !isClipped(tool, it) && it.isNotEmpty() }?.let { StepResult.Lines(lines(it).size) }
        StepAction.EDIT -> {
            val old = tool.stringInput("old_string") ?: return null
            val new = tool.stringInput("new_string") ?: return null
            if (isClipped(tool, old) || isClipped(tool, new)) return null
            val diff = lineDiff(old, new)
            StepResult.Changed(diff.count { it.kind == DiffKind.ADDED }, diff.count { it.kind == DiffKind.REMOVED })
        }
        StepAction.WRITE -> {
            val content = tool.stringInput("content") ?: return null
            if (isClipped(tool, content)) null else StepResult.Changed(lines(content).size, 0)
        }
        else -> null
    }
}

internal fun ToolPart.stringInput(name: String): String? = (input[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** 插件把过长的字符串截断并在末尾加「…」，同时把整个调用标为 truncated。两者都成立才认为这个字符串被截过。 */
internal fun isClipped(
    tool: ToolPart,
    text: String,
): Boolean = tool.truncated && text.endsWith(TRUNCATION_MARK)

internal const val TRUNCATION_MARK = "…"

/** 路径的最后一段。Windows 和 Linux 的分隔符都认。 */
internal fun fileName(path: String): String =
    path
        .trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifEmpty { path }

/** 按行切开；末尾的换行不算一行。 */
internal fun lines(text: String): List<String> = text.removeSuffix("\n").removeSuffix("\r").split(Regex("\r?\n"))

enum class DiffKind { CONTEXT, ADDED, REMOVED }

data class DiffLine(
    val kind: DiffKind,
    val text: String,
)

/**
 * `edit` 的改动对比：按行求最长公共子序列，删除行排在同一位置的新增行前面。
 * 插件把每个字符串截到 2,000 个字符，行数很少，直接用 O(n·m) 的表。
 */
fun lineDiff(
    old: String,
    new: String,
): List<DiffLine> {
    val a = if (old.isEmpty()) emptyList() else lines(old)
    val b = if (new.isEmpty()) emptyList() else lines(new)
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val out = ArrayList<DiffLine>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> {
                out += DiffLine(DiffKind.CONTEXT, a[i])
                i++
                j++
            }
            lcs[i + 1][j] >= lcs[i][j + 1] -> out += DiffLine(DiffKind.REMOVED, a[i++])
            else -> out += DiffLine(DiffKind.ADDED, b[j++])
        }
    }
    while (i < a.size) out += DiffLine(DiffKind.REMOVED, a[i++])
    while (j < b.size) out += DiffLine(DiffKind.ADDED, b[j++])
    return out
}

/**
 * 命令输出拆成开头、中间去掉的行数、末尾（落地方案 B6）。插件没有去掉中间时（输出不长，或者旧插件）只有 [tail]。
 * [total] 是原来的总行数（去掉的部分按插件给的换行数算）。
 */
data class CommandOutput(
    val head: List<String>,
    val omittedLines: Int?,
    val tail: List<String>,
) {
    val total: Int get() = head.size + (omittedLines ?: 0) + tail.size
}

fun commandOutput(
    output: String,
    omitted: OutputOmission?,
): CommandOutput {
    val cut = omitted?.takeIf { it.offset in 0..output.length && it.lines >= 0 }
    if (cut == null) return CommandOutput(emptyList(), null, lines(output.trimEnd()))
    val head = output.substring(0, cut.offset)
    val tail = output.substring(cut.offset).trimEnd()
    return CommandOutput(
        head = if (head.isEmpty()) emptyList() else lines(head),
        omittedLines = cut.lines,
        tail = if (tail.isEmpty()) emptyList() else lines(tail),
    )
}

/** 命令输出默认显示的末尾行数（6.6）。 */
const val OUTPUT_TAIL_LINES = 4

/**
 * 运行中，对话末尾那一行写的「正在做的那一步」：最后一个还在运行的步骤。
 * 没有在运行的步骤（例如正在写回复）时为 null。
 */
fun currentStep(blocks: List<ChatBlock>): Step? {
    val last = blocks.lastOrNull() as? ChatBlock.Steps ?: return null
    return last.steps.lastOrNull { it.status == StepStatus.RUNNING }
}
