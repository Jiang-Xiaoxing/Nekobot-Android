package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class SafeImportStep { FILE, CONTENTS, CONFIRM, RESULT }

internal data class SafeImportUiState(
    val prepared: PreparedPortableImport? = null,
    val selected: Map<PortableDataCategory, Set<String>> = emptyMap(),
    val choices: PortableImportChoices = PortableImportChoices(),
    val plan: PortableImportPreview? = null,
    val planValid: Boolean = false,
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val result: PortableImportResult? = null,
    val route: String? = null,
    val passwordNeeded: Boolean = false,
    val step: SafeImportStep = SafeImportStep.FILE,
    val sourceUri: Uri? = null,
    val fileName: String = "",
    val passwordInput: String = "",
    val picking: Boolean = false
)

/** The same production operations can be delayed or failed deterministically by isolated tests. */
internal interface SafeImportOperations {
    suspend fun prepare(context: Context, uri: Uri, password: String): PreparedPortableImport = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { PortableDataArchiveManager(context).prepareImport(it, password) }
            ?: error(context.getString(R.string.portability_open_input_failed))
    }
    suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
        choices: PortableImportChoices, limit: Int): PortableImportPreview =
        PortableDataArchiveManager(context).previewImport(prepared, selected, choices, limit)
    suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
        progress: (String) -> Unit): PortableImportResult = PortableDataArchiveManager(context).commitImport(prepared, plan, progress)
}

