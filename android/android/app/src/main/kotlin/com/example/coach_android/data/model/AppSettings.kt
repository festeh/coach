package com.example.coach_android.data.model

data class AppSettings(
    val rulesOverlayMessage: String = DEFAULT_RULES_OVERLAY_MESSAGE,
    val rulesOverlayColor: String = DEFAULT_RULES_OVERLAY_COLOR,
    val rulesOverlayButtonText: String = DEFAULT_RULES_OVERLAY_BUTTON_TEXT,
    val rulesOverlayButtonColor: String = DEFAULT_RULES_OVERLAY_BUTTON_COLOR,
    val rulesOverlayTargetApp: String = DEFAULT_RULES_OVERLAY_TARGET_APP,
    val longPressDurationSeconds: Int = DEFAULT_LONG_PRESS_DURATION_SECONDS,
    val typingPhrase: String = DEFAULT_TYPING_PHRASE,
) {
    companion object {
        const val DEFAULT_RULES_OVERLAY_MESSAGE = ""
        const val DEFAULT_RULES_OVERLAY_COLOR = "FF000000"
        const val DEFAULT_RULES_OVERLAY_BUTTON_TEXT = ""
        const val DEFAULT_RULES_OVERLAY_BUTTON_COLOR = "FFFF5252"
        const val DEFAULT_RULES_OVERLAY_TARGET_APP = ""
        const val DEFAULT_LONG_PRESS_DURATION_SECONDS = 5
        const val DEFAULT_TYPING_PHRASE = "I will focus"
    }
}
