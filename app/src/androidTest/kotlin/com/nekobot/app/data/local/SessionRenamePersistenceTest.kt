package com.nekobot.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.*
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.ui.screens.sessions.buildSessionListRows
import com.nekobot.app.ui.screens.sessions.SessionDetailViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionRenamePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profile = "rename-persistence-${UUID.randomUUID()}"
    private lateinit var previousProfile: String
    private fun db() = NekobotDatabase.get(context, profile)

    @Before fun setUp() = runBlocking {
        check(ServiceContainer.prefs.isLocalMode)
        previousProfile = ServiceContainer.prefs.activeDbName
        ServiceContainer.switchLocalDb(profile)
        db().characterDao().upsert(LocalCharacterEntity("c", "林小满", createdAt = "old", updatedAt = "old"))
        db().sessionDao().upsert(LocalSessionEntity("main", "旧自动标题", characterId = "c", characterName = "林小满",
            createdAt = "old", updatedAt = "old"))
        db().sessionDao().upsert(LocalSessionEntity("other", "另一条会话", characterId = "c", characterName = "林小满",
            createdAt = "old", updatedAt = "old"))
        db().messageDao().upsert(LocalMessageEntity("raw", "main", "user", "原有聊天", timestamp = "old", createdAt = "old"))
    }

    @After fun tearDown() {
        ServiceContainer.switchLocalDb(previousProfile)
        ServiceContainer.prefs.removeDbProfile(profile)
        NekobotDatabase.deleteProfileFile(context, profile)
    }

    @Test fun explicitRenamePersistsAfterDatabaseReopenAndLeavesCharacterAndOtherConversationIntact() = runBlocking {
        val originalOther = db().sessionDao().getById("other")
        ServiceContainer.unified.updateSession("main", UpdateSessionRequest(name = "  午后读书会  ", updateListTitle = true))
        NekobotDatabase.closeProfile(profile)
        val saved = requireNotNull(db().sessionDao().getById("main"))
        assertEquals("午后读书会", saved.name)
        assertEquals("午后读书会", saved.listTitleOverride)
        assertEquals("c", saved.characterId)
        assertEquals("林小满", db().characterDao().getById("c")?.name)
        assertEquals(originalOther, db().sessionDao().getById("other"))
        assertEquals("原有聊天", db().messageDao().getById("raw")?.content)
        assertEquals(10, saved.autoNameInterval)
    }

    @Test fun automaticMetadataAndUnchangedSettingsDoNotReplaceTheExplicitTitle() = runBlocking {
        ServiceContainer.localRepository.updateSession("main", name = "午后读书会", updateListTitle = true)
        ServiceContainer.localRepository.updateSession("main", name = "新的自动标题", favorite = true)
        val session = requireNotNull(ServiceContainer.localRepository.getSession("main"))
        assertEquals("新的自动标题", session.name)
        assertEquals("午后读书会", session.displayName)
        val row = buildSessionListRows(listOf(session), emptyList()).single()
        assertEquals("午后读书会", row.listTitle)
        assertEquals("林小满", row.characterLabel)
        ServiceContainer.localRepository.updateSession("other", name = "另一条会话", pinned = true)
        assertNull(db().sessionDao().getById("other")?.listTitleOverride)
    }

    @Test fun portableArchiveRestoresTheExplicitTitleAlongsideTheOriginalMessages() = runBlocking {
        ServiceContainer.localRepository.updateSession("main", name = "午后读书会", updateListTitle = true)
        val manager = PortableDataArchiveManager(context)
        val bytes = ByteArrayOutputStream()
        val categories = setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS)
        manager.export(categories, "", bytes, "rename-test")
        db().sessionDao().deleteById("main")
        manager.import(ByteArrayInputStream(bytes.toByteArray()), "", categories)
        assertEquals("午后读书会", db().sessionDao().getById("main")?.listTitleOverride)
        assertEquals("原有聊天", db().messageDao().getById("raw")?.content)
        assertEquals("林小满", db().characterDao().getById("c")?.name)
    }

    @Test fun detailSaveOnlyOverridesTheVisibleTitleWhenTheNameIsEdited() = runBlocking {
        val viewModel = withContext(Dispatchers.Main) { SessionDetailViewModel() }
        val store = ViewModelStore().apply { put("rename-details", viewModel) }
        try {
            withContext(Dispatchers.Main) { viewModel.load("main") }
            withTimeout(10_000) { viewModel.session.first { it?.id == "main" } }
            val unchanged = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { viewModel.save { unchanged.complete(Unit) } }
            withTimeout(10_000) { unchanged.await() }
            assertNull(db().sessionDao().getById("main")?.listTitleOverride)
            val edited = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                viewModel.name.value = "会话详情里改的新名"
                viewModel.save { edited.complete(Unit) }
            }
            withTimeout(10_000) { edited.await() }
            assertEquals("会话详情里改的新名", db().sessionDao().getById("main")?.listTitleOverride)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
