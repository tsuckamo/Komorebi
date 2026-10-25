package com.beeregg2001.komorebi.data.api

import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** ルール追加をHTTPクライアント内部でも再送しない。検索POSTには適用しない。 */
class EpgStationRuleWriteGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (request.method != "POST" || !request.url.encodedPath.endsWith("/api/rules") || body == null) return chain.proceed(request)
        val once = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }
        return chain.proceed(request.newBuilder().method(request.method, once).build())
    }
}
