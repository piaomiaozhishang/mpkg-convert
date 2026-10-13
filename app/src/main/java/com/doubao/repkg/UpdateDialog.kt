package com.doubao.repkg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「发现新版本」弹窗。
 *
 * - 可选更新：「立即更新 / 稍后提醒 / 忽略此版本」，点外部 = 稍后提醒；
 * - 强制更新：只有「立即更新 / 退出」，且**点外部与返回键都不关闭**。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersionName: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    onExit: () -> Unit
) {
    // 强制更新时吞掉返回键
    BackHandler(enabled = info.force) { /* no-op */ }

    AlertDialog(
        onDismissRequest = { if (!info.force) onLater() },
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = {
            Text(if (info.force) "需要更新才能继续使用" else info.title.ifBlank { "发现新版本" })
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "当前 v$currentVersionName  →  最新 v${info.versionName}",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (info.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        info.notes.forEach { line ->
                            Text(
                                "•  $line",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                info.sizeBytes?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "安装包约 ${formatSize(it)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (info.force) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "此为必要更新，更新后才能继续使用。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onUpdate) { Text("立即更新") }
        },
        dismissButton = {
            if (info.force) {
                TextButton(onClick = onExit) { Text("退出") }
            } else {
                Row {
                    TextButton(onClick = onLater) { Text("稍后提醒") }
                    TextButton(onClick = onIgnore) { Text("忽略此版本") }
                }
            }
        }
    )
}
