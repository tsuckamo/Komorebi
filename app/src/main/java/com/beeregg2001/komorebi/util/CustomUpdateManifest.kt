package com.beeregg2001.komorebi.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

data class CustomRelease(
    val versionCode: Long, val versionName: String, val releaseNotes: String,
    val apkUrl: String, val sha256: String, val size: Long, val abi: String
)

/** 配布先・形式・APKの整合性を検証し、本家APKへの誤更新を防ぐ。 */
object CustomUpdateManifest {
    const val FEED_URL = "https://raw.githubusercontent.com/tsuckamo/Komorebi/main/custom-update.json"
    const val PACKAGE_NAME = "com.beeregg2001.Komorebi"
    const val MAX_APK_BYTES = 256L * 1024 * 1024

    fun parse(json: String, supportedAbis: List<String>): CustomRelease {
        require(json.length <= 65536) { "更新情報が大きすぎます" }
        val root = Gson().fromJson(json, JsonObject::class.java)
        require(root["schemaVersion"].asInt == 1) { "対応していない更新情報です" }
        require(root["packageName"].asString == PACKAGE_NAME) { "別アプリの更新情報です" }
        val abi = root["abi"].asString
        require(abi in supportedAbis) { "この端末用の更新APKはありません" }
        val url = root["apkUrl"].asString
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            Regex("/tsuckamo/Komorebi/releases/download/custom-[1-9][0-9]*/Komorebi-custom-armeabi-v7a-release\\.apk").matches(uri.rawPath)) {
            "許可されていないAPK配布先です"
        }
        val hash = root["sha256"].asString.lowercase()
        require(Regex("[0-9a-f]{64}").matches(hash)) { "APKの検証情報が不正です" }
        val size = root["size"].asLong
        require(size in 1..MAX_APK_BYTES) { "APKサイズが不正です" }
        val code = root["versionCode"].asLong
        require(code in 1..Int.MAX_VALUE.toLong()) { "バージョン番号が不正です" }
        return CustomRelease(code, root["versionName"].asString, root["releaseNotes"].asString, url, hash, size, abi)
    }

    fun verifyFile(file: File, release: CustomRelease) {
        require(file.length() == release.size) { "APKのダウンロードが完了していません" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == release.sha256) { "APKの検証に失敗しました。更新を中止しました" }
    }
}
