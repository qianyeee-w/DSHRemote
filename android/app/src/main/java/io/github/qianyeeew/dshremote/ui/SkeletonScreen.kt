package io.github.qianyeeew.dshremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R

/** 骨架阶段的占位页面，T7 换成主机列表。 */
@Composable
fun SkeletonScreen(versionName: String) {
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.skeleton_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.skeleton_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.skeleton_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun SkeletonScreenPreview() {
    DshRemoteTheme { SkeletonScreen(versionName = "0.1.0") }
}
