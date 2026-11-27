package com.watchout.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.watchout.mobile.auth.LoginViewModel
import com.watchout.mobile.guardian.GuardianViewModel
import com.watchout.mobile.ui.GuardianScreen
import com.watchout.mobile.ui.LoginScreen
import kotlinx.coroutines.launch

/**
 * 폰 앱의 유일한 화면(Activity).
 *
 * 로그인 상태를 보고 어떤 화면을 그릴지 정한다.
 *   로그아웃 상태 → LoginScreen
 *   로그인 상태   → GuardianScreen (보호자 연락처)
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

                if (current == null) {
                    val vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(container.authRepository))
                    LoginScreen(
                        vm = vm,
                        onGoogleClick = {
                            vm.signInWithGoogle { container.googleSignIn.requestIdToken(this@MainActivity) }
                        },
                    )
                } else {
                    // key = uid: 다른 계정으로 다시 로그인하면 새 ViewModel을 만든다 (이전 사람 연락처가 남지 않게)
                    val vm: GuardianViewModel = viewModel(
                        key = current.uid,
                        factory = GuardianViewModel.factory(current.uid, container.guardianRepository),
                    )
                    GuardianScreen(
                        vm = vm,
                        user = current,
                        onSignOut = {
                            scope.launch {
                                container.googleSignIn.clearState(this@MainActivity)
                                container.authRepository.signOut()
                            }
                        },
                    )
                }
            }
        }
    }
}
