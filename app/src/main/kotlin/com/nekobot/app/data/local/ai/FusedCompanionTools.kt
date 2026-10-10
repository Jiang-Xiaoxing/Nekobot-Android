package com.nekobot.app.data.local.ai

internal object FusedCompanionTools {
    // 陪伴模式的能力边界，不依赖界面隐藏，也不改写玩家原有工具开关。
    val allowedNames = setOf(
        "agent_memory_read", "agent_memory_update", "agent_recall_search", "agent_history_search", "agent_history_read",
        "get_date_time", "get_weather", "search_web", "web_fetch",
        "understand_image", "read_image", "generate_image", "list_stickers", "send_sticker", "view_sticker",
        "workspace_list_files", "workspace_read_file", "workspace_parse_file", "workspace_extract_epub", "workspace_file_info"
    )
    val readNames = setOf("agent_memory_read", "db_list_memories")
    val writeNames = setOf("agent_memory_update", "db_save_memory", "db_delete_memory")
    fun definitions(original: List<Map<String, Any>>): List<Map<String, Any>> = original.filter {
        toolNameOf(it) in allowedNames
    }.map { definition ->
        val function = definition["function"] as? Map<*, *> ?: definition
        val name = function["name"] as? String
        when (name) {
            "agent_recall_search" -> buildAgentRecallToolDefinitions(currentOnly = true).first()
            in readNames -> tool(requireNotNull(name), "检索当前长久陪伴窗口的角色记忆。不会读取公共 Agent 记忆。", mapOf("query" to text("问题或主题，空则读取当前记录")), emptyList())
            in writeNames -> tool(requireNotNull(name), "根据本轮玩家原话维护当前窗口角色记忆。不能操作公共记忆或修改人工保护项。更新须引用真实旧 key。撤销旧错误使用 retract。",
                mapOf("key" to text("稳定主题键"), "target_key" to text("要维护的已有主题键，新增时为空"),
                    "operation" to text("add/update/merge/cancel/complete/retract/clarify/resolve/retain"),
                    "category" to text("user_profile/character_profile/relationship/episode/promise/open_issue"),
                    "content" to text("记忆正文"), "quote" to text("本轮玩家连续逐字原句"),
                    "question" to text("有含糊矛盾时自然核对的问题"), "conflict_id" to text("确认的待确认ID")),
                listOf("key", "operation", "category", "content", "quote"))
            else -> definition
        }
    }
    private fun text(description: String) = mapOf("type" to "string", "description" to description)
    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String>): Map<String, Any> =
        mapOf("type" to "function", "function" to mapOf("name" to name, "description" to description,
            "parameters" to mapOf("type" to "object", "properties" to properties, "required" to required)))
}
