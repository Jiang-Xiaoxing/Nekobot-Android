package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import org.junit.Assert.*
import org.junit.Test

class CompanionMessageStyleTest {
    private val reply = Message(role = "assistant", content = "我陪你。")

    @Test fun firstReplyShowsAvatar() = assertTrue(companionShowsAvatar(reply, null, false))
    @Test fun consecutiveRepliesShareAvatar() = assertFalse(companionShowsAvatar(reply, reply, false))
    @Test fun userInterruptStartsNewGroup() = assertTrue(companionShowsAvatar(reply, Message(role = "human"), false))
    @Test fun outgoingHasNoAvatar() = assertFalse(companionShowsAvatar(Message(role = "user"), reply, true))
    @Test fun timeSeparatorStartsNewGroup() = assertTrue(companionShowsAvatar(reply, reply, true))
    @Test fun differentSenderStartsNewGroup() = assertTrue(companionShowsAvatar(reply.copy(sender = "小满"), reply.copy(sender = "晚星"), false))
    @Test fun senderWhitespaceDoesNotBreakGroup() = assertFalse(companionShowsAvatar(reply.copy(sender = " 小满 "), reply.copy(sender = "小满"), false))
    @Test fun senderNameFallbackIsStable() = assertFalse(companionShowsAvatar(reply.copy(sender = " ", name = "小满"), reply.copy(name = "小满"), false))
    @Test fun hiddenSystemMessageBreaksGroup() = assertTrue(companionShowsAvatar(reply, Message(role = "system"), false))
    @Test fun streamingReplyKeepsConsecutiveGroup() = assertFalse(companionShowsAvatar(reply.copy(id = ChatViewModel.STREAMING_ID), reply, false))
    @Test fun shortIntervalHasNoSeparator() = assertFalse(section("21:06", "21:02"))
    @Test fun fiveMinuteIntervalHasSeparator() = assertTrue(section("21:06", "21:01"))
    @Test fun crossedDayHasSeparator() = assertTrue(companionStartsTimeSection(false, false, "2026-10-13", "2026-10-12", "00:01", "23:59"))
    @Test fun unknownTimeHasSeparator() = assertTrue(section(null, "21:01"))
    @Test fun invalidTimeHasSeparator() = assertTrue(section("25:10", "21:01"))
    @Test fun backwardsTimeHasSeparator() = assertTrue(section("21:01", "21:06"))
    @Test fun streamingHasNoNewSeparator() = assertFalse(companionStartsTimeSection(true, true, null, null, null, null))
    @Test fun firstVisibleMessageHasSeparator() = assertTrue(companionStartsTimeSection(true, false, "2026-10-13", "2026-10-13", "21:06", "21:06"))
    @Test fun phoneWidthLeavesAvatarAndMargins() {
        assertEquals(264, companionBubbleMaxWidthDp(360, false))
        assertEquals(296, companionBubbleMaxWidthDp(360, true))
    }
    @Test fun tabletWidthIsCapped() = assertEquals(360, companionBubbleMaxWidthDp(900, false))
    @Test fun narrowWidthDoesNotForceMinimum() {
        assertEquals(84, companionBubbleMaxWidthDp(180, false))
        assertEquals(1, companionBubbleMaxWidthDp(40, false))
    }
    @Test fun uploadedPortraitHasPriority() = assertEquals("file:///portrait.jpg", companionPhotoPath("file:///portrait.jpg", "file:///avatar.jpg"))
    @Test fun legacyIconFallsBackToPhoto() = assertEquals("file:///avatar.jpg", companionPhotoPath("fas fa-cat", "file:///avatar.jpg"))
    @Test fun missingPhotoFallsBackToInitial() = assertNull(companionPhotoPath(" ", "fasfa-cat"))
    @Test fun avatarPhotoWorksWithoutPortrait() = assertEquals("content://photo/1", companionPhotoPath(null, "content://photo/1"))

    private fun section(time: String?, previous: String?) =
        companionStartsTimeSection(false, false, "2026-10-13", "2026-10-13", time, previous)
}
