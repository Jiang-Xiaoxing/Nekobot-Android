package com.nekobot.app.ui.screens.chat

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.R
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CompanionFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(600)
        val bmp = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
    }
    @Test fun quietEntryOnlyOpensOnTapAndRoutesActionsWithoutSending() {
        var wrote = 0; var copied = 0
        val contact = compose.activity.getString(R.string.companion_feedback_email).ifBlank { "feedback@example.invalid" }
        compose.setContent { NekobotTheme { CompanionFeedbackEntry(email=contact,onWrite={wrote++},onCopy={copied++}) } }
        compose.onNodeWithText(contact).assertDoesNotExist()
        compose.onNodeWithText("测试反馈与建议").performClick()
        compose.onNodeWithText(contact).assertIsDisplayed()
        capture("feedback-open")
        compose.onNodeWithText("写邮件").performScrollTo().performClick()
        compose.onNodeWithText("复制邮箱").performClick()
        assertEquals(1,wrote); assertEquals(1,copied)
        compose.onNodeWithContentDescription("关闭反馈").performScrollTo().performClick()
        compose.onNodeWithText(contact).assertDoesNotExist()
        val draft = companionFeedbackIntent("feedback@example.invalid")
        assertEquals(Intent.ACTION_SENDTO,draft.action)
        assertEquals("mailto",draft.data?.scheme)
        assertTrue(draft.dataString.orEmpty().startsWith("mailto:feedback@example.invalid?"))
        assertNull(draft.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }
    @Test fun actualSettingsPageKeepsEntrySmallAndWorksInDarkLargeText() {
        compose.setContent { NekobotTheme(darkTheme=true) {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.6f)) {
                CompanionSettingsScreen("林小满",false,{}, {},{},{},{},{})
            }
        } }
        compose.onNodeWithText("测试反馈与建议").performScrollTo().assertIsDisplayed()
        capture("feedback-quiet-dark")
        compose.onNodeWithText("测试反馈与建议").performClick()
        compose.onNodeWithText("复制邮箱").performScrollTo().assertIsDisplayed()
        capture("feedback-dark-large")
        compose.onNodeWithContentDescription("关闭反馈").performScrollTo().performClick()
        compose.onNodeWithText("测试反馈与建议").assertIsDisplayed()
    }
    @Test fun emptyAddressKeepsInstructionsVisibleAndDisablesContactActions() {
        var wrote = false; var copied = false
        compose.setContent { NekobotTheme {
            CompanionFeedbackEntry(email = "  ", onWrite = { wrote = true }, onCopy = { copied = true })
        } }
        compose.onNodeWithText("测试反馈与建议").assertIsDisplayed().performClick()
        compose.onNodeWithText("反馈邮箱尚未配置").assertIsDisplayed()
        compose.onNodeWithText("写邮件").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("复制邮箱").assertIsNotEnabled()
        assertFalse(wrote); assertFalse(copied)
    }

}
