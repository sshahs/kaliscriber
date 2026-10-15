package com.kaliscriber.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something the app was opened to do, from outside its own screens. */
sealed interface LaunchRequest {
    /** Show a meeting, e.g. one just imported from a shared file. */
    data class OpenMeeting(val id: String) : LaunchRequest

    /** Start recording (Quick Settings tile, home-screen widget). */
    data object Record : LaunchRequest

    /** Show the weekly recap (its Friday notification). */
    data object OpenRecap : LaunchRequest
}

/** The pending [LaunchRequest], held until a screen acts on it. */
class LaunchRequests {
    private val _pending = MutableStateFlow<LaunchRequest?>(null)
    val pending: StateFlow<LaunchRequest?> = _pending.asStateFlow()

    fun post(request: LaunchRequest) {
        _pending.value = request
    }

    /** Marks [request] as handled, unless a newer one has arrived meanwhile. */
    fun consume(request: LaunchRequest) {
        _pending.compareAndSet(request, null)
    }
}
