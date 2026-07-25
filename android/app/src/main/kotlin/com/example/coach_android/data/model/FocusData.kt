package com.example.coach_android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FocusData(
    val isFocusing: Boolean = false,
    val sinceLastChange: Int = 0,
    val focusTimeLeft: Int = 0,
    val numFocuses: Int = 0,
    val lastFocusEndTime: Int = 0,
    val agentReleaseTimeLeft: Int? = null,
    // The override cooldown, as the server prices it. The first override of each
    // local day is free; each one after it makes the next wait 30s longer.
    val overridesToday: Int = 0,
    val cooldownSeconds: Int = 0,
    val cooldownRemaining: Int = 0,
    val nextCooldownSeconds: Int = 0,
    val shortUnblocksLeft: Int = 0,
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
            // Only the discrete counters, not the remaining seconds: those tick
            // once a second and the UI counts them down locally between frames.
            overridesToday != other.overridesToday ||
            shortUnblocksLeft != other.shortUnblocksLeft

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
        ).withCooldownFrom(data)
    }

    // A refusal frame carries the cooldown numbers but none of the focus ones, so
    // it must not travel through updateFromWebSocket: absent
    // agent_release_time_left reads as null there, which would report the lock
    // engaged when it may not be.
    fun withCooldownFrom(data: Map<String, Any?>): FocusData =
        copy(
            overridesToday = (data["overrides_today"] as? Number)?.toInt() ?: overridesToday,
            cooldownSeconds = (data["override_cooldown_seconds"] as? Number)?.toInt() ?: cooldownSeconds,
            cooldownRemaining = (data["override_cooldown_remaining"] as? Number)?.toInt() ?: cooldownRemaining,
            nextCooldownSeconds = (data["override_next_cooldown_seconds"] as? Number)?.toInt() ?: nextCooldownSeconds,
            shortUnblocksLeft = (data["short_unblocks_left"] as? Number)?.toInt() ?: shortUnblocksLeft,
        )

    companion object {
        // Server sends `agent_release_time_left` as a JSON number (seconds remaining)
        // or `null` when the agent lock is engaged. The key is always present.
        private fun parseAgentReleaseTimeLeft(data: Map<String, Any?>): Int? =
            (data["agent_release_time_left"] as? Number)?.toInt()
    }
}
