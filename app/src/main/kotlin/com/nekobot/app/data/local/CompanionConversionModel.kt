package com.nekobot.app.data.local

/** UI 只接收展示字段，不把密钥或完整模型配置留在页面状态。 */
internal data class CompanionConversionModel(val id: String, val name: String, val provider: String, val model: String)

internal class CompanionConversionCallException(val technicalDetail: String) : IllegalStateException(
    "模型调用未完成。请检查所选服务商的配置与余额后继续，已完成的部分保留。")
