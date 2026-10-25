package com.beeregg2001.komorebi.ui.reserve

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.components.InputDialog
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val weekNames = listOf("日", "月", "火", "水", "木", "金", "土")
private fun weekLabel(mask: Int) = if (mask == 127) "全曜日" else weekNames.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString("・") + "曜日"
private fun dateLabel(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Tokyo")).format(DateTimeFormatter.ofPattern("M/d(E) HH:mm"))

@Composable
fun AutoReserveCreationDialog(keyword: String, onDismiss: () -> Unit, onChanged: () -> Unit,
    viewModel: AutoReserveCreationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = KomorebiTheme.colors
    val cancelFocus = remember { FocusRequester() }
    var editingKeyword by remember { mutableStateOf(false) }
    val running = state is AutoReserveCreateUi.Running
    LaunchedEffect(keyword) { viewModel.search(keyword) }
    LaunchedEffect(state::class) {
        if (!running) cancelFocus.safeRequestFocusWithRetry("AutoReserveCreateDismiss")
        if (state is AutoReserveCreateUi.Done) onChanged()
    }
    val dismiss = { if (!running) { viewModel.dismiss(); onDismiss() } }
    val options = state as? AutoReserveCreateUi.Options
    if (editingKeyword && options != null) {
        InputDialog(title = "毎週録画の検索語", initialValue = options.options.keyword,
            onDismiss = { editingKeyword = false },
            onConfirm = { viewModel.edit(options.options.copy(keyword = it)); editingKeyword = false })
    }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = colors.surface,
        properties = DialogProperties(usePlatformDefaultWidth = false), modifier = Modifier.width(720.dp),
        title = { Text("毎週・自動録画を追加", color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val current = state) {
                    AutoReserveCreateUi.Loading -> Text("番組表と録画条件を確認しています…", color = colors.textPrimary)
                    is AutoReserveCreateUi.Candidates -> {
                        Text(if (current.items.isEmpty()) "開始まで10分以上ある候補が見つかりません。番組表の取得範囲や番組名を確認してください。登録していません。" else "録画したい番組と放送局を選んでください。まだ登録しません。", color = colors.textPrimary)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 270.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(current.items, key = { it.programId }) { item ->
                                Button(onClick = { viewModel.select(item) }, modifier = Modifier.fillMaxWidth()) {
                                    Column { Text(item.title); Text("${dateLabel(item.startAt)}  ${item.channel}") }
                                }
                            }
                        }
                    }
                    is AutoReserveCreateUi.Options -> {
                        Text("${current.candidate.channel} / ${current.candidate.title}", color = colors.textPrimary)
                        Button(onClick = { editingKeyword = true }) { Text("検索語: ${current.options.keyword}（変更）") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.edit(current.options.copy(week = current.candidate.week)) }) { Text("同じ曜日: ${weekLabel(current.candidate.week)}") }
                            Button(onClick = { viewModel.edit(current.options.copy(week = 127)) }) { Text("全曜日") }
                            Button(onClick = { viewModel.edit(current.options.copy(excludeReruns = !current.options.excludeReruns)) }) { Text(if (current.options.excludeReruns) "再放送表示: 除外" else "再放送表示: 含める") }
                        }
                        Text("選択中: ${weekLabel(current.options.week)} / 時刻指定なし / 番組名を検索\n保存先・画質はEPGStationの標準設定です。", color = colors.textPrimary)
                        Text("同じ曜日なら放送時刻の変更にも追従します。深夜0時を越えて別曜日になる変更には「全曜日」を選んでください。再放送の表示がない番組は除外できません。", color = colors.textSecondary)
                        current.error?.let { Text(it, color = colors.textPrimary) }
                    }
                    is AutoReserveCreateUi.Review -> {
                        val preview = current.preview
                        Text("検索語「${preview.options.keyword}」 / ${preview.candidate.channel}\n${weekLabel(preview.options.week)}・時刻指定なし / 再放送表示${if (preview.options.excludeReruns) "を除外" else "も含める"}", color = colors.textPrimary)
                        Text("現在の番組表で対象${preview.programs.size}件。各行は上下キーで確認できます。", color = colors.textPrimary)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(preview.programs, key = { it.programId }) { item ->
                                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("${dateLabel(item.startAt)} ${item.title}") }
                            }
                        }
                        Text("今後も同じ条件の放送を自動予約します。番組名が変わった回や条件外の放送は対象になりません。", color = colors.textSecondary)
                        Button(onClick = viewModel::revise) { Text("条件を修正") }
                    }
                    AutoReserveCreateUi.Running -> Text("登録結果と予約への反映を照合しています…", color = colors.textPrimary)
                    is AutoReserveCreateUi.Done -> Text(current.result.message, color = colors.textPrimary)
                    is AutoReserveCreateUi.Error -> Text(current.message, color = colors.textPrimary)
                }
            }
        },
        dismissButton = { Button(onClick = dismiss, enabled = !running, modifier = Modifier.focusRequester(cancelFocus)) { Text(if (state is AutoReserveCreateUi.Done) "閉じる" else "やめる") } },
        confirmButton = {
            when (state) {
                is AutoReserveCreateUi.Options -> Button(onClick = viewModel::preview) { Text("対象を確認") }
                is AutoReserveCreateUi.Review -> Button(onClick = viewModel::confirm) { Text("この条件で毎週録画を登録") }
                is AutoReserveCreateUi.Candidates, is AutoReserveCreateUi.Error -> Button(onClick = { viewModel.search(keyword) }) { Text("候補を読み直す") }
                else -> Unit
            }
        }
    )
}
