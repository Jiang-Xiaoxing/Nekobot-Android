package com.nekobot.app.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production dialog with synthetic records only; no database or importer is used. */
class PortableImportBulkRecordChoiceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val total = 125
    private fun plan() = PortableImportPreview(
        rows = PortableImportRowPlan("rows", PortableImportCounts(different = total),
            listOf(PortableImportRowDifference("row-0", "companion_memories", "Synthetic record",
                listOf(PortableImportFieldDifference("content", "Synthetic local value", "Synthetic imported value")))), emptyList(), 0),
        fileCounts = PortableImportCounts(), fileDifferences = emptyList(), settingDifferences = emptyList(), storyDifferent = false,
        issues = emptyList(), dependencyOptions = emptyList(), fingerprint = "synthetic", preparedId = "synthetic",
        selected = emptyMap(), choices = PortableImportChoices(), fileBefore = emptyMap(), fileAfter = emptyMap(),
        settingsBefore = JsonObject(), settingsIncoming = null, useStory = false, storySessionIds = emptySet(),
        credentialFingerprint = "", credentialTargets = emptyMap())
    private fun confirmationAction(resource: Int): SemanticsNodeInteraction {
        val message = compose.activity.getString(R.string.import_bulk_record_confirm, total)
        val dialog = isDialog() and hasAnyDescendant(hasText(message))
        return compose.onNode(hasText(compose.activity.getString(resource)) and hasClickAction() and hasAnyAncestor(dialog))
    }
    @Test fun cancellingBulkConfirmationLeavesTheIndividualDraftAndSavedChoicesUnchanged() {
        var bulkCalls = 0; var saves = 0
        val choices = PortableImportChoices(files = setOf("explicit-file"), settings = setOf("explicit-setting"))
        val plan = plan()
        compose.setContent { NekobotTheme {
            PortableImportDifferences(plan, choices, false, onDismiss = {}, onSave = { saves++ }, onMore = {},
                onBulkRecords = { _, _ -> bulkCalls++ })
        } }
        compose.onNodeWithTag("safe-difference-row-0-content-replace").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.import_bulk_record_confirm, total)).assertExists()
        assertEquals(0, bulkCalls)
        confirmationAction(R.string.common_cancel).performClick()
        compose.onNodeWithTag("safe-difference-row-0-content-replace").performScrollTo().assertIsSelected()
        assertEquals(0, bulkCalls); assertEquals(0, saves); assertTrue(choices.fields.isEmpty())
    }
    @Test fun confirmationAndKeepPassTheCurrentDraftWithoutWritingOrChangingOtherChoices() {
        val callbacks = mutableListOf<Pair<PortableImportChoices, Boolean>>()
        val choices = PortableImportChoices(files = setOf("explicit-file"), settings = setOf("explicit-setting"), restoreCredentials = true)
        val plan = plan()
        compose.setContent { NekobotTheme {
            PortableImportDifferences(plan, choices, false, onDismiss = {}, onSave = {}, onMore = {},
                onBulkRecords = { draft, incoming -> callbacks += draft to incoming })
        } }
        compose.onNodeWithTag("safe-difference-row-0-content-replace").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        confirmationAction(R.string.common_confirm).performClick()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().performClick()
        assertEquals(listOf(true, false), callbacks.map { it.second })
        callbacks.forEach { (draft, _) ->
            assertEquals(choices.copy(fields = mapOf("row-0" to setOf("content"))), draft)
        }
    }
    @Test fun busyStateDisablesBothBulkActionsAndThePendingConfirmation() {
        val busy = mutableStateOf(false); var calls = 0
        val plan = plan()
        compose.setContent { NekobotTheme {
            PortableImportDifferences(plan, PortableImportChoices(), busy.value, onDismiss = {}, onSave = {}, onMore = {},
                onBulkRecords = { _, _ -> calls++ })
        } }
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performClick()
        compose.runOnIdle { busy.value = true }
        confirmationAction(R.string.common_confirm).assertIsNotEnabled()
        confirmationAction(R.string.common_cancel).performClick()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().assertIsNotEnabled()
        assertEquals(0, calls)
    }
}
