package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMenuMoreActionsTest {
    @Test
    fun moreMenuContainsOnlyMultiselectAndDetailsWhenMetadataExists() {
        assertEquals(listOf(MessageMenuMoreAction.MULTI_SELECT, MessageMenuMoreAction.DETAILS),
            messageMenuMoreActionTypes(hasDetails = true))
    }

    @Test
    fun messageWithoutMetadataStillSupportsMultiselectWithoutEmptyDetails() {
        assertEquals(listOf(MessageMenuMoreAction.MULTI_SELECT),
            messageMenuMoreActionTypes(hasDetails = false))
    }
}
