package com.palli.mobile.ui.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.min

/** Hold-to-talk, as the chat sees it; [VoiceRecorder] is the real one. Call from the main thread. */
interface Recorder {
    /** Starts recording into [file]. Throws if the microphone cannot be had; nothing is left recording then. */
    fun start(file: File)
    /** The note and its length when it is worth sending; null (and no file) for a slip of the thumb or nothing recorded. */
    fun stop(): Pair<File, Long>?
    /** Drops the recording and its file. */
    fun cancel()

    /** No microphone: what a test of the text chat gets. */
    object None : Recorder {
        override fun start(file: File) = throw IllegalStateException("no microphone here")
        override fun stop(): Pair<File, Long>? = null
        override fun cancel() = Unit
    }
}

/**
 * Always AAC in MP4 (m4a): every Android version writes it, and PaLLi's ffmpeg reads it. The note is a real file in
 * the app's own storage, so its upload part has a known length (a part without one makes the send chunked, and PaLLi
 * answers a chunked upload with 413).
 */
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) : Recorder {
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L
    /** MediaRecorder stops itself at [RecordingRules.MAX_MS]; what it wrote up to then is still a note. */
    private var capped = false

    override fun start(file: File) {
        cancel()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(RecordingRules.MAX_MS.toInt())
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) capped = true }
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()                                      // a failed start must not hold the microphone
            file.delete()
            throw e
        }
        recorder = r; target = file; startedAt = SystemClock.elapsedRealtime(); capped = false
    }

    override fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        recorder = null
        val duration = min(SystemClock.elapsedRealtime() - startedAt, RecordingRules.MAX_MS)
        val stopped = runCatching { r.stop() }.isSuccess || capped   // stop() throws when nothing was recorded
        r.release()
        val file = target ?: return null
        target = null
        if (stopped && RecordingRules.shouldSend(duration) && file.length() > 0) return file to duration
        file.delete()
        return null
    }

    override fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        target?.delete(); target = null
    }
}
