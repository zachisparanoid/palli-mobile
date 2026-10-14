package com.palli.mobile.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Session(val baseUrl: String, val token: String, val deviceId: Long, val deviceName: String) {
    /** The token is the phone's only credential: a log line, a failed assertion or a crash report must never carry it. */
    override fun toString() = "Session(baseUrl=$baseUrl, token=***, deviceId=$deviceId, deviceName=$deviceName)"
}

/**
 * Who this phone is to PaLLi. Null means unpaired; `unpairedReason` says why when it was not his choice. The reason is
 * kept with the preferences, so a phone revoked in the background and then killed still opens to a Pair screen that
 * says so; a new pairing clears it.
 */
@Singleton
class SessionStore @Inject constructor(private val prefs: ServerPrefs, private val cipher: TokenCipher) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _unpairedReason = MutableStateFlow<String?>(null)
    val unpairedReason: StateFlow<String?> = _unpairedReason.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val current: Session? get() = _session.value

    /** The pairing on disk. A token that will not unseal (its Keystore key is gone) is no pairing, and says so. */
    suspend fun load(): Session? {
        val stored = prefs.read()
        val token = stored.tokenBlob?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        val session = if (stored.baseUrl != null && stored.deviceId != null && token != null)
            Session(stored.baseUrl, token, stored.deviceId, stored.deviceName.orEmpty()) else null
        var reason = stored.unpairedReason
        if (stored.tokenBlob != null && token == null && reason != KEY_LOST) {
            prefs.setUnpairedReason(KEY_LOST)
            reason = KEY_LOST
        }
        _unpairedReason.value = if (session == null) reason else null
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
        prefs.clearPairing(reason)
        _unpairedReason.value = reason
        _session.value = null
    }

    companion object {
        const val KEY_LOST = "This phone's key was lost; pair again."
    }
}
