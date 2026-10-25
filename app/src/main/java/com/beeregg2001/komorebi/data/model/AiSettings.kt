package com.beeregg2001.komorebi.data.model

data class AiSettings(
    val provider: String = "GEMINI",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val whisperBaseUrl: String = "",
    val whisperModel: String = "whisper-local",
    val whisperApiKey: String = ""
) {
    val isLocal: Boolean get() = provider == "LOCAL"
    val isChatConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
    val isSpeechConfigured: Boolean get() = whisperBaseUrl.isNotBlank() && whisperModel.isNotBlank()
}
