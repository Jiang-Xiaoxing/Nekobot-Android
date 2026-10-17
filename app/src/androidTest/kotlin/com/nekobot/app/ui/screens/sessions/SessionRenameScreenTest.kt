package com.nekobot.app.ui.screens.sessions

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.theme.NekobotTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.UUID
import java.io.File

/** Real rename menu, dialog, repository and list; every case uses an isolated profile. */
class SessionRenameScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = "rename-ui-${UUID.randomUUID()}"
    private lateinit var previousProfile: String

    @Before fun prepareProfile() {
        check(ServiceContainer.prefs.isLocalMode)
        previousProfile = ServiceContainer.prefs.activeDbName
        ServiceContainer.switchLocalDb(profile)
    }

    @After fun restoreProfile() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        ServiceContainer.switchLocalDb(previousProfile)
        ServiceContainer.prefs.removeDbProfile(profile)
        NekobotDatabase.deleteProfileFile(compose.activity, profile)
    }

    @Test fun companionRenameUpdatesTheVisibleTitle() = renameCase(companion = true)
    @Test fun characterRenameUpdatesTheVisibleTitle() = renameCase(companion = false)

    private fun renameCase(companion: Boolean) {
        val id = "renamed-session"
        val characterId = "bound-character"
        val db = NekobotDatabase.get(compose.activity, profile)
        runBlocking {
            db.characterDao().upsert(LocalCharacterEntity(characterId, "林小满", createdAt = "old", updatedAt = "old"))
            db.sessionDao().upsert(LocalSessionEntity(id, "原会话名", characterId = characterId, characterName = "林小满",
                sessionMode = if (companion) "agent" else "character", companionMode = companion,
                inheritCharacter = companion, longConversationEnabled = companion,
                createdAt = "2026-10-12", updatedAt = "2026-10-12"))
        }
        val context = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalActivityResultRegistryOwner provides compose.activity, LocalOnBackPressedDispatcherOwner provides compose.activity) {
                NekobotTheme(darkTheme = false) { SessionsScreen(onOpenChat = {}) }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("林小满", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("更多", useUnmergedTree = true)[0].performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("午后读书会")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(15_000) { runBlocking { db.sessionDao().getById(id)?.name == "午后读书会" } }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("午后读书会") and !hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The old code saves successfully yet still draws the character's name here.
        compose.onNodeWithText("午后读书会", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("林小满", runBlocking { db.characterDao().getById(characterId)?.name })
        assertEquals(characterId, runBlocking { db.sessionDao().getById(id)?.characterId })
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(compose.activity.getExternalFilesDir("validation"), "rename-${if (companion) "companion" else "character"}.webp")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
            bitmap.recycle()
        }
    }
}
