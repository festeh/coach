package com.example.coach_android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRule(
    val id: String,
    val packageName: String,
    val everyN: Int,
    val maxTriggers: Int,
    val challengeType: String = "none",
)
