package com.beeregg2001.komorebi.ui.reserve

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.AutoReserveCancellationViewModel
import com.beeregg2001.komorebi.viewmodel.AutoReserveCancelUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AutoReserveCancellationDialog(
    keyword: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    viewModel: AutoReserveCancellationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val colors = KomorebiTheme.colors
    val cancelFocus = remember { FocusRequester() }
    val running = state is AutoReserveCancelUi.Running
    LaunchedEffect(keyword) { viewModel.search(keyword) }
    LaunchedEffect(state) {
        if (!running) cancelFocus.safeRequestFocusWithRetry("AutoReserveCancelDismiss")
        if (state is AutoReserveCancelUi.Done) onChanged()
    }
    val dismiss = { if (!running) { viewModel.dismiss(); onDismiss() } }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = colors.surface,
        title = { Text("毎週・自動録画の取り消し", color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val current = state) {
                    AutoReserveCancelUi.Closed, AutoReserveCancelUi.Loading -> Text("EPGStationの最新情報を確認しています…", color = colors.textPrimary)
                    is AutoReserveCancelUi.Candidates -> {
                        Text(if (current.items.isEmpty()) "一致する自動録画ルールはありません。変更していません。" else "取り消すルールを1つ選んでください。", color = colors.textPrimary)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(current.items, key = { it.id }) { item ->
                                Button(onClick = { viewModel.select(item.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Column { Text(item.keyword); Text(item.details) }
                                }
                            }
                        }
                    }
                    is AutoReserveCancelUi.Review -> {
                        val preview = current.preview
                        Text(preview.candidate.keyword, color = colors.textPrimary)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text(preview.candidate.details, color = colors.textSecondary) }
                            item { Text("取り消す関連予約: ${preview.programs.size}件", color = colors.textPrimary) }
                            items(preview.programs, key = { it.id }) { program ->
                                val date = Instant.ofEpochMilli(program.startAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
                                Text("$date ${program.channel}\n${program.title}", color = colors.textSecondary)
                            }
                        }
                        Text("このルールを削除し、今後の自動録画と関連予約を取り消します。録画済みの番組は残ります。", color = colors.textPrimary)
                        Text("同じ番組に別のルールや単発予約がある場合、その録画は継続します。", color = colors.textSecondary)
                    }
                    AutoReserveCancelUi.Running -> Text("取り消しと、サーバーへの反映を確認しています…", color = colors.textPrimary)
                    is AutoReserveCancelUi.Done -> Text(current.message, color = colors.textPrimary)
                    is AutoReserveCancelUi.Error -> Text(current.message, color = Color(0xFFFF8A80))
                }
            }
        },
        dismissButton = {
            Button(onClick = dismiss, enabled = !running, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(if (state is AutoReserveCancelUi.Review) "やめる" else "閉じる")
            }
        },
        confirmButton = {
            when (state) {
                is AutoReserveCancelUi.Review -> Button(onClick = viewModel::confirm) { Text("この毎週予約を取り消す") }
                is AutoReserveCancelUi.Error, is AutoReserveCancelUi.Candidates -> Button(onClick = { viewModel.search(keyword) }) { Text("候補を読み直す") }
                else -> Unit
            }
        }
    )
}
