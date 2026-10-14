package com.palli.mobile.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ReminderDto
import com.palli.mobile.domain.RemindersRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The panel's logic. The primary constructor takes the three calls it makes on PaLLi, so tests can stand in for them;
 * the `@Inject` one is what Hilt uses (the same shape as ChatViewModel).
 *
 * Two kinds of trouble are told apart. A list that cannot load is [UiState.error], shown where the list would be. A
 * snooze or cancel that did not go through is a [notice], said once over the list, and the list is left as it was:
 * the exception is a 404, which is PaLLi saying the reminder is no longer open (it fired, was cancelled, or was never
 * his), and then the list is loaded again so the screen stops showing it.
 */
@HiltViewModel
class RemindersViewModel(
    private val fetch: suspend () -> Result<List<ReminderDto>>,
    private val snoozeOnServer: suspend (id: Long, minutes: Int) -> Result<Unit>,
    private val cancelOnServer: suspend (id: Long) -> Result<Unit>,
) : ViewModel() {
    @Inject constructor(repo: RemindersRepository) : this(repo::list, repo::snooze, repo::cancel)

    data class UiState(
        val reminders: List<ReminderDto> = emptyList(),
        val loading: Boolean = false,
        /** True once a list has been loaded, so "no reminders" is only said when PaLLi has said so. */
        val loaded: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** A short plain line for the screen to say once; see [noticeShown]. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var loading: Job? = null

    init { refresh() }

    /** Loads the list again. A load still out is dropped: its answer would be the older one. */
    fun refresh() {
        loading?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loading = viewModelScope.launch {
            attempt { fetch() }
                .onSuccess { reminders -> _state.update { it.copy(reminders = reminders, loading = false, loaded = true) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = if (e is ApiError.Unreachable) UNREACHABLE else LOAD_FAILED) } }
        }
    }

    fun snooze(id: Long, minutes: Int) = act(SNOOZE_FAILED) { snoozeOnServer(id, minutes) }
    fun cancel(id: Long) = act(CANCEL_FAILED) { cancelOnServer(id) }

    /** The screen has shown [text]; forget it, unless a newer notice has already replaced it. */
    fun noticeShown(text: String) = _notice.update { if (it == text) null else it }

    private fun act(failed: String, call: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            attempt(call)
                .onSuccess { refresh() }
                .onFailure { e ->
                    if (e is ApiError.Rejected && e.status == 404) {
                        _notice.value = GONE
                        refresh()
                    } else {
                        _notice.value = say(e, failed)
                    }
                }
        }
    }

    /** A call that throws instead of answering is a failure like any other, not a crash. */
    private suspend fun <T> attempt(call: suspend () -> Result<T>): Result<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun say(e: Throwable, failed: String): String = when {
        e is ApiError.Unreachable -> UNREACHABLE
        e is ApiError.Rejected && e.detail.isNotBlank() && !e.detail.trimStart().startsWith("<") -> e.detail
        else -> failed
    }

    companion object {
        const val GONE = "That reminder has already gone off or been cancelled."
        const val UNREACHABLE = "Can't reach PaLLi right now. Is Tailscale on?"
        const val LOAD_FAILED = "Couldn't load your reminders."
        const val SNOOZE_FAILED = "Couldn't snooze that just now."
        const val CANCEL_FAILED = "Couldn't cancel that just now."
    }
}
