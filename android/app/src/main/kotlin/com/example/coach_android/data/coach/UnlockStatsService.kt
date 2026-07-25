package com.example.coach_android.data.coach

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// One local day of the coach's unlock ledger, as the server reports it.
@Serializable
data class UnlockDayDto(
    val day: String,
    val unlocks: Int = 0,
    @SerialName("unlocked_seconds") val unlockedSeconds: Int = 0,
)

// Reads unlock history from the coach server. The server owns the ledger — it
// sees grants made from the browser and while the phone was off — so the phone
// asks rather than keeping its own tally.
class UnlockStatsService(
    webSocketUrl: String,
    private val apiToken: String = "",
) {
    private val tag = "UnlockStatsService"

    // The focus socket already points at the coach host, so the HTTP base comes
    // from it instead of a second URL to keep configured and in sync.
    private val baseUrl: String = httpBaseFrom(webSocketUrl)

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = baseUrl.isNotEmpty()

    suspend fun fetch(days: Int): List<UnlockDayDto>? {
        if (baseUrl.isEmpty()) {
            Log.w(tag, "No coach URL configured; skipping unlock stats fetch")
            return null
        }

        val request =
            Request
                .Builder()
                .url("$baseUrl/lock-decisions/stats?days=$days")
                // Caddy takes the bearer for machine clients; the sockets use the
                // query form because browsers can't set headers.
                .apply { if (apiToken.isNotEmpty()) header("Authorization", "Bearer $apiToken") }
                .get()
                .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        Log.w(tag, "Unlock stats request failed: ${response.code}")
                        return@use null
                    }
                    json.decodeFromString<List<UnlockDayDto>>(body)
                }
            } catch (e: Exception) {
                Log.w(tag, "Unlock stats request failed: ${e.message}")
                null
            }
        }
    }

    private companion object {
        // wss://coach.example/connect → https://coach.example
        fun httpBaseFrom(webSocketUrl: String): String {
            if (webSocketUrl.isBlank()) return ""
            val withoutQuery = webSocketUrl.substringBefore('?')
            val scheme = if (withoutQuery.startsWith("ws://")) "http://" else "https://"
            val host =
                withoutQuery
                    .removePrefix("wss://")
                    .removePrefix("ws://")
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
            return if (host.isEmpty()) "" else "$scheme$host"
        }
    }
}
