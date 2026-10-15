package com.nekobot.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalProfileSwitchRegressionTest {
    @Test fun repeatedProfileRebindingDoesNotLeaveUncaughtDatabaseReads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val observedProfile = ServiceContainer.prefs.activeDbName
        // Recover only the precise UUID-named fixture left by an interrupted earlier run.
        val interruptedFixture = observedProfile.takeIf {
            it.matches(Regex("rename-(ui|persistence)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        }
        val originalProfile = if (interruptedFixture != null) PrefsManager.DEFAULT_DB_NAME else observedProfile
        val interruptedFixtures = context.databaseList().map { it.removeSuffix(".db") }.filter {
            it.matches(Regex("rename-(ui|persistence)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        }
        val fixtureId = UUID.randomUUID().toString()
        val profiles = listOf("rename-sync-a-$fixtureId", "rename-sync-b-$fixtureId")
        try {
            repeat(12) { ServiceContainer.switchLocalDb(profiles[it % profiles.size]) }
            val selected = profiles.last()
            val db = NekobotDatabase.get(context, selected)
            db.sessionDao().upsert(LocalSessionEntity("ready", "当前资料库", createdAt = "old", updatedAt = "old"))
            // Let the old/background jobs run; an uncaught failure would abort instrumentation.
            delay(300)
            assertEquals(selected, ServiceContainer.prefs.activeDbName)
            assertEquals("当前资料库", ServiceContainer.localRepository.getSession("ready")?.name)
        } finally {
            ServiceContainer.switchLocalDb(originalProfile)
            profiles.plus(interruptedFixtures).distinct().forEach { profile ->
                ServiceContainer.prefs.removeDbProfile(profile)
                NekobotDatabase.deleteProfileFile(context, profile)
            }
        }
    }
}
