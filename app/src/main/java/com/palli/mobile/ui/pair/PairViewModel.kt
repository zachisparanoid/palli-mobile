package com.palli.mobile.ui.pair

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.pairing.PairingLinks
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.domain.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The primary constructor takes the one call the screen makes on PairingRepository, so tests can stand in for it; the
 * `@Inject` one is what Hilt uses (the same shape as ConnectionController).
 */
@HiltViewModel
class PairViewModel(
    private val pairWith: suspend (baseUrl: String, code: String, deviceName: String) -> Result<Session>,
    val unpairedReason: StateFlow<String?>,
    defaultDeviceName: String,
) : ViewModel() {
    @Inject constructor(pairing: PairingRepository, sessions: SessionStore) :
        this(pairing::pair, sessions.unpairedReason, Build.MODEL ?: "Phone")

    /** What a scanned QR or an opened link holds, exactly as the question showed it: the address and the code. */
    data class PendingLink(val address: String, val code: String)

    data class UiState(
        val address: String = "",
        val code: String = "",
        val deviceName: String = "Phone",
        val busy: Boolean = false,
        val error: String? = null,
        /**
         * Set when a scanned QR or an opened `palli://pair` link is waiting for a yes. Any web page can open that link,
         * so a link never pairs on its own; it fills the form and asks first. Yes pairs this, not whatever the form says.
         */
        val pending: PendingLink? = null,
    ) {
        /** The address the question names. */
        val pendingAddress: String? get() = pending?.address
    }

    private val _state = MutableStateFlow(UiState(deviceName = defaultDeviceName))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onAddress(value: String) = _state.update { it.copy(address = value, error = null) }
    fun onCode(value: String) = _state.update { it.copy(code = value, error = null) }
    fun onName(value: String) = _state.update { it.copy(deviceName = value, error = null) }

    /**
     * A scanned QR, or a palli://pair link opened from elsewhere, fills the form and asks "Pair with <address>?".
     * Nothing is sent until [confirmPair]. A link that arrives mid-pairing is dropped: the one in flight is the answer.
     */
    fun onScanned(raw: String) {
        if (_state.value.busy) return
        val link = PairingLinks.parse(raw)
        if (link == null) {
            _state.update { it.copy(error = "That QR is not a PaLLi pairing code.", pending = null) }
            return
        }
        _state.update {
            it.copy(address = link.baseUrl, code = link.code, error = null, pending = PendingLink(link.baseUrl, link.code))
        }
    }

    /** The owner said yes: pair with exactly what the question showed. Does nothing when no question is pending. */
    fun confirmPair() {
        val link = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        submit(link.address, link.code)
    }

    /**
     * No: the address and code the link filled in are cleared with the question, so an address the owner refused is
     * not one tap of the Pair button from being paired. With no question pending it touches nothing the owner typed.
     */
    fun cancelPair() = _state.update { if (it.pending == null) it else it.copy(address = "", code = "", pending = null) }

    /** The Pair button: typed by hand, so there is nothing to confirm. */
    fun pair() {
        val s = _state.value
        if (s.busy) return
        val base = PairingLinks.normalizeBaseUrl(s.address)
            ?: return _state.update { it.copy(error = PairErrors.ADDRESS_HINT) }
        val code = PairingLinks.normalizeCode(s.code)
            ?: return _state.update { it.copy(error = "The code is eight letters and digits, like K7QM-2XPA.") }
        submit(base, code)
    }

    private fun submit(base: String, code: String) {
        val s = _state.value
        if (s.busy) return
        val name = s.deviceName.trim().ifBlank { "Phone" }
        _state.update { it.copy(busy = true, error = null, pending = null) }
        viewModelScope.launch {
            // Nothing that goes wrong in pairing may escape this launch and take the app with it.
            val result = try {
                pairWith(base, code, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result
                .onSuccess { _state.update { it.copy(busy = false) } }          // the nav host moves to chat
                .onFailure { e -> _state.update { it.copy(busy = false, error = PairErrors.message(e, base)) } }
        }
    }
}
