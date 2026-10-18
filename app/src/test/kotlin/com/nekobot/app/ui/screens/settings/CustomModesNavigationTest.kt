package com.nekobot.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomModesNavigationTest {
    @Test
    fun closingEditorReturnsToManagerThenClosingManagerReturnsToSettings() {
        val manager = transitionCustomModesNavigation(
            CustomModesNavigationState.Closed,
            CustomModesNavigationAction.OpenManager
        )
        val editor = transitionCustomModesNavigation(
            manager,
            CustomModesNavigationAction.OpenEditor("custom:existing")
        )

        val returnedToManager = transitionCustomModesNavigation(
            editor,
            CustomModesNavigationAction.CloseEditor
        )
        assertEquals(CustomModesNavigationState.Managing, returnedToManager)
        assertEquals(
            CustomModesNavigationState.Closed,
            transitionCustomModesNavigation(returnedToManager, CustomModesNavigationAction.CloseManager)
        )
    }

    @Test
    fun createdModeUsesEditorRouteAndClosingReturnsToManager() {
        val manager = transitionCustomModesNavigation(
            CustomModesNavigationState.Closed,
            CustomModesNavigationAction.OpenManager
        )
        // 创建成功后，管理弹窗将新 id 交给同一个编辑入口。
        val editor = transitionCustomModesNavigation(
            manager,
            CustomModesNavigationAction.OpenEditor("custom:created")
        )

        assertEquals(CustomModesNavigationState.Editing("custom:created"), editor)
        assertEquals(
            CustomModesNavigationState.Managing,
            transitionCustomModesNavigation(editor, CustomModesNavigationAction.CloseEditor)
        )
    }
}
