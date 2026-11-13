package com.coupeau.voicebubble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous dictation on top of Android's speech recogniser.
 *
 * The recogniser normally gives up after a short silence. Here, whenever it finishes or
 * times out, the words so far are kept and listening restarts straight away, so it only
 * stops when [stop] or [cancel] is called.
 */
class Dictation(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        /** [committed] is the text that is final; [partial] is the phrase still being heard. */
        fun onDictationText(committed: String, partial: String)
        fun onDictationLevel(rmsDb: Float)
        fun onDictationFailed(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val segments = mutableListOf<String>()
    private var partial = ""
    private var errorStreak = 0

    var active = false
        private set

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listener.onDictationFailed("No speech recogniser on this phone. Install or update the Google app.")
            return
        }
        segments.clear()
        partial = ""
        errorStreak = 0
        active = true
        recreate()
        listen()
    }

    /** Stops listening and returns everything heard, including the phrase in progress. */
    fun stop(): String {
        commitPartial()
        shutdown()
        return text()
    }

    /** Stops listening and throws away what was heard. */
    fun cancel() {
        segments.clear()
        partial = ""
        shutdown()
    }

    fun release() {
        shutdown()
        recognizer?.destroy()
        recognizer = null
    }

    private fun shutdown() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun text() = segments.joinToString(" ")

    private fun commitPartial() {
        if (partial.isNotBlank()) segments.add(partial.trim())
        partial = ""
    }

    private fun recreate() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(callbacks)
        }
    }

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Ask for long silences to be allowed. Many recognisers ignore this,
            // which is why we also restart automatically.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restart(400, fresh = true)
        }
    }

    private fun restart(delayMs: Long, fresh: Boolean = false) {
        if (!active) return
        handler.postDelayed({
            if (!active) return@postDelayed
            if (fresh) recreate()
            listen()
        }, delayMs)
    }

    private fun emit() = listener.onDictationText(text(), partial)

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (active) listener.onDictationLevel(rmsdB)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            val best = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!best.isNullOrBlank()) {
                partial = best
                emit()
            }
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!best.isNullOrBlank()) {
                partial = best
            }
            commitPartial()
            errorStreak = 0
            emit()
            restart(0)
        }

        override fun onError(error: Int) {
            if (!active) return
            // Keep anything already heard before the recogniser gave up.
            commitPartial()
            emit()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> restart(250, fresh = true)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    shutdown()
                    listener.onDictationFailed("Microphone permission is missing. Open Voice Bubble to allow it.")
                }

                else -> {
                    errorStreak++
                    if (errorStreak > 6) {
                        shutdown()
                        listener.onDictationFailed("Speech recognition keeps failing (error $error). Check your connection.")
                    } else {
                        restart(400L * errorStreak, fresh = true)
                    }
                }
            }
        }
    }
}
