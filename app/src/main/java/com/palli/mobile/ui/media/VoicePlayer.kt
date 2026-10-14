package com.palli.mobile.ui.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Her voice notes, one at a time. They are OGG/Opus: `MediaPlayer` reads that only from Android 10 and minSdk is 26, so
 * this is Media3's ExoPlayer, which carries its own Ogg extractor. Call from the main thread.
 *
 * The progress ticker runs on the player's own scope, not a bubble's: a bubble scrolled out of view and back still shows
 * the note moving.
 */
@Singleton
class VoicePlayer @Inject constructor(@ApplicationContext private val context: Context) {
    /** [playingId] is the note loaded, sounding or [paused]; [failedId] is the one that last could not be played. */
    data class State(
        val playingId: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val paused: Boolean = false,
        val failedId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    /** Plays [file] as note [id]; the same note again pauses it, and again resumes it. Another note replaces it. */
    fun toggle(id: String, file: File) {
        val current = player
        if (current != null && _state.value.playingId == id) {
            if (current.playWhenReady) current.pause() else current.play()
            _state.value = _state.value.copy(paused = !current.playWhenReady)
            return
        }
        stop()
        val p = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true)
            setHandleAudioBecomingNoisy(true)                     // headphones out: pause, do not blare
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        }
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player === p) stop()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === p) { stop(); _state.value = State(failedId = id) }
            }
        })
        p.prepare()
        p.play()
        player = p
        _state.value = State(id)
        ticker = scope.launch {
            while (isActive && player === p) {
                _state.value = State(id, p.currentPosition, p.duration.coerceAtLeast(0), paused = !p.playWhenReady)
                delay(200)
            }
        }
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        player?.release(); player = null
        _state.value = State()
    }
}
