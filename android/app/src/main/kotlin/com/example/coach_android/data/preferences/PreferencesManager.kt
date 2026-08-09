package com.example.coach_android.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.coach_android.data.coach.UnlockDayDto
import com.example.coach_android.data.model.AppRule
import com.example.coach_android.data.model.AppSettings
import com.example.coach_android.data.model.FocusData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesManager(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            "coach_prefs",
            Context.MODE_PRIVATE,
        )

    private val json = Json { ignoreUnknownKeys = true }

    // --- Focus Data ---

    fun loadFocusData(): FocusData =
        FocusData(
            isFocusing = prefs.getBoolean("focusingState", false),
            sinceLastChange = prefs.getInt("sinceLastChange", 0),
            focusTimeLeft = prefs.getInt("focusTimeLeft", 0),
            numFocuses = prefs.getInt("numFocuses", 0),
            lastFocusEndTime = prefs.getInt("lastFocusEndTime", 0),
        )

    fun saveFocusData(data: FocusData) {
        prefs
            .edit()
            .putBoolean("focusingState", data.isFocusing)
            .putInt("sinceLastChange", data.sinceLastChange)
            .putInt("focusTimeLeft", data.focusTimeLeft)
            .putInt("numFocuses", data.numFocuses)
            .putInt("lastFocusEndTime", data.lastFocusEndTime)
            .apply()
    }

    // --- Focus Duration ---

    fun loadFocusDurationMinutes(): Int = prefs.getInt("focusDurationMinutes", 25)

    fun saveFocusDurationMinutes(minutes: Int) {
        prefs.edit().putInt("focusDurationMinutes", minutes).apply()
    }

    // --- Monitored Packages ---

    fun loadMonitoredPackages(): Set<String> {
        val jsonStr = prefs.getString("selectedAppPackages", null) ?: return emptySet()
        return try {
            json.decodeFromString<List<String>>(jsonStr).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveMonitoredPackages(packages: Set<String>) {
        val jsonStr = json.encodeToString(packages.toList())
        prefs.edit().putString("selectedAppPackages", jsonStr).apply()
    }

    // --- App Rules ---

    fun loadRules(): Map<String, AppRule> {
        val jsonStr = prefs.getString("appRules", null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, AppRule>>(jsonStr)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveRules(rules: Map<String, AppRule>) {
        val jsonStr = json.encodeToString(rules)
        prefs.edit().putString("appRules", jsonStr).apply()
    }

    // --- Agent chat thread ---

    fun getOrCreateAgentChatThreadId(): String {
        val existing = prefs.getString("agentChatThreadId", null)
        if (existing != null) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("agentChatThreadId", fresh).apply()
        return fresh
    }

    // --- Unlock stats cache ---

    // The chat header shows the coach's unlock ledger, which lives on the server.
    // Cached verbatim so the header has something true to draw while the fetch is
    // in flight or the network is gone.
    fun loadUnlockStats(): List<UnlockDayDto> {
        val jsonStr = prefs.getString("unlockStats", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<UnlockDayDto>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveUnlockStats(stats: List<UnlockDayDto>) {
        prefs.edit().putString("unlockStats", json.encodeToString(stats)).apply()
    }

    // --- Pending Challenges ---

    fun loadPendingChallengeIds(): List<String> {
        val jsonStr = prefs.getString("pendingChallenges", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePendingChallengeIds(ids: List<String>) {
        val jsonStr = json.encodeToString(ids)
        prefs.edit().putString("pendingChallenges", jsonStr).apply()
    }

    // --- App Settings ---

    private fun str(
        key: String,
        default: String,
    ): String = prefs.getString(key, null) ?: default

    private fun int(
        key: String,
        default: Int,
    ): Int {
        val v = prefs.getInt(key, -1)
        return if (v >= 0) v else default
    }

    fun loadSettings(): AppSettings =
        AppSettings(
            overlayMessage = str("settingsOverlayMessage", AppSettings.DEFAULT_OVERLAY_MESSAGE),
            overlayColor = str("settingsOverlayColor", AppSettings.DEFAULT_OVERLAY_COLOR),
            overlayButtonText = str("settingsOverlayButtonText", AppSettings.DEFAULT_OVERLAY_BUTTON_TEXT),
            overlayButtonColor = str("settingsOverlayButtonColor", AppSettings.DEFAULT_OVERLAY_BUTTON_COLOR),
            rulesOverlayMessage = str("settingsRulesOverlayMessage", AppSettings.DEFAULT_RULES_OVERLAY_MESSAGE),
            rulesOverlayColor = str("settingsRulesOverlayColor", AppSettings.DEFAULT_RULES_OVERLAY_COLOR),
            rulesOverlayButtonText = str("settingsRulesOverlayButtonText", AppSettings.DEFAULT_RULES_OVERLAY_BUTTON_TEXT),
            rulesOverlayButtonColor = str("settingsRulesOverlayButtonColor", AppSettings.DEFAULT_RULES_OVERLAY_BUTTON_COLOR),
            overlayTargetApp = str("settingsOverlayTargetApp", AppSettings.DEFAULT_OVERLAY_TARGET_APP),
            rulesOverlayTargetApp = str("settingsRulesOverlayTargetApp", AppSettings.DEFAULT_RULES_OVERLAY_TARGET_APP),
            longPressDurationSeconds = int("settingsLongPressDuration", AppSettings.DEFAULT_LONG_PRESS_DURATION_SECONDS),
            typingPhrase = str("settingsTypingPhrase", AppSettings.DEFAULT_TYPING_PHRASE),
        )

    fun saveSettings(settings: AppSettings) {
        prefs
            .edit()
            .putString("settingsOverlayMessage", settings.overlayMessage)
            .putString("settingsOverlayColor", settings.overlayColor)
            .putString("settingsOverlayButtonText", settings.overlayButtonText)
            .putString("settingsOverlayButtonColor", settings.overlayButtonColor)
            .putString("settingsRulesOverlayMessage", settings.rulesOverlayMessage)
            .putString("settingsRulesOverlayColor", settings.rulesOverlayColor)
            .putString("settingsRulesOverlayButtonText", settings.rulesOverlayButtonText)
            .putString("settingsRulesOverlayButtonColor", settings.rulesOverlayButtonColor)
            .putString("settingsOverlayTargetApp", settings.overlayTargetApp)
            .putString("settingsRulesOverlayTargetApp", settings.rulesOverlayTargetApp)
            .putInt("settingsLongPressDuration", settings.longPressDurationSeconds)
            .putString("settingsTypingPhrase", settings.typingPhrase)
            .apply()
    }
}
