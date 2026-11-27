package com.watchout.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchout.gesture.Gesture
import com.watchout.wear.ui.CollectScreen
import com.watchout.wear.ui.MenuScreen
import com.watchout.wear.ui.RecognizeScreen
import com.watchout.wear.ui.RecordScreen
import com.watchout.wear.ui.SamsungCollectScreen

/**
 * 제스처 툴 화면 구성. 오른쪽으로 밀면 이전 화면으로 돌아간다.
 *
 * menu ─┬─ recognize          실시간 인식
 *       ├─ collect ─ record/{id}   라벨 고르기 → 녹화
 *       └─ samsung            삼성 유니버설 제스처 수집 (연속 기록)
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: GestureToolViewModel = viewModel()
                val nav = rememberSwipeDismissableNavController()
                SwipeDismissableNavHost(navController = nav, startDestination = "menu") {
                    composable("menu") {
                        MenuScreen(
                            vm = vm,
                            onRecognize = { nav.navigate("recognize") },
                            onCollect = { nav.navigate("collect") },
                            onSamsungCollect = { nav.navigate("samsung") },
                        )
                    }
                    composable("samsung") { SamsungCollectScreen(vm) }
                    composable("recognize") { RecognizeScreen(vm) }
                    composable("collect") {
                        CollectScreen(vm, onPick = { nav.navigate("record/${it.id}") })
                    }
                    composable("record/{id}") { entry ->
                        val label = Gesture.fromId(entry.arguments?.getString("id") ?: Gesture.IDLE.id)
                        RecordScreen(vm, label)
                    }
                }
            }
        }
    }
}
