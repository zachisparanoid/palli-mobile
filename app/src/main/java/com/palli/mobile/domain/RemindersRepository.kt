package com.palli.mobile.domain

import com.palli.mobile.data.remote.ApiError
import com.palli.mobile.data.remote.ApiFactory
import com.palli.mobile.data.remote.PalliApi
import com.palli.mobile.data.remote.ReminderDto
import com.palli.mobile.data.remote.SnoozeRequest
import com.palli.mobile.data.remote.apiCall
import com.palli.mobile.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemindersRepository @Inject constructor(
    private val apis: ApiFactory,
    private val sessions: SessionStore,
    private val unpairer: Unpairer,
) {
    suspend fun list(): Result<List<ReminderDto>> = call { it.reminders().reminders }
    suspend fun snooze(id: Long, minutes: Int): Result<Unit> = call { it.snooze(id, SnoozeRequest(minutes)); Unit }
    suspend fun cancel(id: Long): Result<Unit> = call { it.cancelReminder(id); Unit }

    private suspend fun <T> call(block: suspend (PalliApi) -> T): Result<T> {
        val session = sessions.current ?: return Result.failure(ApiError.Unauthorized("This phone is not paired."))
        return apiCall { block(apis.forBase(session.baseUrl)) }.onFailure {
            if (it is ApiError.Unauthorized) unpairer.unpaired("This phone was unpaired.")
        }
    }
}
