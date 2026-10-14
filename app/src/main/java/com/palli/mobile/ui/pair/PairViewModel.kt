package com.palli.mobile.ui.pair

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.pairing.PairingLinks
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.domain.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

    data class UiState(
        val address: String = "",
        val code: String = "",
        val deviceName: String = "Phone",
        val busy: Boolean = false,
        val error: String? = null,
        /**
         * Set when a scanned QR or an opened `palli://pair` link is waiting for a yes: the address it would pair with.
         * Any web page can open that link, so a link never pairs on its own; it fills the form and asks first.
         */
        val pendingAddress: String? = null,
    )

    private val _state = MutableStateFlow(UiState(deviceName = defaultDeviceName))
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Editing the address or the code withdraws a pending question: the dialog would be naming the wrong place.
    fun onAddress(value: String) = _state.update { it.copy(address = value, error = null, pendingAddress = null) }
    fun onCode(value: String) = _state.update { it.copy(code = value, error = null, pendingAddress = null) }
    fun onName(value: String) = _state.update { it.copy(deviceName = value, error = null) }

    /**
     * A scanned QR, or a palli://pair link opened from elsewhere, fills the form and asks "Pair with <address>?".
     * Nothing is sent until [confirmPair]. A link that arrives mid-pairing is dropped: the one in flight is the answer.
     */
    fun onScanned(raw: String) {
        if (_state.value.busy) return
        val link = PairingLinks.parse(raw)
        if (link == null) {
            _state.update { it.copy(error = "That QR is not a PaLLi pairing code.", pendingAddress = null) }
            return
        }
        _state.update { it.copy(address = link.baseUrl, code = link.code, error = null, pendingAddress = link.baseUrl) }
    }

    /** The owner said yes to the question [onScanned] asked. Does nothing when no question is pending. */
    fun confirmPair() {
        if (_state.value.pendingAddress == null) return
        _state.update { it.copy(pendingAddress = null) }
        pair()
    }

    /** No: the form keeps what the link filled in, and nothing is sent. */
    fun cancelPair() = _state.update { it.copy(pendingAddress = null) }

    /** The Pair button: typed by hand, so there is nothing to confirm. */
    fun pair() {
        val s = _state.value
        if (s.busy) return
        val base = PairingLinks.normalizeBaseUrl(s.address)
            ?: return _state.update { it.copy(error = "Enter PaLLi's address, like http://your-host.your-tailnet.ts.net:8000.") }
        val code = PairingLinks.normalizeCode(s.code)
            ?: return _state.update { it.copy(error = "The code is eight letters and digits, like K7QM-2XPA.") }
        val name = s.deviceName.trim().ifBlank { "Phone" }
        _state.update { it.copy(busy = true, error = null, pendingAddress = null) }
        viewModelScope.launch {
            pairWith(base, code, name)
                .onSuccess { _state.update { it.copy(busy = false) } }          // the nav host moves to chat
                .onFailure { e -> _state.update { it.copy(busy = false, error = PairErrors.message(e, base)) } }
        }
    }
}
