package io.github.qianyeeew.dshremote.data.attention

import io.github.qianyeeew.dshremote.data.api.AttentionAnswer
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionAnswer
import io.github.qianyeeew.dshremote.data.api.QuestionSet

/** 一个问题的回答草稿：选中的选项（按选中的先后）和自由填写的文本。 */
data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
) {
    /** 不输出自由文本（可能包含用户的隐私）。 */
    override fun toString(): String = "QuestionDraft(selected=$selected, custom=${custom.length} 个字符)"
}

enum class QuestionProblem {
    /** 自由文本超过 [AnswerForm.MAX_CUSTOM] 个字符。 */
    CUSTOM_TOO_LONG,
}

sealed interface AnswerCheck {
    data class Ok(
        val answer: AttentionAnswer.Answers,
    ) : AnswerCheck

    /** 一个问题都没有回答。 */
    data object NothingAnswered : AnswerCheck

    data class Invalid(
        val problems: Map<String, QuestionProblem>,
    ) : AnswerCheck
}

/**
 * 回答提问前的本地校验（protocol.md 5.3），规则和插件 `src/dsh/attention.js` 的 `toDshAnswer` 一致：
 * - 至少回答一个问题，不需要回答全部；没有选也没有填（只有空白）的问题不发送；
 * - 单选题最多选一项，只能选已有的选项；
 * - 自由文本最多 [MAX_CUSTOM] 个字符，只有空白时不发送。
 */
object AnswerForm {
    /** 和插件的 ANSWER_TEXT_LIMIT 一致（按 UTF-16 计数，和 JavaScript 的 length 相同）。 */
    const val MAX_CUSTOM = 4_000

    /** 点了一个选项：多选题切换这一项；单选题选中它（再点一次取消）。不存在的选项忽略。 */
    fun toggle(
        question: Question,
        draft: QuestionDraft,
        label: String,
    ): QuestionDraft {
        if (question.options.none { it.label == label }) return draft
        val selected =
            when {
                label in draft.selected -> draft.selected - label
                question.multiSelect -> draft.selected + label
                else -> listOf(label)
            }
        return draft.copy(selected = selected)
    }

    fun check(
        set: QuestionSet,
        drafts: Map<String, QuestionDraft>,
    ): AnswerCheck {
        val problems = mutableMapOf<String, QuestionProblem>()
        val answers = mutableListOf<QuestionAnswer>()
        for (question in set.questions) {
            val draft = drafts[question.id] ?: continue
            if (draft.custom.length > MAX_CUSTOM) {
                problems[question.id] = QuestionProblem.CUSTOM_TOO_LONG
                continue
            }
            // 按选项的顺序发送，只保留存在的选项；单选题只保留一项
            val selected =
                question.options
                    .map { it.label }
                    .filter { it in draft.selected }
                    .let { if (question.multiSelect) it else it.take(1) }
            val custom = draft.custom.takeIf { it.isNotBlank() }
            if (selected.isEmpty() && custom == null) continue
            answers += QuestionAnswer(question.id, selected, custom)
        }
        return when {
            problems.isNotEmpty() -> AnswerCheck.Invalid(problems)
            answers.isEmpty() -> AnswerCheck.NothingAnswered
            else -> AnswerCheck.Ok(AttentionAnswer.Answers(answers))
        }
    }
}
