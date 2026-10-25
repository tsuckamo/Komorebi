package com.beeregg2001.komorebi.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * AIコンシェルジュ用の音声録音エンジン
 */
class AudioRecorderHelper(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var outputFile: File? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    fun startRecording(): Boolean {
        if (isRecording || recordingJob?.isActive == true) return false
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val file = File(context.cacheDir, "ai_concierge_voice.wav")
        file.delete()
        outputFile = file
        return try {
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            isRecording = true
            recordingJob = coroutineScope.launch { writeAudioDataToFile(recorder, file) }
            true
        } catch (e: Exception) {
            Log.w("AudioRecorderHelper", "録音を開始できませんでした", e)
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    suspend fun stopRecording(): File? = withContext(Dispatchers.IO + NonCancellable) {
        if (!isRecording) return@withContext null
        isRecording = false
        val recorder = audioRecord
        try {
            runCatching { recorder?.stop() }
            // PCM読み取りの終了とWAVヘッダーの書き込みが終わるまで待つ。
            recordingJob?.join()
            outputFile?.takeIf { it.exists() && it.length() > 44 }
        } finally {
            recorder?.release()
            audioRecord = null
            recordingJob = null
        }
    }

    fun release() {
        isRecording = false
        runCatching { audioRecord?.stop() }
        coroutineScope.launch {
            recordingJob?.join()
            audioRecord?.release()
            audioRecord = null
            outputFile?.delete()
            coroutineScope.cancel()
        }
    }

    private fun writeAudioDataToFile(recorder: AudioRecord, file: File) {
        val rawFile = File(context.cacheDir, "temp_raw_audio.pcm")
        val data = ByteArray(bufferSize)
        var totalBytesRead = 0L
        try {
            FileOutputStream(rawFile).use { os ->
                while (isRecording) {
                    val count = recorder.read(data, 0, data.size)
                    if (count < 0) break
                    if (count > 0) { os.write(data, 0, count); totalBytesRead += count }
                }
            }
            if (totalBytesRead > 0) copyRawToWav(rawFile, file)
        } catch (e: Exception) {
            Log.w("AudioRecorderHelper", "録音データを保存できませんでした", e)
            file.delete()
        } finally {
            rawFile.delete()
        }
    }

    private fun copyRawToWav(rawFile: File, wavFile: File) {
        val rawData = ByteArray(rawFile.length().toInt())
        rawFile.inputStream().use { it.read(rawData) }

        wavFile.outputStream().use { os ->
            writeWavHeader(os, rawData.size.toLong(), sampleRate.toLong(), 1, 16)
            os.write(rawData)
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        audioDataLength: Long,
        sampleRate: Long,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = audioDataLength + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] =
            'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] =
            (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] =
            (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] =
            'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] =
            't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] =
            (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] =
            (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] =
            (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] =
            't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (audioDataLength and 0xff).toByte(); header[41] =
            (audioDataLength shr 8 and 0xff).toByte()
        header[42] = (audioDataLength shr 16 and 0xff).toByte(); header[43] =
            (audioDataLength shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }
}
