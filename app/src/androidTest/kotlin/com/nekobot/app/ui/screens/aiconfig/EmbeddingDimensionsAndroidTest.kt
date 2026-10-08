package com.nekobot.app.ui.screens.aiconfig

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.validEmbeddingDimensionsInput
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EmbeddingDimensionsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(AiModelEditorState(name = "Synthetic embedding", purpose = "embedding",
        apiKey = "fixture-key", baseUrl = "https://api.example.com/v1", model = "Qwen/Qwen3-Embedding-8B"))
    private val custom = mutableStateOf(false)
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show() = compose.setContent {
        MaterialTheme { Column {
            EmbeddingDimensionFields(state.value.model, state.value.dimensions, custom.value,
                onSelect = { value -> custom.value = value == "custom"
                    if (!custom.value) state.value = state.value.copy(dimensions = value) },
                onValueChange = { state.value = state.value.copy(dimensions = it) })
        } }
    }
    private fun reopenMenu() {
        val selected = if (custom.value) text(R.string.aimodel_editor_embedding_dimensions_custom)
                       else state.value.dimensions.ifBlank { text(R.string.aimodel_editor_embedding_dimensions_default) }
        compose.onNodeWithText(selected).performClick()
    }
    private fun saveAndReload(): AiModelEditorState {
        var selected: AiModelEditorState? = null
        compose.runOnIdle { selected = state.value }
        val db = Room.inMemoryDatabaseBuilder(compose.activity, NekobotDatabase::class.java).build()
        return try {
            runBlocking {
                val entity = selected!!.toLocalEntity(null)
                db.aiModelDao().upsert(entity)
                db.aiModelDao().getById(entity.id)!!.toEditorState(emptyList())
            }
        } finally { db.close() }
    }

    @Test fun selecting4096PersistsAndReopensAs4096() {
        show(); reopenMenu()
        compose.onNodeWithText("4096").performScrollTo().performClick()
        compose.onNodeWithText("4096").assertIsDisplayed()
        val restored = saveAndReload()
        assertEquals("4096", restored.dimensions)
        assertEquals(4096, restored.toRequest().dimensions)
    }

    @Test fun customInputPersistsAndDefaultClearsTheOverride() {
        show(); reopenMenu()
        compose.onNodeWithText(text(R.string.aimodel_editor_embedding_dimensions_custom)).performScrollTo().performClick()
        compose.onNodeWithTag("embedding-dimensions-custom-input").performTextInput("1280")
        assertEquals("1280", saveAndReload().dimensions)
        compose.onNodeWithTag("embedding-dimensions-custom-input").performTextClearance()
        compose.onNodeWithTag("embedding-dimensions-custom-input").performTextInput("-2")
        compose.runOnIdle { assertFalse(validEmbeddingDimensionsInput(state.value.dimensions)) }
        // The custom input and dropdown both say Custom: use the dropdown's tagged child text.
        compose.onNodeWithTag("embedding-dimensions-selector").performClick()
        compose.onNodeWithText(text(R.string.aimodel_editor_embedding_dimensions_default)).performScrollTo().performClick()
        assertEquals("", saveAndReload().dimensions)
        assertEquals(0, saveAndReload().toLocalEntity(null).dimensions)
    }

    @Test fun fixedBgeModelShowsItsNativeDimensionNotice() {
        state.value = state.value.copy(model = "BAAI/bge-m3", dimensions = "1536")
        show()
        compose.onNodeWithText(text(R.string.aimodel_editor_embedding_dimensions_fixed)).assertIsDisplayed()
        compose.onNodeWithTag("embedding-dimensions-selector").assertDoesNotExist()
    }
}
