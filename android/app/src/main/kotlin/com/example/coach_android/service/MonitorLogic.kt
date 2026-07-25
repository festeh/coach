package com.example.coach_android.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.coach_android.data.db.EventDao
import com.example.coach_android.data.db.EventEntity
import com.example.coach_android.data.db.RuleCounterDao
import com.example.coach_android.data.model.AppRule
import com.example.coach_android.data.model.FocusData
import com.example.coach_android.data.preferences.PreferencesManager
import com.example.coach_android.data.websocket.WebSocketService
import com.example.coach_android.util.TimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MonitorLogic(
    private val prefs: PreferencesManager,
    private val eventDao: EventDao,
    private val ruleCounterDao: RuleCounterDao,
    private val webSocketService: WebSocketService,
) {
    private val tag = "MonitorLogic"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _focusData = MutableStateFlow(FocusData())
    val focusData: StateFlow<FocusData> = _focusData.asStateFlow()

    private var monitoredPackages = emptySet<String>()
    private var rules = emptyMap<String, AppRule>()
    private var pendingChallenges = mutableMapOf<String, String>() // ruleId → packageName

    var overlayManager: com.example.coach_android.OverlayManager? = null

    // Set by FocusMonitorService after construction. Used to launch ChatActivity
    // when the agent lock blocks a monitored app.
    var applicationContext: Context? = null

    private var webSocketJob: Job? = null
    private var refusalJob: Job? = null

    fun initialize() {
        Log.i(tag, "Initializing MonitorLogic...")
        loadPersistedState()
        webSocketService.initialize()
        startWebSocketListener()
        Log.i(
            tag,
            "MonitorLogic initialized - focusing: ${_focusData.value.isFocusing}, monitored: ${monitoredPackages.size} apps, rules: ${rules.size}",
        )
    }

    private fun loadPersistedState() {
        _focusData.value = prefs.loadFocusData()
        monitoredPackages = prefs.loadMonitoredPackages()
        rules = prefs.loadRules()

        val pendingIds = prefs.loadPendingChallengeIds()
        pendingChallenges.clear()
        // Rebuild pending challenges map from IDs — we only know ruleId, packageName is resolved on next app open
        for (id in pendingIds) {
            val rule = rules[id]
            if (rule != null) {
                pendingChallenges[id] = rule.packageName
            }
        }

        scope.launch {
            try {
                ruleCounterDao.cleanupOldCounters(TimeFormatter.todayString())
            } catch (e: Exception) {
                Log.e(tag, "Failed to cleanup old counters: ${e.message}")
            }
        }

        Log.i(
            tag,
            "Loaded state - focusing: ${_focusData.value.isFocusing}, monitored: ${monitoredPackages.joinToString()}, rules: ${rules.size}",
        )
    }

    private fun startWebSocketListener() {
        webSocketJob?.cancel()
        webSocketJob =
            scope.launch {
                webSocketService.focusUpdates.collect { data ->
                    handleWebSocketUpdate(data)
                }
            }
        refusalJob?.cancel()
        refusalJob =
            scope.launch {
                // A refusal carries fresher cooldown numbers than the phone holds,
                // so fold them in even though no lock state changed.
                // Not persisted: the cooldown is the server's to own, and every
                // connect brings it back on the first focus frame.
                webSocketService.unblockRefusals.collect { data ->
                    _focusData.value = _focusData.value.withCooldownFrom(data)
                }
            }
    }

    private fun handleWebSocketUpdate(data: Map<String, Any?>) {
        val old = _focusData.value
        val new = old.updateFromWebSocket(data)

        val focusStarted = !old.isFocusing && new.isFocusing
        val focusEnded = old.isFocusing && !new.isFocusing

        // Log focus events
        scope.launch {
            try {
                if (focusStarted) {
                    eventDao.insert(
                        EventEntity(
                            timestamp = System.currentTimeMillis() / 1000,
                            eventType = "focus_started",
                        ),
                    )
                }
                if (focusEnded) {
                    eventDao.insert(
                        EventEntity(
                            timestamp = System.currentTimeMillis() / 1000,
                            eventType = "focus_ended",
                            duration = old.sinceLastChange,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to log focus event: ${e.message}")
            }
        }

        if (old.hasSignificantDifference(new)) {
            _focusData.value = new
            prefs.saveFocusData(new)
            Log.i(
                tag,
                "Focus data updated from WebSocket: focusing=${new.isFocusing}, sinceLastChange=${new.sinceLastChange}, focusTimeLeft=${new.focusTimeLeft}, numFocuses=${new.numFocuses}",
            )
        }
    }

    // --- App Change Handling (called from FocusMonitorService) ---

    private enum class BlockMode { CHAT, STANDARD_OVERLAY, NONE }

    private fun blockMode(packageName: String): BlockMode {
        if (!monitoredPackages.contains(packageName)) return BlockMode.NONE
        // Agent lock wins over manual focus when both are active.
        if (_focusData.value.isAgentLocked) return BlockMode.CHAT
        if (_focusData.value.isFocusing) return BlockMode.STANDARD_OVERLAY
        return BlockMode.NONE
    }

    fun onAppChanged(packageName: String) {
        Log.d(tag, "App changed to: $packageName")

        scope.launch {
            try {
                // Log app open
                eventDao.insert(
                    EventEntity(
                        timestamp = System.currentTimeMillis() / 1000,
                        eventType = "app_opened",
                        packageName = packageName,
                        duringFocus = if (_focusData.value.isFocusing) 1 else 0,
                    ),
                )

                val mode = blockMode(packageName)
                Log.i(
                    tag,
                    "Block decision for $packageName - focusing: ${_focusData.value.isFocusing}, agentLocked: ${_focusData.value.isAgentLocked}, monitored: ${monitoredPackages.contains(packageName)}, mode: $mode",
                )

                when (mode) {
                    BlockMode.CHAT -> {
                        // Android 15 refuses activity starts from a background
                        // service unless the app has a visible window at that
                        // moment. Flash the overlay to earn the exemption, start
                        // the chat, then drop it so it can't cover the chat.
                        withContext(Dispatchers.Main) {
                            overlayManager?.show(packageName)
                            launchChatActivity(forced = true)
                            overlayManager?.hide()
                        }
                    }
                    BlockMode.STANDARD_OVERLAY -> {
                        withContext(Dispatchers.Main) { overlayManager?.show(packageName) }
                    }
                    BlockMode.NONE -> {
                        withContext(Dispatchers.Main) { overlayManager?.hide() }
                    }
                }

                // A blocked watched-app open is a temptation; reuse the decision above.
                if (mode != BlockMode.NONE) {
                    webSocketService.sendTemptation(packageName)
                }

                // Check rules independently of focus state
                checkRules(packageName)
            } catch (e: Exception) {
                Log.e(tag, "Error handling app change: ${e.message}")
            }
        }
    }

    fun ensureOverlay(packageName: String) {
        val mode = blockMode(packageName)
        when (mode) {
            BlockMode.STANDARD_OVERLAY -> {
                if (overlayManager?.isShowing() != true) {
                    Log.i(tag, "Re-showing overlay for $packageName (was dismissed externally)")
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            overlayManager?.show(packageName)
                        }
                    }
                }
            }
            BlockMode.CHAT -> {
                Log.i(tag, "Re-launching chat for $packageName (was bypassed)")
                scope.launch {
                    withContext(Dispatchers.Main) {
                        overlayManager?.show(packageName)
                        launchChatActivity(forced = true)
                        overlayManager?.hide()
                    }
                }
            }
            BlockMode.NONE -> {}
        }
    }

    private fun launchChatActivity(forced: Boolean) {
        val ctx = applicationContext ?: return
        val intent =
            Intent().apply {
                setClassName(ctx, "com.example.coach_android.ui.chat.ChatActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("EXTRA_FORCED", forced)
            }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch ChatActivity: ${e.message}")
        }
    }

    // --- Rule Checking ---

    private suspend fun checkRules(packageName: String) {
        // Check for pending challenges first
        val pendingRuleId =
            pendingChallenges.entries
                .firstOrNull { it.value == packageName }
                ?.key

        if (pendingRuleId != null) {
            val rule = rules[pendingRuleId]
            if (rule == null) {
                pendingChallenges.remove(pendingRuleId)
                persistPendingChallenges()
                Log.i(tag, "Cleaned up pending challenge for deleted rule $pendingRuleId")
            } else {
                Log.i(tag, "Re-showing pending challenge for rule ${rule.id} on $packageName")
                withContext(Dispatchers.Main) {
                    overlayManager?.show(packageName, "rule", rule.challengeType, rule.id)
                }
                return
            }
        }

        val rulesForApp = rules.values.filter { it.packageName == packageName }
        if (rulesForApp.isEmpty()) return

        val today = TimeFormatter.todayString()

        for (rule in rulesForApp) {
            try {
                ruleCounterDao.incrementOpenCount(rule.id, today)
                val openCount = ruleCounterDao.getOpenCount(rule.id, today) ?: 0
                Log.i(tag, "Rule ${rule.id}: open_count=$openCount for $packageName (everyN=${rule.everyN})")

                if (openCount % rule.everyN == 0) {
                    val counters = ruleCounterDao.getCounters(rule.id, today)
                    val triggerCount = counters?.triggerCount ?: 0

                    if (triggerCount < rule.maxTriggers) {
                        if (rule.challengeType == "none") {
                            ruleCounterDao.incrementTriggerCount(rule.id, today)
                            Log.i(tag, "Rule ${rule.id} triggered! (${triggerCount + 1}/${rule.maxTriggers})")
                        } else {
                            pendingChallenges[rule.id] = packageName
                            persistPendingChallenges()
                            Log.i(tag, "Rule ${rule.id} triggered with challenge ${rule.challengeType}, pending completion")
                        }
                        withContext(Dispatchers.Main) {
                            overlayManager?.show(packageName, "rule", rule.challengeType, rule.id)
                        }
                        break // Only one rule popup per app open
                    } else {
                        Log.i(tag, "Rule ${rule.id}: max triggers reached ($triggerCount/${rule.maxTriggers})")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error checking rule ${rule.id}: ${e.message}")
            }
        }
    }

    fun onChallengeCompleted(ruleId: String) {
        Log.i(tag, "Challenge completed for rule $ruleId")
        scope.launch {
            pendingChallenges.remove(ruleId)
            persistPendingChallenges()
            val today = TimeFormatter.todayString()
            ruleCounterDao.incrementTriggerCount(ruleId, today)
            Log.i(tag, "Challenge completed for rule $ruleId, trigger count incremented")
            withContext(Dispatchers.Main) {
                overlayManager?.hide()
            }
        }
    }

    private fun persistPendingChallenges() {
        prefs.savePendingChallengeIds(pendingChallenges.keys.toList())
    }

    // --- External Triggers ---

    fun refreshFocusState() {
        scope.launch {
            try {
                Log.i(tag, "Refreshing focus state from WebSocket")
                val response = webSocketService.requestFocusStatus()
                val new = _focusData.value.updateFromWebSocket(response)
                _focusData.value = new
                prefs.saveFocusData(new)
                Log.i(tag, "Focus state refreshed: focusing=${new.isFocusing}")
            } catch (e: Exception) {
                Log.e(tag, "Failed to refresh focus state: ${e.message}")
            }
        }
    }

    fun sendFocusCommand(durationMinutes: Int = 0) {
        scope.launch {
            try {
                webSocketService.sendFocusCommand(durationMinutes)
                Log.i(tag, "Focus command sent (duration=${durationMinutes}m)")
            } catch (e: Exception) {
                Log.e(tag, "Failed to send focus command: ${e.message}")
            }
        }
    }

    fun takeOverride(reason: String) {
        webSocketService.sendOverride(reason)
    }

    fun takeShortUnblock() {
        webSocketService.sendShortUnblock()
    }

    // Refusals reach the UI so a declined tap says why instead of doing nothing.
    val unblockRefusals get() = webSocketService.unblockRefusals

    fun reloadMonitoredPackages() {
        monitoredPackages = prefs.loadMonitoredPackages()
        Log.i(tag, "Reloaded monitored packages: ${monitoredPackages.size} apps")
    }

    fun reloadRules() {
        rules = prefs.loadRules()
        Log.i(tag, "Reloaded rules: ${rules.size} rules")
    }

    fun getWebSocketConnectionStatus(): Map<String, Any?> = webSocketService.getConnectionStatus()

    fun dispose() {
        Log.i(tag, "Disposing MonitorLogic")
        webSocketJob?.cancel()
        refusalJob?.cancel()
        scope.cancel()
        webSocketService.dispose()
    }
}
