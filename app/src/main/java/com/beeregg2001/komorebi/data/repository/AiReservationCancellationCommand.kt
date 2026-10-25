package com.beeregg2001.komorebi.data.repository

/** AIには検索語だけを渡させる。ルールID・確認トークン・削除命令は受け取らない。 */
object AiReservationCancellationCommand {
    val pattern = Regex("""\[REQ_CANCEL_AUTO:\s*([^\]\r\n]*)\]""")

    fun keyword(text: String): String? = pattern.find(text)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.length <= 200 }
}
