package com.eunho.tagohub.web

@JsFun("(k) => { try { return window.localStorage.getItem(k); } catch (e) { return null; } }")
private external fun lsGet(k: String): String?

@JsFun("(k, v) => { try { window.localStorage.setItem(k, v); } catch (e) {} }")
private external fun lsSet(k: String, v: String)

actual fun storeGet(key: String): String? = lsGet("tagohub." + key)
actual fun storeSet(key: String, value: String) = lsSet("tagohub." + key, value)
