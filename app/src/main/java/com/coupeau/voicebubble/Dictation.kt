package com.coupeau.voicebubble

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous dictation on top of Android's speech recogniser.
 *
 * Best case (Android 13+): one long "segmented session" that keeps the microphone open
 * and hands back each phrase as you go, so nothing is lost between phrases.
 * The on-device recogniser is tried first, then the normal one.
 *
 * Fallback: the classic recogniser stops after every pause, so we restart it
 * immediately. That works anywhere but can drop a word at each restart.
 */
class Dictation(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        /** [committed] is the text that is final; [partial] is the phrase still being heard. */
        fun onDictationText(committed: String, partial: String)
        fun onDictationLevel(rmsDb: Float)
        fun onDictationFailed(message: String)
    }

    /** Ways of listening, best first. */
    enum class Engine(val label: String) {
        ON_DEVICE_CONTINUOUS("On-device, continuous"),
        CLOUD_CONTINUOUS("Google, continuous"),
        RESTART_LOOP("Google, restart after each pause"),
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val segments = mutableListOf<String>()
    private var partial = ""
    private var errorStreak = 0

    private var engine = Engine.RESTART_LOOP
    /** True once the current engine has produced any words, so we trust it. */
    private var engineProved = false

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
        engine = firstEngine()
        engineProved = false
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

    // ---- choosing the engine --------------------------------------------------------------

    private fun firstEngine(): Engine {
        val forced = Prefs.engineOverride(ctx)
        if (forced != null) return forced
        val broken = Prefs.brokenEngines(ctx)
        val options = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) add(Engine.ON_DEVICE_CONTINUOUS)
                add(Engine.CLOUD_CONTINUOUS)
            }
            add(Engine.RESTART_LOOP)
        }
        return options.firstOrNull { it !in broken } ?: Engine.RESTART_LOOP
    }

    /** The current engine failed before producing anything: remember that and try the next one. */
    private fun demote(error: Int): Boolean {
        if (engine == Engine.RESTART_LOOP || engineProved || Prefs.engineOverride(ctx) != null) return false
        Prefs.markEngineBroken(ctx, engine, error)
        engine = if (engine == Engine.ON_DEVICE_CONTINUOUS) Engine.CLOUD_CONTINUOUS else Engine.RESTART_LOOP
        if (engine in Prefs.brokenEngines(ctx)) engine = Engine.RESTART_LOOP
        restart(150, fresh = true)
        return true
    }

    // ---- recogniser plumbing ----------------------------------------------------------------

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
        recognizer = try {
            if (engine == Engine.ON_DEVICE_CONTINUOUS && Build.VERSION.SDK_INT >= 33) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (_: Exception) {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }.also { it.setRecognitionListener(callbacks) }
        Prefs.setLastEngine(ctx, engine)
    }

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (engine != Engine.RESTART_LOOP && Build.VERSION.SDK_INT >= 33) {
                // One long session: stays open for up to 30 minutes regardless of pauses,
                // handing back each phrase through onSegmentResults.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30 * 60 * 1000L)
                if (engine == Engine.ON_DEVICE_CONTINUOUS) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            } else {
                // Ask for long silences to be allowed; many recognisers ignore this,
                // which is why this mode restarts automatically.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
            }
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            if (!demote(-1)) restart(400, fresh = true)
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

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

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
            val b = best(partialResults)
            if (!b.isNullOrBlank()) {
                engineProved = true
                partial = b
                emit()
            }
        }

        /** Continuous mode: one finished phrase; the session carries on listening. */
        override fun onSegmentResults(segmentResults: Bundle) {
            if (!active) return
            val b = best(segmentResults)
            if (!b.isNullOrBlank()) {
                engineProved = true
                partial = b
            }
            commitPartial()
            errorStreak = 0
            emit()
        }

        /** Continuous mode: the long session ended (e.g. 30-minute cap). Start another. */
        override fun onEndOfSegmentedSession() {
            if (!active) return
            commitPartial()
            emit()
            restart(0)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val b = best(results)
            if (!b.isNullOrBlank()) {
                engineProved = true
                partial = b
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

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    shutdown()
                    listener.onDictationFailed("Microphone permission is missing. Open Voice Bubble to allow it.")
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    if (!demote(error)) restart(250, fresh = true)
                }

                else -> {
                    // Unsupported language/feature, missing offline model, server trouble...
                    if (demote(error)) return
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
