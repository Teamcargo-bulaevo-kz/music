package com.example.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test fun valuesAreClampedToRange() {
        val setting = AppSettings.REQUEST_TIMEOUT_S
        assertEquals(setting.min, setting.clamp(-5))
        assertEquals(setting.max, setting.clamp(99_999))
        assertEquals(setting.default, setting.clamp(setting.default))
    }

    @Test fun defaultsAreInsideRanges() {
        listOf(
            AppSettings.REQUEST_TIMEOUT_S,
            AppSettings.CONNECT_TIMEOUT_S,
            AppSettings.RETRY_WAIT_S,
            AppSettings.CHAT_TEXT_SP,
            AppSettings.FONT_SCALE
        ).forEach { assertEquals(it.key, it.default, it.clamp(it.default)) }
    }

    @Test fun featureKeyMatchesSavedSwitches() {
        assertEquals("feature_flashlight", AppSettings.featureKey("flashlight"))
    }
}
