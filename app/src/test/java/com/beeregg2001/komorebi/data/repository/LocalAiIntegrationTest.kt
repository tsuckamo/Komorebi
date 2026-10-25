package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.AiSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 明示したテストサーバーと合成音声だけを使う任意の結合テスト。TV操作は実行しない。 */
class LocalAiIntegrationTest {
    @Test fun whisperToQwen() = runBlocking {
        val wavPath = System.getenv("KOMOREBI_TEST_WAV")
        assumeTrue(!wavPath.isNullOrBlank())
        val settings = AiSettings(
            provider = "LOCAL",
            baseUrl = requireNotNull(System.getenv("KOMOREBI_TEST_AI_URL")),
            model = requireNotNull(System.getenv("KOMOREBI_TEST_AI_MODEL")),
            whisperBaseUrl = requireNotNull(System.getenv("KOMOREBI_TEST_WHISPER_URL"))
        )
        val client = LocalAiClient()
        val text = client.transcribe(settings, File(wavPath!!).readBytes())
        assertTrue(text.contains("BS11"))
        val answer = client.chat(settings,
            "チャンネル一覧: BS11=bs11。チャンネル変更の指示には [PLAY_LIVE: bs11] とだけ答えてください。", text)
        assertEquals("[PLAY_LIVE: bs11]", answer)
    }
}
