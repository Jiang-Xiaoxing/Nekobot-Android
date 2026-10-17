package com.nekobot.app.ui.screens.chat

/** 显示开关与角色视角都开启才展示；与新请求档位无关，保存的思考原文不变。 */
internal fun companionThoughtsEnabled(showReasoning: Boolean, characterThinking: Boolean): Boolean =
    showReasoning && characterThinking
