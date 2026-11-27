package com.watchout.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchout.wear.GestureToolViewModel

/**
 * 삼성 제스처 수집 화면.
 *
 * 들어오면 (필요하면 신체 센서 권한을 묻고) 바로 연속 기록을 시작하고, 나가면 멈춘다.
 * 이 화면을 띄운 채로 삼성 유니버설 제스처를 하면 된다.
 *
 * 화면에 누를 수 있는 것을 일부러 하나도 두지 않았다. 유니버설 제스처의 "주먹 쥐기"는
 * 화면의 강조된 항목을 탭하는 동작이라, 버튼이 있으면 기록 중에 눌려 버린다.
 * 같은 이유로 "뒤로 가기"도 무시한다. 끝낼 때는 화면을 오른쪽으로 밀어서 나간다.
 */
@Composable
fun SamsungCollectScreen(vm: GestureToolViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    var permissionAsked by remember { mutableStateOf(false) }
    var ppgGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ppgGranted = granted
        permissionAsked = true
    }

    // 권한이 없고 심박 센서가 있으면 한 번 묻고, 답을 받은 뒤(또는 필요 없으면 바로) 기록을 시작한다.
    LaunchedEffect(Unit) {
        if (!ppgGranted && vm.ppgSensorName != null) {
            launcher.launch(Manifest.permission.BODY_SENSORS)
        } else {
            permissionAsked = true
        }
    }
    LaunchedEffect(permissionAsked) {
        if (permissionAsked) vm.startSession(withPpg = ppgGranted)
    }
    // 유니버설 제스처 중 "뒤로 가기"로 설정된 것이 있으면 이 화면이 닫혀 기록이 끊긴다.
    // 기록 중에는 뒤로 가기(제스처, 버튼)를 무시한다. 손으로 밀어서 나가는 것은 그대로 된다.
    var blockedBacks by remember { mutableIntStateOf(0) }
    BackHandler(enabled = true) { blockedBacks++ }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            vm.stopSession()
        }
    }

    val s = vm.session
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s == null) {
            Text("준비 중…", style = MaterialTheme.typography.title3)
            return@Column
        }
        Text("● 기록 중 ${s.elapsedSec}초", color = Color(0xFFFF5252), style = MaterialTheme.typography.title3)
        Text(
            "가속 ${s.acc} · 자이로 ${s.gyro}\n" +
                if (s.ppgOn) "심박 ${s.ppg}" else "심박 기록 안 함 (${if (vm.ppgSensorName == null) "센서 없음" else "권한 없음"})",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
        )
        if (blockedBacks > 0) {
            Text("뒤로 가기 무시함 ${blockedBacks}번", style = MaterialTheme.typography.caption3, color = Color(0xFFFFB74D))
        }
        Text(
            "유니버설 제스처를 하세요\n끝내려면 밀어서 나가기",
            style = MaterialTheme.typography.caption3,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
        Text(s.fileName, style = MaterialTheme.typography.caption3, color = Color.DarkGray)
    }
}
