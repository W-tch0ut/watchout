package com.watchout.mobile.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.watchout.mobile.R

/**
 * 구글 계정 선택 창을 띄우고, 고른 계정의 "ID 토큰"을 받아 온다.
 *
 * Android의 Credential Manager(계정 관리자)를 쓴다. 예전 GoogleSignInClient 방식은
 * 더 이상 권장되지 않는다. 받은 ID 토큰은 [AuthRepository.signInWithGoogle]에 넘긴다.
 */
class GoogleSignInHelper(appContext: Context) {

    // google-services.json에서 자동으로 만들어지는 "웹 클라이언트 ID".
    // Firebase가 "이 토큰은 우리 프로젝트용이 맞다"고 확인할 때 쓴다.
    private val serverClientId: String = appContext.getString(R.string.default_web_client_id)

    /**
     * @param activityContext 계정 선택 창을 띄울 화면(Activity). 앱 Context로는 창을 띄울 수 없다.
     * @throws AuthException 취소, 계정 없음, 설정 파일 없음 등
     */
    suspend fun requestIdToken(activityContext: Context): String {
        if (serverClientId.isBlank()) throw AuthException(AuthError.NOT_CONFIGURED)

        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw AuthException(AuthError.CANCELLED, e)
        } catch (e: NoCredentialException) {
            throw AuthException(AuthError.NO_GOOGLE_ACCOUNT, e)
        } catch (e: GetCredentialException) {
            throw AuthException(AuthError.UNKNOWN, e)
        }

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw AuthException(AuthError.UNKNOWN)
    }

    /** 로그아웃할 때 부른다. 다음 로그인에서 계정을 다시 고를 수 있게 기억을 지운다. */
    suspend fun clearState(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: ClearCredentialException) {
            // 지울 기억이 없거나 실패해도 Firebase 로그아웃은 계속 진행한다.
        }
    }
}
