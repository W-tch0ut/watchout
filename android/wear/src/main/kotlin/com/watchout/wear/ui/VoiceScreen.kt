package com.watchout.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchout.wear.voice.VoiceUiState
import com.watchout.wear.voice.VoiceViewModel

/**
 * 음성 명령 화면 (#69).
 *
 * 가운데 동그란 버튼을 **누르고 있는 동안만** 듣는다(무전기처럼). 손을 떼면 들은 말을 해석해
 * 명령을 보낸다. 듣지 않을 때는 마이크를 쓰지 않으므로 TV 소리에 잘못 반응하지 않는다.
 */
@Composable
fun VoiceScreen(vm: VoiceViewModel) {
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }

    KeepScreenOn()
    DisposableEffect(Unit) { onDispose { vm.leave() } }

    val state = vm.state
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state is VoiceUiState.Unavailable) {
                Text(
                    "이 워치는 기기 안 음성 인식을 지원하지 않아요.\n목소리를 밖으로 보내지 않기 위해 음성 명령을 끕니다.",
                    style = MaterialTheme.typography.caption2,
                    color = Color(0xFFFF8A80),
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            Text(
                text = statusText(state, micGranted),
                style = MaterialTheme.typography.caption2,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            TalkButton(
                listening = state is VoiceUiState.Listening,
                onPress = {
                    if (micGranted) {
                        vm.startListening()
                        true
                    } else {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    }
                },
                onRelease = vm::finishListening,
            )
            Text(
                text = vm.lastCommandJson ?: "보낸 명령 없음",
                style = MaterialTheme.typography.caption3,
                color = if (vm.lastCommandJson != null) Color(0xFF8AB4F8) else Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun statusText(state: VoiceUiState, micGranted: Boolean): String = when {
    !micGranted -> "버튼을 눌러 마이크를 허용해 주세요"
    state is VoiceUiState.Listening -> state.heard.ifEmpty { "듣는 중…" }
    state is VoiceUiState.Idle && state.hint != null -> state.hint
    else -> "누른 채로 말하세요\n\"이리 와\" \"멈춰\""
}

/**
 * 누르는 동안만 듣는 동그란 버튼.
 * 일반 Button은 "눌렀다 뗀 뒤" 한 번만 알려 주므로, 누름·뗌을 따로 알 수 있게 직접 만든다.
 *
 * @param onPress 눌렀을 때. 듣기를 시작했으면 true (권한이 없어서 묻기만 했으면 false)
 */
@Composable
private fun TalkButton(listening: Boolean, onPress: () -> Boolean, onRelease: () -> Unit) {
    // pointerInput 블록은 한 번만 만들어지므로, 매번 바뀌는 콜백은 최신 값을 따로 들고 있는다.
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(76.dp)
            .clip(CircleShape)
            .background(if (listening) Color(0xFFFF5252) else Color(0xFF8AB4F8))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (press()) {
                            // 손을 뗄 때까지(또는 손가락이 밖으로 미끄러질 때까지) 기다린다
                            tryAwaitRelease()
                            release()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (listening) "듣는 중" else "🎤", fontSize = if (listening) 14.sp else 28.sp, color = Color.Black)
    }
}
