package com.example.coach_android.di

import android.content.Context
import com.example.coach_android.data.agentchat.AgentChatService
import com.example.coach_android.data.coach.UnlockStatsService
import com.example.coach_android.data.db.UsageDatabase
import com.example.coach_android.data.preferences.PreferencesManager
import com.example.coach_android.data.websocket.WebSocketService
import com.example.coach_android.service.MonitorLogic

class AppContainer(
    context: Context,
    webSocketUrl: String,
    agentsUrl: String,
    apiToken: String = "",
) {
    val preferencesManager = PreferencesManager(context)
    val database = UsageDatabase.getInstance(context)
    val eventDao = database.eventDao()
    val ruleCounterDao = database.ruleCounterDao()

    // The token rides the query string on both sockets — OkHttp could set a
    // header, but the browser clients can't, so the server speaks query-param.
    val webSocketService = WebSocketService(withToken(webSocketUrl, apiToken))

    val unlockStatsService =
        UnlockStatsService(
            webSocketUrl = webSocketUrl,
            apiToken = apiToken,
        )

    val agentChatService =
        AgentChatService(
            baseUrl = agentsUrl,
            apiToken = apiToken,
        )

    val monitorLogic =
        MonitorLogic(
            prefs = preferencesManager,
            eventDao = eventDao,
            ruleCounterDao = ruleCounterDao,
            webSocketService = webSocketService,
        )

    private companion object {
        fun withToken(
            url: String,
            token: String,
        ): String = if (url.isEmpty() || token.isEmpty()) url else "$url?token=$token"
    }
}
