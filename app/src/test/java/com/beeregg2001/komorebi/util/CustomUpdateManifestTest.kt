package com.beeregg2001.komorebi.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CustomUpdateManifestTest {
    private val bytes = "検証用APK".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val json get() = """{"schemaVersion":1,"packageName":"com.beeregg2001.Komorebi","versionCode":24,"versionName":"1.1.1-custom.24","releaseNotes":"更新","abi":"armeabi-v7a","size":${bytes.size},"sha256":"$hash","apkUrl":"https://github.com/tsuckamo/Komorebi/releases/download/custom-24/Komorebi-custom-armeabi-v7a-release.apk"}"""
    private fun parse(text: String = json) = CustomUpdateManifest.parse(text, listOf("armeabi-v7a"))
    private fun rejected(text: String) { assertThrows(Exception::class.java) { parse(text) } }

    @Test fun ownReleaseIsAccepted() { assertEquals(24L, parse().versionCode) }
    @Test fun upstreamAndOtherRepositoriesAreRejected() {
        rejected(json.replace("tsuckamo/Komorebi", "BeerEgg2001/Komorebi"))
        rejected(json.replace("tsuckamo/Komorebi", "another/Komorebi"))
    }
    @Test fun unsafeUrlVariantsAreRejected() {
        listOf(
            json.replace("https://", "http://"), json.replace("github.com/", "github.com.evil.invalid/"),
            json.replace("github.com/", "user@github.com/"), json.replace("custom-24/Komorebi", "../Komorebi"),
            json.replace("release.apk\"", "release.apk?redirect=1\"")
        ).forEach(::rejected)
    }
    @Test fun wrongPackageAndAbiAreRejected() {
        rejected(json.replace("com.beeregg2001.Komorebi", "another.package"))
        assertThrows(Exception::class.java) { CustomUpdateManifest.parse(json, listOf("arm64-v8a")) }
    }
    @Test fun incompleteOrOversizedMetadataIsRejected() {
        rejected(json.replace(hash, "bad-hash"))
        rejected(json.replace("\"size\":${bytes.size}", "\"size\":0"))
        rejected(json.replace("\"size\":${bytes.size}", "\"size\":999999999"))
        rejected(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        rejected("{}")
    }
    @Test fun truncatedOrModifiedDownloadIsRejected() {
        val file = File.createTempFile("komorebi-update", ".apk")
        try {
            file.writeBytes(bytes)
            CustomUpdateManifest.verifyFile(file, parse())
            file.writeBytes(bytes.dropLast(1).toByteArray())
            assertThrows(Exception::class.java) { CustomUpdateManifest.verifyFile(file, parse()) }
            file.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
            assertThrows(Exception::class.java) { CustomUpdateManifest.verifyFile(file, parse()) }
        } finally { file.delete() }
    }
}
