package com.nekobot.app.ui.screens.settings

/** 自定义工具集模式管理弹窗与明细编辑弹窗的单层导航状态。 */
internal sealed interface CustomModesNavigationState {
    data object Closed : CustomModesNavigationState
    data object Managing : CustomModesNavigationState
    data class Editing(val modeId: String) : CustomModesNavigationState
}

internal sealed interface CustomModesNavigationAction {
    data object OpenManager : CustomModesNavigationAction
    data object CloseManager : CustomModesNavigationAction
    data class OpenEditor(val modeId: String) : CustomModesNavigationAction
    data object CloseEditor : CustomModesNavigationAction
}

internal fun transitionCustomModesNavigation(
    state: CustomModesNavigationState,
    action: CustomModesNavigationAction
): CustomModesNavigationState = when (action) {
    CustomModesNavigationAction.OpenManager -> CustomModesNavigationState.Managing
    CustomModesNavigationAction.CloseManager ->
        if (state == CustomModesNavigationState.Managing) CustomModesNavigationState.Closed else state
    is CustomModesNavigationAction.OpenEditor -> CustomModesNavigationState.Editing(action.modeId)
    CustomModesNavigationAction.CloseEditor ->
        if (state is CustomModesNavigationState.Editing) CustomModesNavigationState.Managing else state
}
