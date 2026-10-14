package com.palli.mobile.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppForeground @Inject constructor() : DefaultLifecycleObserver {
    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    override fun onStart(owner: LifecycleOwner) { _inForeground.value = true }
    override fun onStop(owner: LifecycleOwner) { _inForeground.value = false }
}
