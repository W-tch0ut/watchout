package com.watchout.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchout.gesture.Gesture
import com.watchout.wear.demo.DemoState
import com.watchout.wear.demo.DemoViewModel
import com.watchout.wear.demo.PhoneLink
import com.watchout.wear.ui.CarScreen
import com.watchout.wear.ui.CollectScreen
import com.watchout.wear.ui.FallOverlay
import com.watchout.wear.ui.FallScreen
import com.watchout.wear.ui.GesturesScreen
import com.watchout.wear.ui.MenuScreen
import com.watchout.wear.ui.NewGestureScreen
import com.watchout.wear.ui.RecognizeScreen
import com.watchout.wear.ui.RecordScreen
import com.watchout.wear.ui.RegisterGestureScreen
import com.watchout.wear.ui.RemoteRegOverlay
import com.watchout.wear.ui.SamsungCollectScreen
import com.watchout.wear.ui.VoiceScreen
import com.watchout.wear.voice.VoiceViewModel

/**
 * 화면 구성. 오른쪽으로 밀면 이전 화면으로 돌아간다.
 *
 * menu ─┬─ car                 로봇카 연결 상태, 최근 기록 (연결은 폰 앱)  (사용자)
 *       ├─ gestures ─ gesture_new ─ gesture_register/{i}   내 제스처 목록 (등록은 폰 앱, 워치 등록은 개발자 모드)
 *       ├─ voice               음성 명령 (누른 채로 말하기, #69)       (사용자)
 *       ├─ fall                낙상 감지 켜기·테스트                    (개발자 모드)
 *       ├─ recognize           실시간 인식                            (개발자 모드)
 *       ├─ collect ─ record/{id}   라벨 고르기 → 녹화                 (개발자 모드)
 *       └─ samsung            삼성 유니버설 제스처 수집               (개발자 모드)
 *
 * 낙상 의심·결과([FallOverlay])와 폰에서 시작한 제스처 등록([RemoteRegOverlay])은 어느 화면에 있든 위에 덮어서 보여준다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: GestureToolViewModel = viewModel()
                val demo: DemoViewModel = viewModel()
                // 화면이 다시 보일 때, 그리고 어디서든 제스처가 바뀌었을 때 목록을 파일에서 다시 읽는다
                LifecycleResumeEffect(DemoState.gesturesVersion) {
                    demo.refreshGestures()
                    onPauseOrDispose { }
                }
                // 화면이 보일 때: 폰이 알려준 로봇카 상태를 읽고, 막혔던 감지 시작을 하고, 제스처 목록을 폰에 올린다
                LifecycleResumeEffect(Unit) {
                    PhoneLink.onAppOpened(this@MainActivity)
                    onPauseOrDispose { }
                }
                val nav = rememberSwipeDismissableNavController()
                Box {
                    SwipeDismissableNavHost(navController = nav, startDestination = "menu") {
                        composable("menu") {
                            MenuScreen(
                                vm = vm,
                                onRecognize = { nav.navigate("recognize") },
                                onCollect = { nav.navigate("collect") },
                                onSamsungCollect = { nav.navigate("samsung") },
                                onCar = { nav.navigate("car") },
                                onGestures = { nav.navigate("gestures") },
                                onFall = { nav.navigate("fall") },
                                onVoice = { nav.navigate("voice") },
                                gestureCount = demo.gestures.size,
                                devMode = demo.devMode,
                                onTitleTap = demo::onTitleTap,
                                onExitDevMode = { demo.changeDevMode(false) },
                            )
                        }
                        composable("car") { CarScreen(demo) }
                        composable("gestures") { GesturesScreen(demo, onNew = { nav.navigate("gesture_new") }) }
                        composable("gesture_new") {
                            NewGestureScreen { name, _ ->
                                val i = DemoViewModel.NAME_PRESETS.indexOfFirst { it.first == name }
                                nav.navigate("gesture_register/$i")
                            }
                        }
                        composable("gesture_register/{i}") { entry ->
                            val i = entry.arguments?.getString("i")?.toIntOrNull() ?: 0
                            val (name, cmd) = DemoViewModel.NAME_PRESETS[i]
                            RegisterGestureScreen(demo, name, cmd, onDone = { nav.popBackStack("gestures", false) })
                        }
                        composable("voice") { VoiceScreen(viewModel<VoiceViewModel>()) }
                        composable("fall") { FallScreen(demo) }
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
                    // 폰에서 시작한 제스처 등록 (워치를 보라는 화면)
                    DemoState.remoteReg?.let { RemoteRegOverlay(it) }
                    DemoState.fall?.let { FallOverlay(it, onOk = demo::cancelFall) }
                }
            }
        }
    }
}