class SafePortableImportViewModel internal constructor(private val operations: SafeImportOperations = object : SafeImportOperations {}) : ViewModel() {
    internal val state = MutableStateFlow(SafeImportUiState())
    private var work: Job? = null
    private var cleared = false
    private var limit = 50
    private var request = 0L
    private var committing = false
    private var bulkRecordBaseline: SafeImportUiState? = null
    private data class DifferenceReviewBaseline(val state: SafeImportUiState, val limit: Int)
    private var differenceReviewBaseline: DifferenceReviewBaseline? = null
    internal fun beginPicking(): Boolean {
        if (state.value.busy || state.value.picking) return false
        state.value = state.value.copy(picking = true)
        return true
    }
    internal fun pickerClosed() { state.value = state.value.copy(picking = false) }
    internal fun password(value: String) { if (!state.value.busy) state.value = state.value.copy(passwordInput = value) }
    internal fun continueContents() {
        if (!state.value.busy && state.value.prepared != null) state.value = state.value.copy(step = SafeImportStep.CONTENTS)
    }
    internal fun backToContents() { if (!state.value.busy) state.value = state.value.copy(step = SafeImportStep.CONTENTS) }
    internal fun back(): Boolean {
        if (state.value.busy) { cancel(); return true }
        val previous = when (state.value.step) {
            SafeImportStep.CONTENTS -> SafeImportStep.FILE
            SafeImportStep.CONFIRM -> SafeImportStep.CONTENTS
            else -> return false
        }
        state.value = state.value.copy(step = previous)
        return true
    }
    fun inspect(context: Context, uri: Uri, password: String) {
        if (state.value.busy) return
        val token = ++request
        differenceReviewBaseline = null
        limit = 50
        state.value.prepared?.close()
        state.value = SafeImportUiState(busy = true, sourceUri = uri, fileName = uri.lastPathSegment.orEmpty().substringAfterLast('/'), passwordInput = password)
        work = viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { runCatching { portableArchiveDisplayName(context, uri) }.getOrNull() }
                if (token == request && !name.isNullOrBlank()) state.value = state.value.copy(fileName = name)
                val prepared = operations.prepare(context, uri, password)
                if (token != request || cleared) { prepared.close(); return@launch }
                val selected = prepared.preview.categories.associate { summary -> summary.category to
                    if (summary.category == PortableDataCategory.CREDENTIALS) emptySet() else summary.details
                        .filter { it.key != "plot_story" }.mapTo(linkedSetOf()) { it.key } }
                state.value = state.value.copy(prepared = prepared, selected = selected, step = SafeImportStep.CONTENTS)
            } catch (_: CancellationException) {
                if (token == request) state.value = state.value.copy(error = context.getString(R.string.safe_import_cancelled))
            } catch (error: Exception) {
                if (token == request) {
                    val route = withContext(Dispatchers.IO) { recognizeDedicatedImport(context, uri) }
                    if (token == request) state.value = state.value.copy(route = route,
                        passwordNeeded = route == null && error.message?.contains("加密") == true,
                        error = if (route != null) context.getString(R.string.safe_import_dedicated) else error.message ?: context.getString(R.string.safe_import_failed))
                }
            } finally { if (token == request) state.value = state.value.copy(busy = false); if (cleared) state.value.prepared?.close() }
        }
    }
    internal fun select(category: PortableDataCategory, keys: Set<String>, checked: Boolean) {
        if (state.value.busy) return
        val old = state.value.selected[category].orEmpty()
        val next = if (checked) old + keys else old - keys
        if (old == next) return
        request++
        state.value = state.value.copy(selected = state.value.selected + (category to next), planValid = false, result = null)
    }
    internal fun choices(value: PortableImportChoices): Boolean {
        if (state.value.busy) return false
        val next = value.normalized()
        if (state.value.choices.normalized() == next) return false
        request++
        state.value = state.value.copy(choices = next, planValid = false, result = null)
        return true
    }
    internal fun saveChoices(context: Context, value: PortableImportChoices) {
        if (state.value.busy) return
        if (choices(value) || !state.value.planValid) preview(context)
    }
    internal fun beginDifferenceReview(): Boolean {
        if (cleared || state.value.busy || !state.value.planValid || state.value.plan == null) return false
        if (differenceReviewBaseline == null) differenceReviewBaseline = DifferenceReviewBaseline(state.value, limit)
        return true
    }
    internal fun beginSourceDifferenceReview(context: Context): Boolean {
        if (state.value.plan?.rows?.sourceConflictKeys.isNullOrEmpty() || !beginDifferenceReview()) return false
        limit = Int.MAX_VALUE
        preview(context)
        return true
    }
    internal fun saveDifferenceReview(context: Context, draft: PortableImportChoices): Boolean {
        if (cleared || state.value.busy || differenceReviewBaseline == null) return false
        val restorePageSize = limit == Int.MAX_VALUE && differenceReviewBaseline!!.limit != Int.MAX_VALUE
        if (restorePageSize) limit = differenceReviewBaseline!!.limit
        differenceReviewBaseline = null
        if (restorePageSize) { choices(draft); preview(context) } else saveChoices(context, draft)
        return true
    }
    /** Closing the comparison discards every temporary refresh, including completed bulk choices. */
    internal fun discardDifferenceReview() {
        val baseline = differenceReviewBaseline ?: return
        differenceReviewBaseline = null
        if (cleared) return
        request++
        bulkRecordBaseline = null
        work?.cancel()
        limit = baseline.limit
        state.value = baseline.state
    }
    /** Publish a bulk draft only after both reads succeed; cancellation leaves the old review intact. */
    internal fun chooseAllRecordFields(context: Context, draft: PortableImportChoices, useIncoming: Boolean,
                                       rowKeys: Set<String>? = null) {
        val snapshot = state.value
        val prepared = snapshot.prepared ?: return
        val previousPlan = snapshot.plan ?: return
        if (snapshot.busy || cleared || previousPlan.rows.counts.different == 0) return
        val token = ++request
        val requestedLimit = limit
        val selected = snapshot.selected.mapValues { it.value.toSet() }
        val originalDraft = draft.normalized()
        bulkRecordBaseline = snapshot
        state.value = snapshot.copy(busy = true, error = null, result = null, planValid = false,
            progress = context.getString(R.string.safe_import_checking))
        work = viewModelScope.launch {
            try {
                val (choices, plan) = withContext(Dispatchers.IO) {
                    val complete = operations.preview(context, prepared, selected, originalDraft, Int.MAX_VALUE)
                    ensureActive()
                    require(complete.rows.counts.different == previousPlan.rows.counts.different &&
                        complete.rows.differences.size == complete.rows.counts.different) {
                        context.getString(R.string.import_bulk_record_changed)
                    }
                    var next = originalDraft
                    complete.rows.differences.filter { rowKeys == null || it.key in rowKeys }.forEach { row ->
                        ensureActive()
                        next = next.withRecordChoice(row, useIncoming, complete.rows.sourceFieldChoices[row.memoryId].orEmpty())
                    }
                    next to operations.preview(context, prepared, selected, next, requestedLimit)
                }
                ensureActive()
                if (token == request && !cleared) state.value = state.value.copy(plan = plan, choices = choices,
                    planValid = true, step = SafeImportStep.CONFIRM)
            } catch (_: CancellationException) {
                if (token == request && !cleared) state.value = snapshot
            } catch (error: Exception) {
                if (token == request && !cleared) state.value = snapshot.copy(planValid = false,
                    error = error.message ?: context.getString(R.string.safe_import_failed))
            } finally {
                if (token == request) { bulkRecordBaseline = null; state.value = state.value.copy(busy = false) }
                if (cleared) prepared.close()
            }
        }
    }
    fun preview(context: Context, more: Boolean = false) {
        val snapshot = state.value
        val prepared = snapshot.prepared ?: return
        if (snapshot.busy || cleared) return
        if (more) limit += 50
        val token = ++request
        val requestedLimit = limit
        val selected = snapshot.selected.mapValues { it.value.toSet() }
        val choices = snapshot.choices.normalized()
        state.value = snapshot.copy(busy = true, error = null, result = null, planValid = false, progress = context.getString(R.string.safe_import_checking))
        work = viewModelScope.launch {
            try {
                val plan = operations.preview(context, prepared, selected, choices, requestedLimit)
                if (token == request && !cleared) state.value = state.value.copy(plan = plan, choices = choices, planValid = true, step = SafeImportStep.CONFIRM)
            } catch (_: CancellationException) {
                if (token == request) state.value = state.value.copy(error = context.getString(R.string.safe_import_cancelled), planValid = false)
            } catch (error: Exception) {
                if (token == request) state.value = state.value.copy(error = error.message ?: context.getString(R.string.safe_import_failed), planValid = false)
            } finally { if (token == request) state.value = state.value.copy(busy = false); if (cleared) prepared.close() }
        }
    }
    fun commit(context: Context) {
        val snapshot = state.value
        val prepared = snapshot.prepared ?: return
        val plan = snapshot.plan ?: return
        if (cleared || differenceReviewBaseline != null || snapshot.busy || !snapshot.planValid || snapshot.result != null || plan.issues.isNotEmpty() ||
            plan.choices.normalized() != snapshot.choices.normalized() || plan.selected.filterValues { it.isNotEmpty() } != snapshot.selected.filterValues { it.isNotEmpty() }) return
        val token = ++request
        committing = true
        state.value = snapshot.copy(busy = true, planValid = false, error = null)
        work = viewModelScope.launch {
            try {
                val result = operations.commit(context, prepared, plan) { message -> if (token == request) state.value = state.value.copy(progress = message) }
                if (token == request) state.value = state.value.copy(result = result, step = SafeImportStep.RESULT)
            } catch (_: CancellationException) {
                if (token == request) state.value = state.value.copy(result = prepared.result, step = if (prepared.result != null) SafeImportStep.RESULT else state.value.step,
                    error = if (prepared.result == null) context.getString(R.string.safe_import_cancelled) else null)
            } catch (error: Exception) {
                if (token == request) state.value = state.value.copy(result = prepared.result, step = if (prepared.result != null) SafeImportStep.RESULT else state.value.step,
                    error = if (prepared.result != null) context.getString(R.string.safe_import_reload_failed) else error.message ?: context.getString(R.string.safe_import_failed))
            } finally { committing = false; if (token == request) state.value = state.value.copy(busy = false); if (cleared) prepared.close() }
        }
    }
    fun cancel() {
        val baseline = bulkRecordBaseline
        work?.cancel()
        // Writes retain the busy guard until the transaction and attachment recovery finish.
        if (!committing) {
            request++
            state.value = baseline ?: state.value.copy(busy = false, planValid = false, error = null, progress = "")
            bulkRecordBaseline = null
        }
    }
    override fun onCleared() { cleared = true; work?.cancel(); if (work?.isCompleted != false) state.value.prepared?.close() }
}

