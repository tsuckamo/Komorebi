package com.beeregg2001.komorebi.ui.video.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@Composable
fun RecordDeleteDialog(
    program: RecordedProgram,
    isDeleting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(isDeleting, error) {
        // リモコンの決定ボタン連打で削除しないよう、最初はキャンセルを選ぶ。
        if (!isDeleting) cancelFocus.safeRequestFocusWithRetry("RecordDeleteCancel")
    }
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        containerColor = colors.surface,
        title = { Text("録画を削除しますか？", color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(program.title, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${program.channel?.name.orEmpty()}  ${program.startTime}",
                    color = colors.textSecondary
                )
                Text(
                    "EPGStation上のこの番組の録画情報と、紐づくすべての動画ファイル（元TS・エンコード済み）を削除します。元に戻せません。",
                    color = colors.textPrimary
                )
                if (isDeleting) Text("削除処理中…", color = colors.textSecondary)
                error?.let { Text(it, color = Color(0xFFFF8A80)) }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                enabled = !isDeleting,
                modifier = Modifier.focusRequester(cancelFocus)
            ) { Text("キャンセル") }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF8C2424),
                    contentColor = Color.White,
                    focusedContainerColor = Color(0xFFFFDAD6),
                    focusedContentColor = Color(0xFF410002)
                )
            ) { Text("録画を削除") }
        }
    )
}
