package com.watchout.wear.voice

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.watchout.command.CommandSender
import com.watchout.command.RobotCommand
import com.watchout.command.VoiceConversation
import com.watchout.command.VoiceResult

/** 음성 화면이 그릴 상태 */
sealed interface VoiceUiState {
    /** 버튼을 기다리는 중. [hint]는 직전 결과에 대한 안내(없으면 기본 안내) */
    data class Idle(val hint: String? = null) : VoiceUiState

    /** 버튼을 누르고 있어 듣는 중. [heard]는 지금까지 받아쓴 말(화면에만 보여 준다) */
    data class Listening(val heard: String = "") : VoiceUiState

    /** 기기 안 음성 인식이 없는 워치. 클라우드로 넘기지 않고 이 안내만 보여 준다. */
    data object Unavailable : VoiceUiState
}

/**
 * 워치 음성 명령 화면의 두뇌 (#69).
 *
 * 흐름: 버튼 누름 → [OnDeviceSpeechInput]로 듣기 → 받아쓴 말을 [VoiceConversation]이 해석
 *       → 알아들으면 [CommandSender]로 명령 전송 + 진동, 못 알아들으면 안내 + 다른 진동.
 *
 * 받아쓴 글자는 화면 상태([VoiceUiState.Listening.heard])로만 쓰고 로그·서버로 보내지 않는다.
 * 명령 JSON만 [lastCommandJson]과 [CommandSender]로 나간다.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    private val speech = OnDeviceSpeechInput(app)
    private val conversation = VoiceConversation()

    /** 지금은 Logcat 출력. #42 통신이 정해지면 이 한 줄만 바꾼다. */
    private val sender: CommandSender = LogCommandSender()

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }

    var state by mutableStateOf<VoiceUiState>(
        if (speech.isAvailable) VoiceUiState.Idle() else VoiceUiState.Unavailable,
    )
        private set

    /** 마지막으로 보낸 명령 JSON (화면 확인용) */
    var lastCommandJson by mutableStateOf<String?>(null)
        private set

    private val listener = object : OnDeviceSpeechInput.Listener {
        override fun onPartial(candidates: List<String>) {
            if (state !is VoiceUiState.Listening) return
            state = VoiceUiState.Listening(candidates.firstOrNull().orEmpty())
            // 멈춤은 문장이 끝나기를 기다리지 않는다. 후보 중 하나라도 멈춤이면 바로 보낸다.
            candidates.firstNotNullOfOrNull(conversation::onPartial)?.let(::send)
        }

        override fun onFinal(candidates: List<String>) {
            when (val result = conversation.onFinal(candidates)) {
                null -> state = VoiceUiState.Idle() // 멈춤을 이미 보냈다
                is VoiceResult.Understood -> {
                    send(result.command)
                    state = VoiceUiState.Idle()
                }
                VoiceResult.AskTarget -> {
                    vibrate(ASK_VIBRATION_MS)
                    state = VoiceUiState.Idle("어떤 물건을 가져올까요?\n다시 눌러 물건 이름을 말해 주세요")
                }
                VoiceResult.NotUnderstood -> notUnderstood()
            }
        }

        override fun onError(code: Int) {
            conversation.reset()
            state = when (code) {
                // 아무 말도 못 들었거나 알아듣지 못함
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    vibrateFail()
                    VoiceUiState.Idle("잘 못 들었어요. 다시 말해 주세요")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    VoiceUiState.Idle("마이크 권한이 필요해요")
                // 한국어 기기 안 인식 모델이 없다 → 클라우드로 넘기지 않고 알린다
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    VoiceUiState.Idle("이 워치에 한국어 음성 인식이 없어요")
                // 버튼을 너무 짧게 누르는 등 인식기가 바쁘거나 끊긴 경우
                else -> VoiceUiState.Idle("다시 한 번 눌러 주세요 (오류 $code)")
            }
        }
    }

    /** 버튼을 누른 순간 */
    fun startListening() {
        if (state is VoiceUiState.Unavailable || state is VoiceUiState.Listening) return
        conversation.startTurn()
        state = VoiceUiState.Listening()
        speech.start(listener)
    }

    /** 버튼에서 손을 뗀 순간. 지금까지 들은 말로 결과를 낸다. */
    fun finishListening() {
        if (state is VoiceUiState.Listening) speech.finish()
    }

    /** 화면을 나갈 때. 듣던 것은 버리고 마이크를 돌려준다. */
    fun leave() {
        speech.cancel()
        speech.release()
        conversation.reset()
        if (state !is VoiceUiState.Unavailable) state = VoiceUiState.Idle()
    }

    private fun send(command: RobotCommand) {
        sender.send(command)
        lastCommandJson = command.toJson()
        // 멈춤은 길고 또렷하게, 다른 명령은 짧게
        vibrate(if (command == RobotCommand.Stop) STOP_VIBRATION_MS else OK_VIBRATION_MS)
    }

    private fun notUnderstood() {
        vibrateFail()
        state = VoiceUiState.Idle("못 알아들었어요. 명령을 보내지 않았어요")
    }

    private fun vibrate(ms: Long) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** "못 알아들었어요" 진동: 짧게 두 번. 명령을 보냈을 때(한 번)와 손목으로 구분된다. */
    private fun vibrateFail() {
        vibrator.vibrate(VibrationEffect.createWaveform(FAIL_PATTERN_MS, -1))
    }

    override fun onCleared() {
        speech.release()
    }

    companion object {
        private const val OK_VIBRATION_MS = 80L
        private const val STOP_VIBRATION_MS = 250L
        private const val ASK_VIBRATION_MS = 40L
        // 쉬기 0ms → 떨림 60ms → 쉬기 80ms → 떨림 60ms
        private val FAIL_PATTERN_MS = longArrayOf(0, 60, 80, 60)
    }
}
