package com.eunho.tagohub.web

import java.util.prefs.Preferences

private val prefs: Preferences? = runCatching { Preferences.userRoot().node("tagohub") }.getOrNull()

actual fun storeGet(key: String): String? = runCatching { prefs?.get(key, null) }.getOrNull()
actual fun storeSet(key: String, value: String) { runCatching { prefs?.put(key, value.take(Preferences.MAX_VALUE_LENGTH)) } }
