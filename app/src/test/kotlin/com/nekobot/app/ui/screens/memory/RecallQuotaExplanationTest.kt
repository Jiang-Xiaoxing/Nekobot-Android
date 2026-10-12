package com.nekobot.app.ui.screens.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class RecallQuotaExplanationTest {
    @Test fun defaultTextMatchesApprovedCopy() {
        assertEquals(listOf(
            "回查次数 3：角色每句最多翻几次记忆",
            "总份额 4000 ≈ 3000～4000 个汉字，约一篇短文",
            "单次 1500 ≈ 七八条普通记忆",
            "只算查回来的资料，不算角色回复"), recallQuotaExplanation(3, 4000))
    }

    @Test fun numbersFollowSettings() {
        assertEquals("单次 800 ≈ 约 4 条普通记忆", recallQuotaExplanation(2, 800)[2])
    }
}
