package com.beeregg2001.komorebi.data.repository

import org.junit.Assert.*
import org.junit.Test

class AiReservationCreationCommandTest {
    @Test fun acceptsSearchWordsOnly() { assertEquals("イッテQ", AiReservationCreationCommand.keyword("候補を確認 [RESERVE_AUTO: イッテQ]")) }
    @Test fun rejectsEmptyAndOversizedInput() {
        assertNull(AiReservationCreationCommand.keyword("[RESERVE_AUTO: ]"))
        assertNull(AiReservationCreationCommand.keyword("[RESERVE_AUTO: ${"a".repeat(201)}]"))
    }
    @Test fun rejectsMultipleAndParameterPayloads() {
        assertNull(AiReservationCreationCommand.keyword("[RESERVE_AUTO: 番組A][RESERVE_AUTO: 番組B]"))
        assertNull(AiReservationCreationCommand.keyword("[RESERVE_AUTO: 番組 | 99]"))
    }
    @Test fun rejectsMultilineAndPunctuationOnly() {
        assertNull(AiReservationCreationCommand.keyword("[RESERVE_AUTO: 番組\n削除]"))
        assertNull(AiReservationCreationCommand.keyword("[RESERVE_AUTO: !!!]"))
    }
}
