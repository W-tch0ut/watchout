package com.watchout.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.gesture.GesturePresets
import com.watchout.gesture.GestureSummary
import com.watchout.gesture.RegPhase
import com.watchout.gesture.WatchLink
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.gesture.GestureViewModel
import com.watchout.mobile.gesture.Registration

/**
 * 내 제스처 화면 (본인 모드).
 * 등록·삭제는 폰에서 하고, 동작 녹화는 워치가 한다. 손짓하는 손으로 워치를 누르지 않아도 된다.
 */
@Composable
fun GestureScreen(vm: GestureViewModel, user: AppUser, onChangeMode: () -> Unit, onSignOut: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<GestureSummary?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeHeader(title = "내 제스처", user = user, onChangeMode = onChangeMode, onSignOut = onSignOut)
        Text(
            "워치를 찬 손으로 할 동작을 등록해요. 로봇카에 연결돼 있으면 손짓만으로 로봇카를 부를 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.message?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissMessage) { Text("닫기") }
            }
        }

        val gestures = state.gestures
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            when {
                gestures == null -> item { Text("워치에서 목록을 불러오는 중… (워치 앱을 한 번 열어 주세요)") }
                gestures.isEmpty() -> item { Text("아직 등록한 제스처가 없어요.") }
                else -> items(gestures, key = { it.id }) { g ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.titleMedium)
                                Text("→ ${g.command.label} · 시연 ${g.samples}번", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { deleting = g }) { Text("삭제") }
                        }
                    }
                }
            }
        }
        Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("새 제스처 등록") }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("어떤 제스처인가요?") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(GesturePresets.ALL) { (name, cmd) ->
                        Card(
                            onClick = {
                                picking = false
                                vm.start(name, cmd)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text("→ ${cmd.label}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("취소") } },
        )
    }

    state.registration?.let { reg ->
        RegistrationDialog(reg, onRecord = vm::record, onSave = vm::save, onClose = vm::close)
    }

    deleting?.let { g ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("삭제할까요?") },
            text = { Text("'${g.name}' 제스처를 워치에서 지워요.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(g.id)
                    deleting = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

/** "워치를 확인하세요!" 등록 창. 시연 버튼은 폰에 있다 (워치를 찬 손은 동작만 한다) */
@Composable
private fun RegistrationDialog(reg: Registration, onRecord: () -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    val w = reg.watch
    val saved = w?.phase == RegPhase.SAVED
    AlertDialog(
        onDismissRequest = { }, // 바깥을 눌러 실수로 닫히지 않게
        title = { Text(if (saved) "등록했어요 ✓" else "⌚ 워치를 확인하세요!") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${reg.name}  →  ${reg.command.label}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${w?.count ?: 0} / ${WatchLink.GOOD_SAMPLES}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when (w?.phase) {
                        null -> "워치에 등록 화면을 띄우는 중…\n(안 뜨면 워치 앱을 열어 주세요)"
                        RegPhase.READY -> "'시연'을 누르고 3초 뒤 워치가 '지금!' 하고 떨면\n워치를 찬 손으로 동작을 한 번 하세요"
                        RegPhase.COUNTDOWN -> "${w.secondsLeft}…  준비하세요"
                        RegPhase.RECORDING -> "● 지금! 동작하세요"
                        RegPhase.ADDED -> "${w.count}번째 저장 ✓ " +
                            if (w.count < WatchLink.GOOD_SAMPLES) "같은 동작으로 한 번 더" else "충분해요. 저장하세요"
                        RegPhase.FAILED -> w.message.ifEmpty { "동작이 안 잡혔어요. 조금 더 크게 해 보세요" }
                        RegPhase.SAVED -> "로봇카에 연결돼 있으면 바로 써요"
                        RegPhase.CANCELLED -> "워치에서 취소했어요"
                    },
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            when {
                saved -> Button(onClick = onClose) { Text("완료") }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSave, enabled = w?.canSave == true) { Text("저장") }
                    Button(onClick = onRecord, enabled = w != null && !w.busy && w.phase != RegPhase.CANCELLED) {
                        Text("시연")
                    }
                }
            }
        },
        dismissButton = { if (!saved) TextButton(onClick = onClose) { Text("취소") } },
    )
}
