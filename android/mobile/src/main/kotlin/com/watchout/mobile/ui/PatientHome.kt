package com.watchout.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** 본인(환자) 모드의 탭 */
enum class PatientTab(val icon: String, val label: String) {
    CAR("🚗", "로봇카"),
    GESTURES("✋", "제스처"),
    GUARDIANS("👪", "보호자"),
    SMS("✉️", "문자"),
}

/**
 * 본인(환자) 모드 첫 화면: 아래 탭으로 로봇카 / 보호자 연락처 / 응급 문자를 오간다.
 * 각 탭의 내용은 [content]가 그린다 (ViewModel을 MainActivity에서 만들어 넘기려고).
 */
@Composable
fun PatientHome(content: @Composable (PatientTab) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(PatientTab.CAR) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                PatientTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Text(t.icon) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { inner ->
        // 각 화면이 화면 가장자리(상태 표시줄 등) 여백을 스스로 넣으므로, 아래 탭 높이만큼은 이미 썼다고 알려 준다
        Box(Modifier.padding(inner).consumeWindowInsets(inner)) { content(tab) }
    }
}
