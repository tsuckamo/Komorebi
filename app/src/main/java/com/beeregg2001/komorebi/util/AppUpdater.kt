package com.beeregg2001.komorebi.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val versionName: String, val releaseNotes: String, val apkUrl: String) : UpdateState()
    data class Downloading(val progressPercentage: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
    private var available: CustomRelease? = null

    // 個人版は専用の安定版だけを取得する。既存の呼び出し引数は互換性のため残す。
    @Suppress("UNUSED_PARAMETER")
    suspend fun checkForUpdates(receiveBetaUpdates: Boolean = false) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateState.Downloading) return@withContext
        _updateState.value = UpdateState.Checking
        available = null
        try {
            client.newCall(Request.Builder().url(CustomUpdateManifest.FEED_URL).build()).execute().use { response ->
                check(response.isSuccessful) { "個人版の更新情報を取得できませんでした" }
                val release = CustomUpdateManifest.parse(
                    response.body?.string() ?: error("更新情報が空です"), Build.SUPPORTED_ABIS.toList()
                )
                if (release.versionCode > versionCode(installedPackage())) {
                    available = release
                    _updateState.value = UpdateState.UpdateAvailable(release.versionName, release.releaseNotes, release.apkUrl)
                } else _updateState.value = UpdateState.Idle
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AppUpdater", "Custom update check failed", e)
            _updateState.value = UpdateState.Error("個人版の更新確認に失敗しました。接続を確認して再試行してください")
        }
    }

    suspend fun downloadAndInstallUpdate(apkUrl: String) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateState.Downloading) return@withContext
        val release = available
        if (release == null || apkUrl != release.apkUrl) {
            _updateState.value = UpdateState.Error("更新情報をもう一度確認してください")
            return@withContext
        }
        val directory = context.externalCacheDir ?: context.cacheDir
        val partial = File(directory, "custom-update.part")
        val apk = File(directory, "custom-update.apk")
        try {
            _updateState.value = UpdateState.Downloading(0)
            client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "APKをダウンロードできませんでした" }
                val body = response.body ?: error("APKが空です")
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        var progress = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= release.size) { "APKサイズが更新情報と一致しません" }
                            output.write(buffer, 0, count)
                            val next = (total * 100 / release.size).toInt()
                            if (next != progress) {
                                _updateState.value = UpdateState.Downloading(next)
                                progress = next
                            }
                        }
                    }
                }
            }
            CustomUpdateManifest.verifyFile(partial, release)
            verifyPackage(partial, release)
            if (apk.exists()) check(apk.delete()) { "古い更新APKを削除できませんでした" }
            check(partial.renameTo(apk)) { "更新APKを保存できませんでした" }
            _updateState.value = UpdateState.ReadyToInstall
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                })
            }
            _updateState.value = UpdateState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AppUpdater", "Custom update download failed", e)
            _updateState.value = UpdateState.Error("更新を中止しました: " + e.localizedMessage)
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, packageFlags())

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { it.toCharsString() }.toSet()

    private fun verifyPackage(file: File, release: CustomRelease) {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, packageFlags())
            ?: error("APKを読み取れませんでした")
        val installed = installedPackage()
        require(archive.packageName == context.packageName) { "別アプリのAPKです" }
        require(versionCode(archive) == release.versionCode && release.versionCode > versionCode(installed)) {
            "APKのバージョンが更新情報と一致しません"
        }
        val expected = signers(installed)
        require(expected.isNotEmpty() && expected == signers(archive)) { "署名が異なるAPKのため更新を中止しました" }
    }

    fun resetState() { _updateState.value = UpdateState.Idle }
}
