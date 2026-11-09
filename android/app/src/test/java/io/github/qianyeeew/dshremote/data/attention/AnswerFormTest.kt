package io.github.qianyeeew.dshremote.data.attention

import io.github.qianyeeew.dshremote.data.api.AttentionAnswer
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionAnswer
import io.github.qianyeeew.dshremote.data.api.QuestionOption
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 回答提问前的本地校验，规则和插件的 toDshAnswer 一致（protocol.md 5.3）。 */
class AnswerFormTest {
    private val single = Question("q1", "用哪种方案？", options = listOf(QuestionOption("方案A"), QuestionOption("方案B")))
    private val multi =
        Question("q2", "要哪些功能？", options = listOf(QuestionOption("甲"), QuestionOption("乙"), QuestionOption("丙")), multiSelect = true)
    private val set = QuestionSet(listOf(single, multi))

    @Test
    fun singleSelectKeepsOneAndTogglesOff() {
        var d = QuestionDraft()
        d = AnswerForm.toggle(single, d, "方案A")
        d = AnswerForm.toggle(single, d, "方案B")
        assertEquals(listOf("方案B"), d.selected)
        d = AnswerForm.toggle(single, d, "方案B")
        assertEquals(emptyList<String>(), d.selected)
        // 不存在的选项忽略
        assertEquals(d, AnswerForm.toggle(single, d, "方案C"))
    }

    @Test
    fun multiSelectAddsAndRemoves() {
        var d = QuestionDraft()
        d = AnswerForm.toggle(multi, d, "丙")
        d = AnswerForm.toggle(multi, d, "甲")
        d = AnswerForm.toggle(multi, d, "乙")
        d = AnswerForm.toggle(multi, d, "甲")
        assertEquals(listOf("丙", "乙"), d.selected)
    }

    @Test
    fun answersOnlyTouchedQuestionsInOptionOrder() {
        val check = AnswerForm.check(set, mapOf("q2" to QuestionDraft(selected = listOf("丙", "甲"))))
        assertEquals(AnswerCheck.Ok(AttentionAnswer.Answers(listOf(QuestionAnswer("q2", listOf("甲", "丙"))))), check)
    }

    @Test
    fun customTextAloneIsAnAnswerButBlankIsNot() {
        assertEquals(
            AnswerCheck.Ok(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", emptyList(), "都不要")))),
            AnswerForm.check(set, mapOf("q1" to QuestionDraft(custom = "都不要"))),
        )
        assertEquals(AnswerCheck.NothingAnswered, AnswerForm.check(set, mapOf("q1" to QuestionDraft(custom = "  \n"))))
        assertEquals(AnswerCheck.NothingAnswered, AnswerForm.check(set, emptyMap()))
        // 选了选项又只填了空白：只发选项，不发 custom
        assertEquals(
            AnswerCheck.Ok(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("方案A"), null)))),
            AnswerForm.check(set, mapOf("q1" to QuestionDraft(listOf("方案A"), " "))),
        )
    }

    @Test
    fun invalidSelectionsAreDroppedAndSingleSelectSendsOne() {
        // 正常界面上不会出现，但即使草稿里有，也不会发出插件会拒绝的内容
        val check = AnswerForm.check(set, mapOf("q1" to QuestionDraft(selected = listOf("方案B", "方案A", "不存在"))))
        assertEquals(AnswerCheck.Ok(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("方案A"))))), check)
        assertEquals(AnswerCheck.NothingAnswered, AnswerForm.check(set, mapOf("q1" to QuestionDraft(selected = listOf("不存在")))))
        assertEquals(AnswerCheck.NothingAnswered, AnswerForm.check(set, mapOf("q9" to QuestionDraft(selected = listOf("方案A")))))
    }

    @Test
    fun customTextLimitMatchesThePlugin() {
        val ok = "字".repeat(AnswerForm.MAX_CUSTOM)
        assertEquals(
            AnswerCheck.Ok(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", emptyList(), ok)))),
            AnswerForm.check(set, mapOf("q1" to QuestionDraft(custom = ok))),
        )
        assertEquals(
            AnswerCheck.Invalid(mapOf("q1" to QuestionProblem.CUSTOM_TOO_LONG)),
            AnswerForm.check(set, mapOf("q1" to QuestionDraft(custom = ok + "多"), "q2" to QuestionDraft(listOf("甲")))),
        )
    }

    @Test
    fun draftDoesNotPrintCustomText() {
        assertFalse(QuestionDraft(custom = "私密内容").toString().contains("私密内容"))
    }
}
