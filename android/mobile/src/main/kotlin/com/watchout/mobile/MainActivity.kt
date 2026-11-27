package com.watchout.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchout.mobile.auth.LoginViewModel
import com.watchout.mobile.guardian.GuardianViewModel
import com.watchout.mobile.profile.RoleViewModel
import com.watchout.mobile.profile.UserRole
import com.watchout.mobile.ui.GuardianHomeScreen
import com.watchout.mobile.ui.GuardianScreen
import com.watchout.mobile.ui.LoadingScreen
import com.watchout.mobile.ui.LoginScreen
import com.watchout.mobile.ui.RoleSelectScreen
import kotlinx.coroutines.launch

/**
 * 폰 앱의 유일한 화면(Activity).
 *
 * 로그인 상태를 보고 어떤 화면을 그릴지 정한다.
 *   로그아웃 상태          → LoginScreen
 *   로그인, 모드 안 고름   → RoleSelectScreen (본인 / 보호자)
 *   본인(환자) 모드        → GuardianScreen (보호자 연락처 등록)
 *   보호자 모드            → GuardianHomeScreen (연결된 가족·알림 자리, #50)
 * 로그인·로그아웃하면 currentUser가 바뀌고, 화면이 자동으로 바뀐다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WatchOutApp).container

        setContent {
            MaterialTheme {
                val user by container.authRepository.currentUser.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                val current = user

                // 로그아웃할 때마다 1씩 늘어나는 번호. 화면을 돌려도(rememberSaveable) 유지된다.
                // 연락처 ViewModel의 이름표(key)에 넣어서, 다시 로그인하면 항상 새 ViewModel을 만든다.
                // (같은 계정이라도 로그아웃 때 끊긴 Firestore 실시간 연결을 다시 쓰지 않게)
                var signOutCount by rememberSaveable { mutableIntStateOf(0) }
                LaunchedEffect(current == null) {
                    if (current == null) signOutCount++
                }

                if (current == null) {
                    val vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(container.authRepository))
                    LoginScreen(
                        vm = vm,
                        onGoogleClick = {
                            vm.signInWithGoogle { container.googleSignIn.requestIdToken(this@MainActivity) }
                        },
                    )
                } else {
                    // key = uid + 로그아웃 횟수: 다른 계정이든 같은 계정이든 다시 로그인하면 새 ViewModel
                    val sessionKey = "${current.uid}#$signOutCount"
                    val onSignOut: () -> Unit = {
                        scope.launch {
                            container.googleSignIn.clearState(this@MainActivity)
                            container.authRepository.signOut()
                        }
                    }
                    val roleVm: RoleViewModel = viewModel(
                        key = "role:$sessionKey",
                        factory = RoleViewModel.factory(current.uid, container.profileRepository),
                    )
                    val roleState by roleVm.state.collectAsStateWithLifecycle()

                    // 모드(역할)에 따라 화면을 고른다
                    when {
                        roleState.isLoading -> LoadingScreen()
                        roleState.showSelect -> RoleSelectScreen(
                            current = roleState.role,
                            isSaving = roleState.isSaving,
                            error = roleState.error,
                            canCancel = roleState.canCancel,
                            onChoose = roleVm::choose,
                            onCancel = roleVm::cancelChange,
                            onSignOut = onSignOut,
                        )
                        roleState.role == UserRole.GUARDIAN -> GuardianHomeScreen(
                            user = current,
                            onChangeMode = roleVm::startChange,
                            onSignOut = onSignOut,
                        )
                        else -> { // 본인(환자) 모드: 보호자 연락처 등록
                            val vm: GuardianViewModel = viewModel(
                                key = "guardians:$sessionKey",
                                factory = GuardianViewModel.factory(current.uid, container.guardianRepository),
                            )
                            GuardianScreen(
                                vm = vm,
                                user = current,
                                onChangeMode = roleVm::startChange,
                                onSignOut = onSignOut,
                            )
                        }
                    }
                }
            }
        }
    }
}
