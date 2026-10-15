package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Append to the published upstream v52 schema; never reuse personal-version migrations. */
internal object UpstreamCompanionSchema {
    internal val statements = listOf(
        """ALTER TABLE `local_sessions` ADD COLUMN `companion_mode` INTEGER NOT NULL DEFAULT 0""",
        """ALTER TABLE `local_sessions` ADD COLUMN `list_title_override` TEXT DEFAULT NULL""",
        """ALTER TABLE `local_characters` ADD COLUMN `chat_model_queue` TEXT DEFAULT NULL""",
        """ALTER TABLE `local_experience_archives` ADD COLUMN `character_id` TEXT""",
        """CREATE TABLE IF NOT EXISTS `companion_scopes` (`id` TEXT NOT NULL, `profile` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `characterId` TEXT NOT NULL, `baselineRowId` INTEGER NOT NULL, `archiveRowId` INTEGER NOT NULL, `archiveMessageId` TEXT, `historyAccess` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `error` TEXT, `updatedAt` TEXT NOT NULL, `embeddingModelId` TEXT, PRIMARY KEY(`id`))""",
        """CREATE INDEX IF NOT EXISTS `index_companion_scopes_sessionId` ON `companion_scopes` (`sessionId`)""",
        """CREATE TABLE IF NOT EXISTS `companion_memories` (`id` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `factKey` TEXT NOT NULL, `version` INTEGER NOT NULL, `kind` TEXT NOT NULL, `subject` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, `eventDate` TEXT, `annualMonthDay` TEXT, `tagsJson` TEXT NOT NULL, `current` INTEGER NOT NULL, `valid` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `category` TEXT NOT NULL DEFAULT 'user_profile', `alwaysProvide` INTEGER NOT NULL DEFAULT 0, `manual` INTEGER NOT NULL DEFAULT 0, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `provenance` TEXT NOT NULL DEFAULT 'native', `expiresAt` TEXT, `coreExcluded` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))""",
        """CREATE INDEX IF NOT EXISTS `index_companion_memories_scopeId` ON `companion_memories` (`scopeId`)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_memories_scopeId_factKey_version` ON `companion_memories` (`scopeId`, `factKey`, `version`)""",
        """CREATE TABLE IF NOT EXISTS `companion_sources` (`memoryId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `quote` TEXT NOT NULL, `spokenDate` TEXT, `role` TEXT NOT NULL, PRIMARY KEY(`memoryId`, `messageId`))""",
        """CREATE INDEX IF NOT EXISTS `index_companion_sources_messageId` ON `companion_sources` (`messageId`)""",
        """CREATE TABLE IF NOT EXISTS `companion_receipts` (`scopeId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, PRIMARY KEY(`scopeId`, `messageId`))""",
        """CREATE INDEX IF NOT EXISTS `index_companion_receipts_messageId` ON `companion_receipts` (`messageId`)""",
        """CREATE TABLE IF NOT EXISTS `companion_forgotten_sources` (`scopeId` TEXT NOT NULL, `messageId` TEXT NOT NULL, PRIMARY KEY(`scopeId`, `messageId`))""",
        """CREATE TABLE IF NOT EXISTS `companion_pending` (`scopeId` TEXT NOT NULL, `messageId` TEXT NOT NULL, PRIMARY KEY(`scopeId`, `messageId`))""",
        """CREATE TABLE IF NOT EXISTS `companion_vectors` (`scopeId` TEXT NOT NULL, `itemType` TEXT NOT NULL, `itemId` TEXT NOT NULL, `modelKey` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `vectorJson` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`scopeId`, `itemType`, `itemId`, `modelKey`))""",
        """CREATE TABLE IF NOT EXISTS `companion_clarifications` (`scopeId` TEXT NOT NULL, `factKey` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `question` TEXT NOT NULL, `questionMessageId` TEXT, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`scopeId`, `factKey`))""",
        """CREATE TABLE IF NOT EXISTS `companion_owners` (`characterId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, PRIMARY KEY(`characterId`))""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_owners_sessionId` ON `companion_owners` (`sessionId`)""",
        """CREATE TABLE IF NOT EXISTS `companion_context_materials` (`scopeId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `configuration` TEXT NOT NULL, `startMessageId` TEXT NOT NULL, `content` TEXT NOT NULL, `timeKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `retrieval` TEXT NOT NULL, `userPayload` TEXT NOT NULL, PRIMARY KEY(`scopeId`, `messageId`))""",
        """CREATE TABLE IF NOT EXISTS `companion_relationship_state` (`sessionId` TEXT NOT NULL, `characterId` TEXT NOT NULL, `version` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `automatic` INTEGER NOT NULL, `interval` INTEGER NOT NULL, `modelId` TEXT, `awaitingHistory` INTEGER NOT NULL, `cursorId` TEXT, `baselineJson` TEXT NOT NULL, `legacy` INTEGER NOT NULL, `mood` TEXT NOT NULL, `energy` TEXT NOT NULL, `feelingReason` TEXT NOT NULL, `feelingSources` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `notice` TEXT, `correctionResult` TEXT, `moodIntensity` REAL DEFAULT NULL, `historyScopeVersion` INTEGER NOT NULL DEFAULT 0, `historyReviewRequired` INTEGER NOT NULL DEFAULT 0, `narrativeJson` TEXT DEFAULT NULL, `legacySnapshotJson` TEXT DEFAULT NULL, PRIMARY KEY(`sessionId`), FOREIGN KEY(`sessionId`) REFERENCES `local_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        """CREATE TABLE IF NOT EXISTS `companion_relationship_assessments` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `narrativeJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `correctionKey` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `companion_relationship_state`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_relationship_assessments_sessionId_revision` ON `companion_relationship_assessments` (`sessionId`, `revision`)""",
        """CREATE TABLE IF NOT EXISTS `companion_relationship_events` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `eventKey` TEXT NOT NULL, `revision` INTEGER NOT NULL, `summary` TEXT NOT NULL, `reason` TEXT NOT NULL, `kind` TEXT NOT NULL, `deltasJson` TEXT NOT NULL, `sourcesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `corrected` INTEGER NOT NULL, `impactsJson` TEXT DEFAULT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `companion_relationship_state`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_relationship_events_sessionId_eventKey_revision` ON `companion_relationship_events` (`sessionId`, `eventKey`, `revision`)""",
        """CREATE TABLE IF NOT EXISTS `companion_transfer_jobs` (`id` TEXT NOT NULL, `profile` TEXT NOT NULL, `sourceSessionId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `targetCharacterId` TEXT NOT NULL, `targetSessionId` TEXT NOT NULL, `targetName` TEXT NOT NULL, `phase` TEXT NOT NULL, `directory` TEXT NOT NULL, `reportJson` TEXT NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_companion_transfer_jobs_profile_sourceSessionId_fingerprint` ON `companion_transfer_jobs` (`profile`, `sourceSessionId`, `fingerprint`)""",
        """UPDATE local_experience_archives SET character_id = (SELECT character_id FROM local_sessions WHERE local_sessions.id = local_experience_archives.session_id)"""
    )

    fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach { db.execSQL(it) }
    }
}
