package com.farhan.laserguardlink

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Renders the typed sentence to a WAV with Android TTS. The file is
 * then served by PhoneServer at /voice.wav and the board downloads
 * it after a /voice call — exactly the flow the firmware implements
 * (AudioGeneratorWAV on the board plays the cached file).
 */
class VoiceMaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    suspend fun synthesize(text: String, outFile: File): Boolean {
        if (!ready.await()) return false
        return suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(outFile.exists() && outFile.length() > 0)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            val result = tts.synthesizeToFile(text, Bundle(), outFile, "lg-voice")
            if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
