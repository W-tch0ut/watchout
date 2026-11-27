package com.watchout.mobile.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Firebase Authentication으로 [AuthRepository] 약속을 지키는 실제 구현.
 *
 * 로그인 토큰 보관: Firebase SDK가 토큰을 앱 전용 저장 공간(다른 앱이 읽을 수 없는 곳)에
 * 보관하고, 1시간마다 알아서 새로 받는다. 우리 코드는 토큰을 직접 꺼내 저장하지 않는다.
 * 토큰을 직접 들고 다니면 로그에 찍히거나 엉뚱한 곳에 남을 위험만 커지기 때문이다.
 */
class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {

    private val _currentUser = MutableStateFlow(auth.currentUser?.toAppUser())
    override val currentUser: StateFlow<AppUser?> = _currentUser.asStateFlow()

    init {
        // 로그인·로그아웃·토큰 만료 등으로 상태가 바뀔 때마다 Firebase가 알려준다.
        auth.addAuthStateListener { _currentUser.value = it.currentUser?.toAppUser() }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<AppUser> = runAuth {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await().user
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AppUser> = runAuth {
        auth.signInWithEmailAndPassword(email, password).await().user
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AppUser> = runAuth {
        auth.createUserWithEmailAndPassword(email, password).await().user
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = try {
        auth.sendPasswordResetEmail(email).await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(AuthException(toAuthError(e), e))
    }

    override fun signOut() = auth.signOut()

    /** Firebase 호출을 실행하고, 성공이면 AppUser, 실패면 AuthException으로 감싼다. */
    private suspend fun runAuth(block: suspend () -> FirebaseUser?): Result<AppUser> = try {
        val user = block() ?: throw AuthException(AuthError.UNKNOWN)
        Result.success(user.toAppUser())
    } catch (e: CancellationException) {
        throw e // 화면을 떠나서 작업이 취소된 것은 오류가 아니다. 그대로 올려 보낸다.
    } catch (e: AuthException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AuthException(toAuthError(e), e))
    }

    private fun toAuthError(e: Exception): AuthError = when (e) {
        is FirebaseNetworkException -> AuthError.NETWORK
        is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
        is FirebaseAuthException -> AuthErrorMapper.fromFirebaseCode(e.errorCode)
        else -> AuthError.UNKNOWN
    }

    private fun FirebaseUser.toAppUser() = AppUser(uid = uid, email = email, displayName = displayName)
}
