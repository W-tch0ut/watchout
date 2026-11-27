package com.watchout.wear.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchout.gesture.CarCommand
import com.watchout.gesture.RegPhase
import com.watchout.gesture.RegState
import com.watchout.gesture.WatchLink
import com.watchout.wear.demo.DemoState
import com.watchout.wear.demo.DemoViewModel
import com.watchout.wear.demo.DemoViewModel.RegPhase as LocalRegPhase
import com.watchout.wear.demo.FallUi
import com.watchout.wear.demo.connectionSummary

private val Blue = Color(0xFF8AB4F8)
private val Red = Color(0xFFFF5252)
private val Orange = Color(0xFFFFB74D)

// ===================== 로봇카 연결 =====================

@Composable
fun CarScreen(vm: DemoViewModel) {
    // 감지 중 알림·낙상 알림을 띄우려면 알림 권한이 필요하다 (안드로이드 13+). 처음 들어올 때 한 번 묻는다
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val connected = DemoState.connection != DemoState.Connection.OFF

    // 로봇카 찾기·연결은 폰 앱에서 한다. 워치는 상태만 보여준다.
    ListScreen {
        item { Text("🚗 로봇카", style = MaterialTheme.typography.title3) }
        if (!connected) {
            item {
                Text(
                    "연결 안 됨\n\n폰 앱 → 🚗 로봇카에서\n연결해 주세요",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2,
                )
            }
            return@ListScreen
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(DemoState.carName ?: "", fontWeight = FontWeight.Bold)
                Text(
                    DemoState.connectionSummary(),
                    color = if (DemoState.connection == DemoState.Connection.CONNECTED) Blue else Orange,
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
                if (DemoState.carStatus.isNotEmpty()) {
                    Text("차: ${DemoState.carStatus}", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
                }
                if (vm.fallDetectionOn) {
                    Text(
                        "🛡 낙상 감지 중 · 넘어지면 바로 물어볼게요",
                        style = MaterialTheme.typography.caption3,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (vm.devMode) {
            // ----- 개발자 모드: 버튼으로 명령 보내기 (폰을 거쳐 로봇카로) -----
            listOf(CarCommand.APPROACH, CarCommand.STOP, CarCommand.FOLLOW).forEach { cmd ->
                item {
                    CompactChip(
                        onClick = { vm.send(cmd) },
                        label = { Text(cmd.label) },
                        enabled = DemoState.connection == DemoState.Connection.CONNECTED,
                    )
                }
            }
        }
        item { EventLog() }
    }
}

@Composable
private fun EventLog() {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        if (DemoState.events.isEmpty()) {
            Text("제스처를 하면 여기에 기록돼요", style = MaterialTheme.typography.caption3, color = Color.Gray)
        }
        DemoState.events.take(5).forEach {
            Text(it, style = MaterialTheme.typography.caption3)
        }
    }
}

// ===================== 내 제스처 =====================

@Composable
fun GesturesScreen(vm: DemoViewModel, onNew: () -> Unit) {
    ListScreen {
        item { Text("✋ 내 제스처", style = MaterialTheme.typography.title3) }
        item {
            Text(
                "로봇카에 연결돼 있으면 화면을 꺼도 알아들어요",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
        vm.gestures.forEach { g ->
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(g.name) },
                    secondaryLabel = { Text("→ ${g.command.label} · 시연 ${g.templates.size}번") },
                    onClick = { },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item { CompactChip(onClick = { vm.delete(g.id) }, label = { Text("‘${g.name}’ 삭제") }) }
        }
        item {
            Text(
                "새 제스처는 폰 앱 → ✋ 제스처에서 등록해요",
                style = MaterialTheme.typography.caption3,
                color = Blue,
                textAlign = TextAlign.Center,
            )
        }
        if (vm.devMode) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("+ 워치에서 등록 (개발자)") },
                    secondaryLabel = { Text("같은 동작 5번 시연") },
                    onClick = onNew,
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

/** 이름(추천 명령 포함)과 명령을 고른다. 고른 뒤 등록 화면으로 간다 */
@Composable
fun NewGestureScreen(onPick: (name: String, command: CarCommand) -> Unit) {
    ListScreen {
        item { Text("어떤 제스처인가요?", style = MaterialTheme.typography.caption1) }
        DemoViewModel.NAME_PRESETS.forEach { (name, cmd) ->
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(name) },
                    secondaryLabel = { Text("→ ${cmd.label}") },
                    onClick = { onPick(name, cmd) },
                )
            }
        }
    }
}

@Composable
fun RegisterGestureScreen(vm: DemoViewModel, name: String, command: CarCommand, onDone: () -> Unit) {
    KeepScreenOn()
    DisposableEffect(Unit) {
        vm.beginRegistration()
        onDispose { vm.endRegistration() }
    }
    val phase = vm.regPhase
    val count = vm.regShapes.size
    ListScreen {
        item { Text(name, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
        item { Text("→ ${command.label}", style = MaterialTheme.typography.caption2, color = Blue) }
        item {
            Text(
                "'시연'을 누르고 3초 뒤 '● 지금!'이 뜨면 동작을 한 번 하세요. ${DemoViewModel.GOOD_SAMPLES}번 반복",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
        item { Text("$count / ${DemoViewModel.GOOD_SAMPLES}", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item {
            when (phase) {
                is LocalRegPhase.Countdown -> Text("${phase.secondsLeft}", fontSize = 32.sp)
                LocalRegPhase.Recording -> Text("● 지금!", color = Red, style = MaterialTheme.typography.title2)
                LocalRegPhase.Saved -> Button(onClick = onDone) { Text("완료") }
                else -> Button(onClick = vm::recordSample, colors = ButtonDefaults.primaryButtonColors()) { Text("시연") }
            }
        }
        item {
            val msg = when (phase) {
                is LocalRegPhase.Added -> "${phase.count}번째 저장 ✓"
                is LocalRegPhase.Failed -> phase.reason
                LocalRegPhase.Saved -> "등록했어요! 로봇카에 연결하면 바로 써요"
                else -> ""
            }
            Text(msg, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        }
        if (count >= DemoViewModel.MIN_SAMPLES && phase != LocalRegPhase.Saved) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("저장하기") },
                    secondaryLabel = { Text(if (count < DemoViewModel.GOOD_SAMPLES) "더 하면 더 정확해요" else "충분해요") },
                    onClick = { vm.saveGesture(name, command) },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
        }
    }
}

// ===================== 낙상 감지 =====================

@Composable
fun FallScreen(vm: DemoViewModel) {
    val connected = DemoState.connection == DemoState.Connection.CONNECTED
    ListScreen {
        item { Text("🆘 낙상 감지", style = MaterialTheme.typography.title3) }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = vm.fallDetectionOn,
                onCheckedChange = { vm.toggleFallDetection() },
                label = { Text("낙상 감지") },
                secondaryLabel = { Text(if (vm.fallDetectionOn) "켜짐" else "꺼짐") },
                toggleControl = { androidx.wear.compose.material.Icon(ToggleChipDefaults.switchIcon(vm.fallDetectionOn), null) },
            )
        }
        item {
            Text(
                "넘어짐이 감지되면 10초 동안 '괜찮으세요?'를 묻고, 대답이 없으면 로봇카가 가서 카메라로 확인한 뒤 보호자에게 알려요.",
                style = MaterialTheme.typography.caption3,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                label = { Text("낙상 테스트") },
                secondaryLabel = { Text(if (connected) "넘어진 것처럼 시작" else "먼저 로봇카에 연결하세요") },
                onClick = vm::testFall,
                enabled = connected,
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

/** 낙상 의심·결과를 화면 전체로 덮어 보여준다. (앱을 열었을 때) */
@Composable
fun FallOverlay(fall: FallUi, onOk: () -> Unit) {
    val bg = when (fall.phase) {
        FallUi.Phase.ASKING -> Color(0xFF8B0000)
        FallUi.Phase.CONFIRMED -> Color(0xFF5D1F00)
        else -> Color(0xFF102A43)
    }
    Box(
        modifier = Modifier.fillMaxSize().background(bg).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fall.message, style = MaterialTheme.typography.title2, textAlign = TextAlign.Center, color = Color.White)
            when (fall.phase) {
                FallUi.Phase.ASKING -> {
                    Text("${fall.secondsLeft}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Button(
                        onClick = onOk,
                        modifier = Modifier.fillMaxWidth(0.7f),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.White, contentColor = Color.Black),
                    ) { Text("괜찮아요") }
                }
                FallUi.Phase.SENT -> Text("잠시만 기다려 주세요", color = Color.White, style = MaterialTheme.typography.caption1)
                FallUi.Phase.CANCELLED -> Unit
                else -> Button(onClick = onOk) { Text("닫기") }
            }
        }
    }
}

/**
 * 폰에서 시작한 제스처 등록 화면. 버튼은 폰에 있고, 워치는 지금 단계만 크게 보여준다.
 * (워치를 찬 손은 동작만 하면 된다)
 */
@Composable
fun RemoteRegOverlay(reg: RegState) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B2545)).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("✋ ${reg.name}", style = MaterialTheme.typography.caption1, color = Color.White, textAlign = TextAlign.Center)
            when (reg.phase) {
                RegPhase.COUNTDOWN -> Text("${reg.secondsLeft}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
                RegPhase.RECORDING -> Text("● 지금!", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Red)
                else -> Text(
                    "${reg.count} / ${WatchLink.GOOD_SAMPLES}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Text(
                when (reg.phase) {
                    RegPhase.READY -> "폰에서 '시연'을 누르세요"
                    RegPhase.COUNTDOWN -> "준비하세요"
                    RegPhase.RECORDING -> "동작을 한 번 하세요"
                    else -> reg.message
                },
                style = MaterialTheme.typography.caption2,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}
