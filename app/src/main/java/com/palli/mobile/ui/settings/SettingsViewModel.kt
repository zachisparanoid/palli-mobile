package com.palli.mobile.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.session.ServerPrefs
import com.palli.mobile.data.session.Session
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.domain.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The settings' logic. The primary constructor takes what the screen reads and the two things it can do, so tests can
 * stand in for them; the `@Inject` one is what Hilt uses (the same shape as ChatViewModel).
 *
 * Staying connected is only written down here: the connection controller watches that setting and starts or stops the
 * connection itself, so nothing on this screen touches the stream.
 */
@HiltViewModel
class SettingsViewModel(
    val session: StateFlow<Session?>,
    stayConnectedSetting: Flow<Boolean>,
    private val writeStayConnected: suspend (Boolean) -> Unit,
    /** Tells PaLLi and forgets the pairing here; PairingRepository runs all of it whatever becomes of the caller. */
    private val unpairThisPhone: suspend () -> Unit,
) : ViewModel() {
    @Inject constructor(sessions: SessionStore, prefs: ServerPrefs, pairing: PairingRepository) :
        this(sessions.session, prefs.stayConnected, prefs::setStayConnected, pairing::unpairThisPhone)

    /** What is stored; null for the instant before it has been read, so the switch is not shown wrongly. */
    val stayConnected: StateFlow<Boolean?> = stayConnectedSetting.map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True from the tap on Unpair until the pairing is gone (the screen then leaves), or until it failed. */
    private val _unpairing = MutableStateFlow(false)
    val unpairing: StateFlow<Boolean> = _unpairing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun setStayConnected(on: Boolean) {
        viewModelScope.launch {
            // Written even if this screen is left a moment later: a switch that was moved must have stayed moved.
            withContext(NonCancellable) {
                try {
                    writeStayConnected(on)
                } catch (e: Exception) {
                    Log.w(TAG, "the stay-connected choice could not be saved: ${e.javaClass.simpleName}")
                    _notice.value = SAVE_FAILED
                }
            }
        }
    }

    fun unpair() {
        if (!_unpairing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                unpairThisPhone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "unpairing failed: ${e.javaClass.simpleName}")
                _unpairing.value = false
                _notice.value = UNPAIR_FAILED
            }
        }
    }

    /** The screen has shown [text]; forget it, unless a newer notice has already replaced it. */
    fun noticeShown(text: String) = _notice.update { if (it == text) null else it }

    companion object {
        const val SAVE_FAILED = "Couldn't save that. Try once more."
        const val UNPAIR_FAILED = "Couldn't unpair just now. Try once more."
        private const val TAG = "SettingsViewModel"
    }
}
