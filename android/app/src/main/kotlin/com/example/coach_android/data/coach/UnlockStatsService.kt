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

// One row of the release journal, newest first: a time the lock came off with
// the user's reason and the coach's reply. Older rows also hold peeks,
// overrides and the old judge's denials.
@Serializable
data class ReleaseDto(
    val id: Long,
    val kind: String,
    val source: String = "",
    @SerialName("user_message") val userMessage: String = "",
    @SerialName("agent_message") val agentMessage: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val created: String,
)

// One page of the journal. nextBefore points at the next, older page; null
// once the journal's first row has been read.
@Serializable
data class ReleasePageDto(
    val decisions: List<ReleaseDto> = emptyList(),
    @SerialName("next_before") val nextBefore: Long? = null,
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

    suspend fun fetch(days: Int): List<UnlockDayDto>? =
        decode(get("/lock-decisions/stats?days=$days"))

    // One page of releases, older than `before` when given.
    suspend fun fetchReleases(
        before: Long?,
        limit: Int,
    ): ReleasePageDto? {
        val query = "limit=$limit" + (before?.let { "&before=$it" } ?: "")
        return decode(get("/lock-decisions?$query"))
    }

    private inline fun <reified T> decode(body: String?): T? =
        body?.let {
            try {
                json.decodeFromString<T>(it)
            } catch (e: Exception) {
                Log.w(tag, "Unreadable coach response: ${e.message}")
                null
            }
        }

    // The response body, or null when the server can't be asked or answers
    // with an error.
    private suspend fun get(pathAndQuery: String): String? {
        if (baseUrl.isEmpty()) {
            Log.w(tag, "No coach URL configured; skipping $pathAndQuery")
            return null
        }

        val request =
            Request
                .Builder()
                .url("$baseUrl$pathAndQuery")
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
                        Log.w(tag, "Request $pathAndQuery failed: ${response.code}")
                        return@use null
                    }
                    body
                }
            } catch (e: Exception) {
                Log.w(tag, "Request $pathAndQuery failed: ${e.message}")
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
