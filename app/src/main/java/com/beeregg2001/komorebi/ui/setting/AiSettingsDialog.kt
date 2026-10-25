package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.AiSettings
import com.beeregg2001.komorebi.data.repository.LocalAiClient
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@Composable
fun AiSettingsDialog(
    initial: AiSettings,
    onSave: (AiSettings) -> Unit,
    onGeminiSetup: (AiSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = KomorebiTheme.colors
    val providerFocus = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing == null) providerFocus.safeRequestFocusWithRetry("AiSettings") }
    val fields = listOf(
        "会話AIのURL" to value.baseUrl,
        "会話AIのモデル名" to value.model,
        "会話AIのトークン（任意）" to value.apiKey,
        "WhisperのURL" to value.whisperBaseUrl,
        "Whisperのモデル名" to value.whisperModel,
        "Whisperのトークン（任意）" to value.whisperApiKey
    )
    val currentEdit = editing
    if (currentEdit != null) {
        com.beeregg2001.komorebi.ui.components.InputDialog(
            title = currentEdit,
            initialValue = fields.first { it.first == currentEdit }.second,
            onDismiss = { editing = null },
            onConfirm = { input ->
                val text = input.trim()
                value = when (currentEdit) {
                    "会話AIのURL" -> value.copy(baseUrl = text)
                    "会話AIのモデル名" -> value.copy(model = text)
                    "会話AIのトークン（任意）" -> value.copy(apiKey = text)
                    "WhisperのURL" -> value.copy(whisperBaseUrl = text)
                    "Whisperのモデル名" -> value.copy(whisperModel = text)
                    else -> value.copy(whisperApiKey = text)
                }
                editing = null
                error = null
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("AIコンシェルジュの設定", color = colors.textPrimary) },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingItem("会話・音声の処理先", if (value.isLocal) "ローカルLLM + Whisper" else "Gemini",
                    modifier = Modifier.focusRequester(providerFocus)) {
                    value = value.copy(provider = if (value.isLocal) "GEMINI" else "LOCAL")
                    error = null
                }
                if (value.isLocal) {
                    Text("会話はLLMサーバー、音声はWhisperサーバーで処理します。URLには /v1 まで指定できます。",
                        color = colors.textSecondary)
                    fields.forEach { (title, text) ->
                        SettingItem(title, if (title.contains("トークン") && text.isNotBlank()) "設定済み" else text.ifBlank { "未設定" }) {
                            editing = title
                        }
                    }
                } else {
                    Text("会話と音声をGoogle Geminiで処理します。", color = colors.textSecondary)
                    SettingItem("Gemini APIキーの設定", "開く") { onGeminiSetup(value) }
                }
                error?.let { Text(it, color = Color(0xFFFF8A80)) }
            }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("キャンセル") } },
        confirmButton = {
            Button(onClick = {
                val validation = runCatching {
                    if (value.isLocal) {
                        require(value.isChatConfigured) { "会話AIのURLとモデル名を入力してください。" }
                        LocalAiClient.endpoint(value.baseUrl, "chat/completions")
                        if (value.whisperBaseUrl.isNotBlank()) {
                            require(value.whisperModel.isNotBlank()) { "Whisperのモデル名を入力してください。" }
                            LocalAiClient.endpoint(value.whisperBaseUrl, "audio/transcriptions")
                        }
                    }
                }
                validation.onSuccess { onSave(value) }.onFailure { error = it.message }
            }) { Text("保存") }
        }
    )
}
