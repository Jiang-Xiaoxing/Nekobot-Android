package com.nekobot.app.data.local.ai

import com.nekobot.app.R

/** Stored recovery markers retain their original identity; only their display is localized. */
internal fun relationshipNoticeText(notice: String, text: (Int) -> String): String {
    val resource = when (notice) {
        CompanionRelationshipRecovery.IN_FLIGHT -> R.string.single_role_notice_interrupted
        CompanionRelationshipRecovery.RETRY_IN_FLIGHT -> R.string.single_role_notice_retry_interrupted
        CompanionRelationshipRecovery.TIMEOUT_EXHAUSTED -> R.string.single_role_notice_timeout_exhausted
        else -> if (CompanionRelationshipRecovery.timedOut(notice)) R.string.single_role_notice_timeout else null
    }
    return resource?.let(text) ?: notice
}
