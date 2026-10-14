package com.palli.mobile.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Session(val baseUrl: String, val token: String, val deviceId: Long, val deviceName: String)

/** Who this phone is to PaLLi. Null means unpaired; `unpairedReason` says why when it was not his choice. */
@Singleton
class SessionStore @Inject constructor(private val prefs: ServerPrefs, private val cipher: TokenCipher) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _unpairedReason = MutableStateFlow<String?>(null)
    val unpairedReason: StateFlow<String?> = _unpairedReason.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val current: Session? get() = _session.value

    suspend fun load(): Session? {
        val stored = prefs.read()
        val token = stored.tokenBlob?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        val session = if (stored.baseUrl != null && stored.deviceId != null && token != null)
            Session(stored.baseUrl, token, stored.deviceId, stored.deviceName.orEmpty()) else null
        _session.value = session
        _loaded.value = true
        return session
    }

    suspend fun save(session: Session) {
        prefs.write(session.baseUrl, session.deviceId, session.deviceName, cipher.encrypt(session.token))
        _unpairedReason.value = null
        _session.value = session
    }

    suspend fun clear(reason: String? = null) {
        prefs.clearPairing()
        _unpairedReason.value = reason
        _session.value = null
    }
}
