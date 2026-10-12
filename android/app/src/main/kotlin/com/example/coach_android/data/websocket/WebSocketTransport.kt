package com.example.coach_android.data.websocket

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// The sockets keep their own protocols and lifetimes, but can share OkHttp's
// connection pool, dispatcher, and timeout configuration.
internal object WebSocketTransport {
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES)
            .build()
}
