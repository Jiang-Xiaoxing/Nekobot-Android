package com.nekobot.app.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogoTest {
    @Test
    fun `opencode accounts use their official provider icons`() {
        assertTrue(
            providerLogoAsset("opencode-zen", null, null).endsWith("/opencode.svg")
        )
        assertTrue(
            providerLogoAsset("opencode-go", null, null).endsWith("/opencode-go.svg")
        )
    }

    @Test
    fun `stepfun logos resolve from provider url and chat models`() {
        assertTrue(providerLogoAsset("stepfun", null, "step-5-preview").endsWith("/stepfun.svg"))
        assertTrue(providerLogoAsset(null, "https://api.stepfun.com/v1", null).endsWith("/stepfun.svg"))
        assertTrue(providerLogoAsset(null, null, "stepaudio-3-realtime-preview").endsWith("/stepfun.svg"))
    }

    @Test
    fun `kimi logos resolve from provider url and model names`() {
        assertTrue(providerLogoAsset("kimi", null, "kimi-k3").endsWith("/kimi.svg"))
        assertTrue(providerLogoAsset("moonshot-cn", null, null).endsWith("/kimi.svg"))
        assertTrue(providerLogoAsset(null, "https://api.moonshot.cn/v1", null).endsWith("/kimi.svg"))
        assertTrue(providerLogoAsset(null, null, "kimi-k2.7-code").endsWith("/kimi.svg"))
    }

    @Test
    fun `doubao uses the same asset in every theme`() {
        assertTrue(
            providerLogoAsset("doubao", null, null).endsWith("/doubao-dark.svg")
        )
    }
}
