package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.common.BackTopBar

/** 会话详情的占位页面：T9 实现消息、输入框和流式输出。 */
@Composable
fun ChatScreen(
    title: String?,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sessions_untitled), onBack) }) { padding ->
        Text(
            stringResource(R.string.chat_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
        )
    }
}
