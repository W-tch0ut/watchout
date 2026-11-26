package com.watchout.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchout.gesture.Gesture
import com.watchout.gesture.GestureResult
import com.watchout.gesture.WearingHand
import com.watchout.wear.GestureToolViewModel
import com.watchout.wear.RecordPhase
import java.util.Locale

/** 화면에 같이 보여줄 그림 문자 */
val Gesture.emoji: String
    get() = when (this) {
        Gesture.ROTATE_CW -> "↻"
        Gesture.ROTATE_CCW -> "↺"
        Gesture.PUSH -> "⬆"
        Gesture.PULL -> "⬇"
        Gesture.SWING_LEFT -> "⬅"
        Gesture.SWING_RIGHT -> "➡"
        Gesture.PRESS_DOWN -> "✋"
        Gesture.IDLE -> "·"
    }

/** 시계·스크롤 표시가 붙은 세로 목록 화면 */
@Composable
private fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** 화면이 보이는 동안 화면이 꺼지지 않게 한다. 제스처를 하는 동안 화면이 꺼지면 센서가 멈춘다. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun MenuScreen(vm: GestureToolViewModel, onRecognize: () -> Unit, onCollect: () -> Unit) {
    ListScreen {
        item { Text("제스처 툴", style = MaterialTheme.typography.title3) }
        if (!vm.sensorsAvailable) {
            item { Text("⚠ 가속도·자이로 센서가 없어요", color = Color(0xFFFF8A80), textAlign = TextAlign.Center) }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("실시간 인식") },
                secondaryLabel = { Text("제스처를 하면 이름을 알려줘요") },
                onClick = onRecognize,
                enabled = vm.sensorsAvailable,
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("데이터 모으기") },
                secondaryLabel = { Text("AI 학습용 녹화") },
                onClick = onCollect,
                enabled = vm.sensorsAvailable,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("착용: ${if (vm.hand == WearingHand.LEFT) "왼손" else "오른손"}") },
                secondaryLabel = { Text("눌러서 바꾸기") },
                onClick = vm::toggleHand,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
fun RecognizeScreen(vm: GestureToolViewModel) {
    KeepScreenOn()
    DisposableEffect(Unit) {
        vm.startRecognizing()
        onDispose { vm.stopRecognizing() }
    }
    ListScreen {
        item { ResultCard(vm.lastResult) }
        if (vm.history.size > 1) {
            item { Text("최근", style = MaterialTheme.typography.caption2, color = Color.Gray) }
            items(vm.history.size - 1) { i ->
                val r = vm.history[i + 1]
                Text(
                    "${r.gesture.emoji} ${r.gesture.label}  ${percent(r.confidence)}",
                    style = MaterialTheme.typography.caption1,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: GestureResult?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (result == null) {
            Text("제스처를 해보세요", style = MaterialTheme.typography.title3)
            Spacer(Modifier.height(4.dp))
            Text("갔다가 돌아오는 동작 한 번", style = MaterialTheme.typography.caption2, color = Color.Gray)
            return
        }
        val recognized = result.gesture != Gesture.IDLE
        Text(result.gesture.emoji, fontSize = 40.sp)
        Text(
            if (recognized) result.gesture.label else "모르겠어요",
            style = MaterialTheme.typography.title2,
            fontWeight = FontWeight.Bold,
            color = if (recognized) Color(0xFF8AB4F8) else Color.Gray,
        )
        Text("확신 ${percent(result.confidence)}", style = MaterialTheme.typography.caption1)
        // 규칙을 조정할 때 보는 숫자: 손목 회전량(rad)과 축별 최대 가속도(m/s²)
        val f = result.features
        Text(
            String.format(
                Locale.ROOT,
                "회전 %.2f  가속 %.1f/%.1f/%.1f  %dms",
                f.rotation.x, f.linearPeak.x, f.linearPeak.y, f.linearPeak.z, f.durationMs,
            ),
            style = MaterialTheme.typography.caption3,
            color = Color.Gray,
        )
    }
}

@Composable
fun CollectScreen(vm: GestureToolViewModel, onPick: (Gesture) -> Unit) {
    ListScreen {
        item { Text("어떤 동작을 녹화할까요?", style = MaterialTheme.typography.caption1) }
        Gesture.entries.forEach { g ->
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${g.emoji} ${g.label}") },
                    secondaryLabel = { Text("${vm.counts[g] ?: 0}개 모음") },
                    onClick = { onPick(g) },
                    colors = if (g == Gesture.IDLE) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                )
            }
        }
        item {
            Text(
                "저장 위치\n${vm.storagePath}",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun RecordScreen(vm: GestureToolViewModel, label: Gesture) {
    KeepScreenOn()
    DisposableEffect(label) {
        vm.enterRecordScreen()
        onDispose { vm.leaveRecordScreen() }
    }
    val phase = vm.recordPhase
    ListScreen {
        item { Text("${label.emoji} ${label.label}", style = MaterialTheme.typography.title3) }
        item {
            Text(
                label.howTo,
                style = MaterialTheme.typography.caption2,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
        item {
            when (phase) {
                is RecordPhase.Countdown -> Text("${phase.secondsLeft}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                RecordPhase.Recording -> Text("● 지금 동작!", color = Color(0xFFFF5252), style = MaterialTheme.typography.title2)
                else -> Button(
                    onClick = { vm.record(label) },
                    colors = ButtonDefaults.primaryButtonColors(),
                ) { Text("녹화") }
            }
        }
        item {
            val status = when (phase) {
                is RecordPhase.Saved -> "저장됨 ✓ (${phase.count}개)"
                is RecordPhase.Failed -> "실패: ${phase.reason}"
                RecordPhase.Ready -> "${vm.counts[label] ?: 0}개 모음 · 진동 후 2.5초 녹화"
                else -> ""
            }
            Text(status, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        }
        if (phase is RecordPhase.Saved) {
            item {
                Row(horizontalArrangement = Arrangement.Center) {
                    CompactChip(
                        onClick = { vm.deleteLatest(label) },
                        label = { Text("방금 것 지우기") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}

private fun percent(v: Float) = "${(v * 100).toInt()}%"
