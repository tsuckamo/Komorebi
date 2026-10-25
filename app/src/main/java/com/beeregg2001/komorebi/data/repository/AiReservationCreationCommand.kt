package com.beeregg2001.komorebi.data.repository

/** AIの出力は検索語に限定し、登録条件や確認トークンとして使わない。 */
object AiReservationCreationCommand {
    val pattern = Regex("""\[RESERVE_AUTO:\s*([^\]\r\n|]*)\]""")
    fun keyword(text: String): String? {
        val matches = pattern.findAll(text).toList()
        if (matches.size != 1) return null
        return matches.single().groupValues[1].trim().takeIf { it.length in 1..200 && EpgProgramSearch.normalize(it).isNotEmpty() }
    }
}
