package com.beeregg2001.komorebi.data.repository

import org.junit.Assert.*
import org.junit.Test

class AiReservationCancellationCommandTest {
    @Test fun acceptsSearchKeywordOnly() {
        assertEquals("イッテQ", AiReservationCancellationCommand.keyword("候補を確認します。[REQ_CANCEL_AUTO: イッテQ]"))
    }
    @Test fun allowsListingWithoutGuessingAProgram() {
        assertEquals("", AiReservationCancellationCommand.keyword("[REQ_CANCEL_AUTO: ]"))
    }
    @Test fun deleteAndSingleEpisodeCommandsAreNotAccepted() {
        assertNull(AiReservationCancellationCommand.keyword("[DELETE_RULE: 7]"))
        assertNull(AiReservationCancellationCommand.keyword("[CANCEL_SINGLE: 100]"))
    }
    @Test fun rejectsMalformedOrUnboundedCommands() {
        assertNull(AiReservationCancellationCommand.keyword("[REQ_CANCEL_AUTO: 未完了"))
        assertNull(AiReservationCancellationCommand.keyword("[REQ_CANCEL_AUTO: ${"a".repeat(201)}]"))
    }
}
