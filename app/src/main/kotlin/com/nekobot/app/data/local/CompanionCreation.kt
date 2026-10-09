package com.nekobot.app.data.local

import androidx.annotation.StringRes
import com.nekobot.app.R
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase

/** 创建页和事务提交共用的资格检查；不以消息数判断角色是否已使用。 */
internal class CompanionCreation(private val db: NekobotDatabase) {
    suspend fun eligibility(characterId: String?): CompanionEligibility {
        if (characterId.isNullOrBlank() || db.characterDao().getById(characterId) == null) {
            return CompanionEligibility.MissingCharacter
        }
        db.companionMemoryDao().owner(characterId)?.let { owner ->
            val session = db.sessionDao().getById(owner.sessionId)
            return if (session != null) CompanionEligibility.Existing(session.id)
            else CompanionEligibility.ReservedCharacter
        }
        val sessions = db.sessionDao().listForCompanionEligibility(characterId)
        if (sessions.isEmpty()) return CompanionEligibility.Available
        val candidates = sessions.filter { it.canTransferToCompanion() }
        return CompanionEligibility.Used(candidates)
    }

    /** 必须在与 INSERT 相同的事务内调用，避免预览后另一操作创建了会话。 */
    suspend fun requireNew(characterId: String?) {
        val state = eligibility(characterId)
        if (state != CompanionEligibility.Available) throw CompanionCreationException(state)
    }
}

fun LocalSessionEntity.canTransferToCompanion(): Boolean = !companionMode &&
    !characterId.isNullOrBlank() && characterIds.isNullOrBlank() &&
    (sessionMode.equals("character", true) || (sessionMode.equals("agent", true) && inheritCharacter))

sealed interface CompanionEligibility {
    data object Available : CompanionEligibility
    data object MissingCharacter : CompanionEligibility
    data object ReservedCharacter : CompanionEligibility
    data class Existing(val sessionId: String) : CompanionEligibility
    data class Used(val candidates: List<LocalSessionEntity>) : CompanionEligibility
}

class CompanionCreationException(val eligibility: CompanionEligibility) : IllegalStateException() {
    @get:StringRes
    val messageRes: Int get() = when (eligibility) {
        CompanionEligibility.MissingCharacter -> R.string.companion_create_missing
        CompanionEligibility.ReservedCharacter -> R.string.companion_create_reserved
        is CompanionEligibility.Existing -> R.string.companion_create_existing
        is CompanionEligibility.Used -> R.string.companion_create_used
        CompanionEligibility.Available -> R.string.companion_create_changed
    }
}
