package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalExperienceArchiveJobEntity

/** Shown before the user opts in to potentially paid history processing. */
data class ExperienceBackfillInfo(
    val messageCount: Int,
    val modelName: String?,
    val inputPricePerMillionUsd: Double?,
    val outputPricePerMillionUsd: Double?,
    val job: LocalExperienceArchiveJobEntity?
)
