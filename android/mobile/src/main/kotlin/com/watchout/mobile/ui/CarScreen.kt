package com.watchout.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.gesture.LinkState
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.car.CarLinkState
import com.watchout.mobile.car.CarViewModel

/**
 * 로봇카 화면 (본인 모드).
 * 폰이 Wi-Fi로 로봇카를 찾아 직접 연결한다. 워치는 폰을 통해 제스처 명령을 보내고, 연결 상태만 본다.
 */
@Composable
fun CarScreen(vm: CarViewModel, user: AppUser, onChangeMode: () -> Unit, onSignOut: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 연결 중 알림(앱을 닫아도 연결 유지)과 낙상 알림을 띄우려면 알림 권한이 필요하다 (안드로이드 13+)
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleResumeEffect(Unit) {
        vm.checkWatch()
        onPauseOrDispose { }
    }
    val connected = state.link.state != LinkState.OFF

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeHeader(title = "로봇카", user = user, onChangeMode = onChangeMode, onSignOut = onSignOut)
        LinkCard(state.link, onDisconnect = vm::disconnect)
        Text(
            when (state.watchConnected) {
                null -> "⌚ 워치 확인 중…"
                true -> "⌚ 워치 연결됨 · 워치 제스처가 이 폰을 거쳐 로봇카로 가요"
                false -> "⌚ 연결된 워치가 없어요. Galaxy Wearable 앱에서 워치를 연결해 주세요"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        if (connected) {
            Text("최근 일", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                if (state.events.isEmpty()) item { Text("워치로 손짓하면 여기에 기록돼요", style = MaterialTheme.typography.bodySmall) }
                items(state.events) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            Button(onClick = vm::scan, enabled = !state.scanning, modifier = Modifier.fillMaxWidth()) {
                if (state.scanning) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                Text(if (state.scanning) "찾는 중…" else "로봇카 찾기")
            }
            state.scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(state.cars, key = { it.id }) { car ->
                    Card(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.connect(car)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(car.name, style = MaterialTheme.typography.titleMedium)
                            Text("${car.host}:${car.port} · 눌러서 연결", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** 폰 ↔ 로봇카 연결 상태 */
@Composable
private fun LinkCard(link: CarLinkState, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (link.state) {
                        LinkState.OFF -> "🚗 연결 안 됨"
                        LinkState.CONNECTING -> "🚗 ${link.car?.name} 연결 중…"
                        LinkState.CONNECTED -> "🚗 ${link.car?.name} 연결됨 ✓"
                        LinkState.ERROR -> "🚗 ${link.car?.name} 연결 끊김 (다시 연결 중)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (link.message.isNotEmpty() && link.state != LinkState.OFF) {
                    Text(link.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (link.state != LinkState.OFF) TextButton(onClick = onDisconnect) { Text("끊기") }
        }
    }
}
