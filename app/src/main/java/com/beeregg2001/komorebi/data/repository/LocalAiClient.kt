package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.AiSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 録画サーバー用のURL書換え・認証Interceptorを共有しない、AI専用クライアント。 */
@Singleton
class LocalAiClient @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val gson = Gson()

    suspend fun chat(settings: AiSettings, systemPrompt: String, userPrompt: String): String {
        require(settings.isChatConfigured) { "会話AIの接続先URLとモデル名を設定してください。" }
        val json = gson.toJson(mapOf(
            "model" to settings.model.trim(),
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "stream" to false,
            "max_tokens" to 1024,
            "temperature" to 0.3,
            "chat_template_kwargs" to mapOf("enable_thinking" to false)
        ))
        val result = request(Request.Builder()
            .url(endpoint(settings.baseUrl, "chat/completions"))
            .withToken(settings.apiKey)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build(), "会話AI")
        val choice = result.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
        check(choice?.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString != "length") { "AIの応答が途中で切れました。短い指示でもう一度お試しください。" }
        val content = choice?.getAsJsonObject("message")?.get("content")
        val text = if (content != null && !content.isJsonNull && content.isJsonPrimitive) content.asString else ""
        return cleanAssistantText(text).also {
            check(it.isNotBlank()) { "会話AIから回答が返りませんでした。モデル設定を確認してください。" }
        }
    }

    suspend fun transcribe(settings: AiSettings, wav: ByteArray): String {
        require(settings.isSpeechConfigured) { "Whisperの接続先URLとモデル名を設定してください。" }
        require(wav.size > 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(wav, 8, 4, Charsets.US_ASCII) == "WAVE") { "録音データがありません。マイクを確認してください。" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", settings.whisperModel.trim())
            .addFormDataPart("language", "ja")
            .addFormDataPart("response_format", "json")
            .build()
        val result = request(Request.Builder()
            .url(endpoint(settings.whisperBaseUrl, "audio/transcriptions"))
            .withToken(settings.whisperApiKey).post(body).build(), "Whisper")
        val value = result.get("text")
        check(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Whisperの応答にtextがありません。API形式を確認してください。"
        }
        return value.asString.trim()
    }

    private fun Request.Builder.withToken(token: String): Request.Builder = apply {
        if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
    }

    private suspend fun request(request: Request, service: String): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "${service}への接続に失敗しました（HTTP ${response.code}）。接続先・モデル・認証設定を確認してください。" }
            try {
                gson.fromJson(response.body?.string(), JsonObject::class.java)
                    ?: throw IOException("空の応答")
            } catch (e: Exception) {
                throw IOException("${service}からJSON形式の応答を取得できませんでした。", e)
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        fun endpoint(baseUrl: String, path: String): HttpUrl {
            val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("接続先はhttp://またはhttps://で始まるURLを指定してください。")
            require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
                "URLには認証情報・クエリ・フラグメントを含めず、認証トークン欄を使用してください。"
            }
            val prefix = base.encodedPath.trimEnd('/')
            val apiPath = if (prefix.endsWith("/v1")) prefix else "$prefix/v1"
            return base.newBuilder().encodedPath("$apiPath/$path").build()
        }

        /** 思考文中の操作タグを実行しない。未完の思考文も除去する。 */
        fun cleanAssistantText(text: String): String = text
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .substringBefore("<think>").trim()
    }
}
