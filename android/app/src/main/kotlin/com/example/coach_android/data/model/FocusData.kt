package com.example.coach_android.data.model

import kotlinx.serialization.Serializable

// Where the day's override request stands, as the server tells it.
@Serializable
enum class OverridePhase(val wire: String) {
    // No live request: the next tap grants (first of the day) or arms.
    IDLE("idle"),

    // A request is armed and its countdown is running.
    COOLING("cooling"),

    // The countdown lapsed; a tap redeems until the window closes.
    READY("ready"),
    ;

    companion object {
        fun fromWire(value: String?): OverridePhase? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class FocusData(
    val isFocusing: Boolean = false,
    val sinceLastChange: Int = 0,
    val focusTimeLeft: Int = 0,
    val numFocuses: Int = 0,
    val lastFocusEndTime: Int = 0,
    val agentReleaseTimeLeft: Int? = null,
    // The override as the server runs it: the first of each local day is free,
    // each redeemed one makes the next wait 30s longer, and the wait starts at
    // the tap that asks — never in the background.
    val overridesToday: Int = 0,
    val overridePhase: OverridePhase = OverridePhase.IDLE,
    val cooldownSeconds: Int = 0,
    val cooldownRemaining: Int = 0,
    val redeemRemaining: Int = 0,
    val nextCooldownSeconds: Int = 0,
) {
    val isAgentLocked: Boolean get() = agentReleaseTimeLeft == null
    val isFocused: Boolean get() = isFocusing || isAgentLocked

    fun hasSignificantDifference(other: FocusData): Boolean =
        isFocusing != other.isFocusing ||
            isAgentLocked != other.isAgentLocked ||
            kotlin.math.abs(sinceLastChange - other.sinceLastChange) > 10 ||
            kotlin.math.abs(focusTimeLeft - other.focusTimeLeft) > 30 ||
            kotlin.math.abs((agentReleaseTimeLeft ?: 0) - (other.agentReleaseTimeLeft ?: 0)) > 30 ||
            numFocuses != other.numFocuses ||
            // The discrete override state, not the remaining seconds: those tick
            // once a second and the UI counts them down locally between frames.
            overridesToday != other.overridesToday ||
            overridePhase != other.overridePhase

    fun updateFromWebSocket(data: Map<String, Any?>): FocusData {
        val nowFocusing = data["focusing"] as? Boolean ?: isFocusing
        val currentTime = (System.currentTimeMillis() / 1000).toInt()
        val newLastFocusEndTime = if (isFocusing && !nowFocusing) currentTime else lastFocusEndTime

        return copy(
            isFocusing = nowFocusing,
            sinceLastChange = (data["since_last_change"] as? Number)?.toInt() ?: sinceLastChange,
            focusTimeLeft = (data["focus_time_left"] as? Number)?.toInt() ?: focusTimeLeft,
            numFocuses = (data["num_focuses"] as? Number)?.toInt() ?: numFocuses,
            lastFocusEndTime = newLastFocusEndTime,
            agentReleaseTimeLeft = parseAgentReleaseTimeLeft(data),
            overridesToday = (data["overrides_today"] as? Number)?.toInt() ?: overridesToday,
            overridePhase = OverridePhase.fromWire(data["override_phase"] as? String) ?: overridePhase,
            cooldownSeconds = (data["override_cooldown_seconds"] as? Number)?.toInt() ?: cooldownSeconds,
            cooldownRemaining = (data["override_cooldown_remaining"] as? Number)?.toInt() ?: cooldownRemaining,
            redeemRemaining = (data["override_redeem_remaining"] as? Number)?.toInt() ?: redeemRemaining,
            nextCooldownSeconds = (data["override_next_cooldown_seconds"] as? Number)?.toInt() ?: nextCooldownSeconds,
        )
    }

    companion object {
        // Server sends `agent_release_time_left` as a JSON number (seconds remaining)
        // or `null` when the agent lock is engaged. The key is always present.
        private fun parseAgentReleaseTimeLeft(data: Map<String, Any?>): Int? =
            (data["agent_release_time_left"] as? Number)?.toInt()
    }
}
