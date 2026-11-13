package io.github.qianyeeew.dshremote.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户正在看的会话：会话页、计划页开始显示时 [hold]，停止时调用返回的函数释放。按次数算：
 * 从会话页进计划页时，计划页先开始、会话页后停止（返回时反过来），同一个会话不会在中途被清掉。
 * 只在主线程上调用。
 */
class VisibleSessions {
    private val holds = LinkedHashMap<VisibleSession, Int>()
    private val _current = MutableStateFlow<VisibleSession?>(null)

    /** 最后被持有、还没释放的会话；都释放了时为 null。 */
    val current: StateFlow<VisibleSession?> = _current.asStateFlow()

    fun hold(session: VisibleSession): () -> Unit {
        holds.remove(session)?.let { holds[session] = it + 1 } ?: run { holds[session] = 1 }
        _current.value = session
        var released = false
        return {
            if (!released) {
                released = true
                val left = (holds[session] ?: 1) - 1
                if (left > 0) holds[session] = left else holds.remove(session)
                _current.value = holds.keys.lastOrNull()
            }
        }
    }
}
