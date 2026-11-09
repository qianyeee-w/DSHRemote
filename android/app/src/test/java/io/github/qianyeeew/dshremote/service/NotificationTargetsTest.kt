package io.github.qianyeeew.dshremote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 点通知的入口（security.md「App 端的通知」）：只认识这个进程发出的令牌。 */
class NotificationTargetsTest {
    @Test
    fun tokenResolvesToItsTargetAndIsStable() {
        val target = NotificationTarget.AttentionPage("h1", "a-${System.nanoTime()}")
        val token = NotificationTargets.tokenFor(target)
        assertEquals(token, NotificationTargets.tokenFor(target))
        assertEquals(target, NotificationTargets.resolve(token))
        assertNotEquals(token, NotificationTargets.tokenFor(NotificationTarget.SessionPage("h1", "s-${System.nanoTime()}")))
    }

    @Test
    fun forgedOrMissingTokensAreIgnored() {
        assertNull(NotificationTargets.resolve(null))
        assertNull(NotificationTargets.resolve(""))
        assertNull(NotificationTargets.resolve("h1"))
        // 其他 App 能看到的只有 hostId、attentionId 之类的信息，拿它们拼不出令牌
        val target = NotificationTarget.AttentionPage("h1", "a1")
        NotificationTargets.tokenFor(target)
        assertNull(NotificationTargets.resolve("a1"))
        assertNull(NotificationTargets.resolve("attention:h1:a1"))
    }

    @Test
    fun oldTokensAreForgottenBeyondTheLimit() {
        val first = NotificationTarget.SessionPage("h-old", "s-${System.nanoTime()}")
        val token = NotificationTargets.tokenFor(first)
        repeat(300) { NotificationTargets.tokenFor(NotificationTarget.SessionPage("h-many", "s$it-${System.nanoTime()}")) }
        assertNull(NotificationTargets.resolve(token))
    }
}
