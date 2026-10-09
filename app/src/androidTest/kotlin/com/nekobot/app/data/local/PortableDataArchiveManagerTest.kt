package com.nekobot.app.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PortableDataArchiveManagerTest {
    @Test fun fusedModeBackupRestoresOwnershipFactsSourcesAndProgressWithoutRequestCache() = runBlocking {
        val db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val characterId = "fused-portable-${UUID.randomUUID()}"
        val row = LocalSessionEntity(mergeSessionId,"Portable fused fixture",characterId=characterId,
            sessionMode="agent",inheritCharacter=true,longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now")
        db.sessionDao().upsert(row)
        val store=com.nekobot.app.data.local.ai.CompanionMemoryStore(db)
        val scope=store.ensureScope(row)
        val source=LocalMessageEntity("$mergeSessionId-source",row.id,"user","我怕辣",timestamp="2026-10-08",createdAt="2026-10-08")
        db.messageDao().upsert(source)
        store.fusedTool(row,"agent_memory_update",mapOf("key" to "diet","operation" to "add","category" to "user_profile",
            "content" to "用户怕辣","quote" to source.content),source.id)
        db.companionMemoryDao().saveReceipts(listOf(com.nekobot.app.data.local.db.CompanionReceiptEntity(scope.id,source.id,
            com.nekobot.app.data.local.ai.CompanionMemoryPolicy.fingerprint(source))))
        val output=ByteArrayOutputStream()
        manager.export(setOf(PortableDataCategory.CONVERSATIONS),"",output,"test")
        db.sessionDao().deleteById(row.id)
        manager.import(ByteArrayInputStream(output.toByteArray()),"",setOf(PortableDataCategory.CONVERSATIONS))
        val restored=requireNotNull(db.sessionDao().getById(row.id))
        assertTrue(restored.companionMode)
        assertEquals(row.id,db.companionMemoryDao().owner(characterId)?.sessionId)
        val restoredScope=store.ensureScope(restored)
        val fact=store.validatedMemories(restoredScope).single { it.current }
        assertEquals("用户怕辣",fact.content)
        assertEquals(source.id,db.companionMemoryDao().sources(fact.id).single().messageId)
        assertTrue(db.companionMemoryDao().pending(restoredScope.id).isEmpty())
        assertTrue(db.companionMemoryDao().contextMaterials(restoredScope.id).isEmpty())
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var manager: PortableDataArchiveManager
    private lateinit var workspaceRoot: File
    private lateinit var memoryFile: File
    private var originalMemory: ByteArray? = null
    private var originalRecentSessionsIncludeArchived: Boolean = false
    private lateinit var mergeSessionId: String

    @Before
    fun setUp() {
        manager = PortableDataArchiveManager(context)
        memoryFile = com.nekobot.app.data.local.ai.GlobalAgentMemoryStore.memoryFileFor(
            context,
            com.nekobot.app.ServiceContainer.prefs.activeDbName
        )
        originalMemory = memoryFile.takeIf(File::isFile)?.readBytes()
        originalRecentSessionsIncludeArchived = com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived
        mergeSessionId = "portable-merge-${UUID.randomUUID()}"
        workspaceRoot = File(context.filesDir, "workspace/$mergeSessionId")
        workspaceRoot.deleteRecursively()
        workspaceRoot.mkdirs()
        memoryFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
        originalMemory?.let(memoryFile::writeBytes) ?: memoryFile.delete()
        com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived =
            originalRecentSessionsIncludeArchived
        runBlocking {
            NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
                .sessionDao()
                .deleteById(mergeSessionId)
        }
    }

    @Test
    fun encryptedArchiveCanPreviewAndSelectivelyRestoreFiles() = runBlocking {
        // v0.8.3 exports only workspaces belonging to this profile's sessions.
        val db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        db.sessionDao().upsert(LocalSessionEntity(mergeSessionId, "Portable files", createdAt = "now", updatedAt = "now"))
        File(workspaceRoot, "notes/todo.txt").apply {
            parentFile?.mkdirs()
            writeText("portable workspace")
        }
        memoryFile.writeText("portable memory")
        val output = ByteArrayOutputStream()

        manager.export(
            selected = setOf(PortableDataCategory.WORKSPACE, PortableDataCategory.GLOBAL_MEMORY),
            password = "portable-password",
            output = output,
            appVersion = "test"
        )

        val archive = output.toByteArray()
        val preview = manager.inspect(ByteArrayInputStream(archive), "portable-password")
        assertTrue(preview.encrypted)
        assertEquals(
            setOf(PortableDataCategory.WORKSPACE, PortableDataCategory.GLOBAL_MEMORY),
            preview.categories.mapTo(linkedSetOf()) { it.category }
        )

        workspaceRoot.deleteRecursively()
        memoryFile.delete()
        manager.import(
            input = ByteArrayInputStream(archive),
            password = "portable-password",
            selected = setOf(PortableDataCategory.GLOBAL_MEMORY)
        )
        assertEquals("portable memory", memoryFile.readText())
        assertFalse(File(workspaceRoot, "notes/todo.txt").exists())

        manager.import(
            input = ByteArrayInputStream(archive),
            password = "portable-password",
            selected = setOf(PortableDataCategory.WORKSPACE)
        )
        assertEquals("portable workspace", File(workspaceRoot, "notes/todo.txt").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun credentialsRequirePassword(): Unit = runBlocking {
        manager.export(
            selected = setOf(PortableDataCategory.CREDENTIALS),
            password = "",
            output = ByteArrayOutputStream(),
            appVersion = "test"
        )
    }

    @Test
    fun appSettingsUseTheirDedicatedRestorePath() = runBlocking {
        val expected = !originalRecentSessionsIncludeArchived
        com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived = expected
        val output = ByteArrayOutputStream()
        manager.export(
            selected = setOf(PortableDataCategory.APP_SETTINGS),
            password = "",
            output = output,
            appVersion = "test"
        )

        com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived = !expected
        val result = manager.import(
            input = ByteArrayInputStream(output.toByteArray()),
            password = "",
            selected = setOf(PortableDataCategory.APP_SETTINGS)
        )

        assertEquals(expected, com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived)
        assertEquals(1, result.importedRows)
    }

    @Test
    fun mergingParentRowsKeepsNewerChildRows() = runBlocking {
        val database = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val timestamp = "2026-01-01T00:00:00Z"
        database.sessionDao().upsert(
            LocalSessionEntity(
                id = mergeSessionId,
                name = "portable merge test",
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        database.messageDao().upsert(
            LocalMessageEntity(
                id = "$mergeSessionId-before",
                sessionId = mergeSessionId,
                role = "user",
                content = "before export",
                timestamp = timestamp,
                createdAt = timestamp
            )
        )
        val output = ByteArrayOutputStream()
        manager.export(
            selected = setOf(PortableDataCategory.CONVERSATIONS),
            password = "",
            output = output,
            appVersion = "test"
        )

        database.messageDao().upsert(
            LocalMessageEntity(
                id = "$mergeSessionId-after",
                sessionId = mergeSessionId,
                role = "user",
                content = "created after export",
                timestamp = timestamp,
                createdAt = timestamp
            )
        )
        manager.import(
            input = ByteArrayInputStream(output.toByteArray()),
            password = "",
            selected = setOf(PortableDataCategory.CONVERSATIONS)
        )

        val restored = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
            .messageDao()
            .listBySession(mergeSessionId)
            .map { it.id }
            .toSet()
        assertTrue("$mergeSessionId-before" in restored)
        assertTrue("$mergeSessionId-after" in restored)
    }
}
