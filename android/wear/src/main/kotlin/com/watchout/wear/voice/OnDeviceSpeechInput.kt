package com.watchout.wear.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 워치 **기기 안**에서만 동작하는 음성 인식.
 *
 * 개인정보 원칙(#69, CLAUDE.md):
 * - `createOnDeviceSpeechRecognizer`만 쓴다. 기기 안 인식이 없으면 [isAvailable]이 false이고,
 *   일반 `createSpeechRecognizer`(클라우드로 목소리를 보낼 수 있음)로 **넘어가지 않는다**.
 * - 녹음 원본은 받지도 저장하지도 않는다([RecognitionListener.onBufferReceived]를 비워 둔다).
 * - 받아쓴 글자는 [Listener]로 넘길 뿐 여기서 로그로 찍지 않는다.
 *
 * 모든 메서드는 메인 스레드에서 불러야 한다(SpeechRecognizer 규칙).
 */
class OnDeviceSpeechInput(private val context: Context) {

    /** 인식 결과를 받는 쪽. 받아쓴 글자는 화면 표시·명령 해석에만 쓰고 로그에 남기지 않는다. */
    interface Listener {
        /** 말하는 도중의 받아쓰기 (멈춤 단어를 빨리 잡으려고 쓴다) */
        fun onPartial(candidates: List<String>)

        /** 문장이 끝난 뒤의 받아쓰기 후보들 (가장 그럴듯한 것부터) */
        fun onFinal(candidates: List<String>)

        /** 오류. [code]는 SpeechRecognizer.ERROR_* 값이다. */
        fun onError(code: Int)
    }

    /**
     * 이 워치에서 기기 안 음성 인식을 쓸 수 있는가.
     * `isOnDeviceRecognitionAvailable`은 Android 12(API 31)부터 있다. 그보다 낮으면 기기 안 인식을
     * 확인할 방법이 없으므로 "안 된다"로 본다(클라우드로 넘기지 않기 위해).
     */
    val isAvailable: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    /** 듣기 시작. 버튼을 누른 순간 부른다. */
    fun start(listener: Listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isAvailable) {
            listener.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(Callback(listener))
        r.startListening(recognizeIntent())
    }

    /** 그만 듣고 지금까지 들은 말로 결과를 낸다. 버튼에서 손을 뗀 순간 부른다. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** 결과도 받지 않고 바로 끝낸다(화면을 나갈 때). */
    fun cancel() {
        recognizer?.cancel()
    }

    /** 마이크·인식기 자원을 돌려준다. 다시 [start]하면 새로 만든다. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        // 기기 안 인식기를 쓰지만, 혹시 모를 네트워크 사용도 막아 달라고 한 번 더 요청한다.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // 말하는 도중 결과를 받아야 "멈춰"를 문장이 끝나기 전에 잡을 수 있다.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // 비슷한 후보 몇 개를 받아 그중 알아들을 수 있는 것을 고른다.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
    }

    private class Callback(private val listener: Listener) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            listener.onPartial(partialResults.texts())
        }

        override fun onResults(results: Bundle?) {
            listener.onFinal(results.texts())
        }

        override fun onError(error: Int) = listener.onError(error)

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit

        /** 녹음 원본 조각. 저장·전송하지 않으므로 일부러 아무것도 하지 않는다. */
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun Bundle?.texts(): List<String> =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    }

    companion object {
        const val LANGUAGE = "ko-KR"
        private const val MAX_RESULTS = 3
    }
}
