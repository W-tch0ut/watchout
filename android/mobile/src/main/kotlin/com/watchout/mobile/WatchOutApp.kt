package com.watchout.mobile

import android.app.Application
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.watchout.mobile.alert.AlertRepository
import com.watchout.mobile.alert.FirestoreAlertRepository
import com.watchout.mobile.auth.AuthRepository
import com.watchout.mobile.auth.FirebaseAuthRepository
import com.watchout.mobile.auth.GoogleSignInHelper
import com.watchout.mobile.car.CarFinder
import com.watchout.mobile.car.CarSession
import com.watchout.mobile.car.ServiceCarSession
import com.watchout.mobile.car.DataLayerWatchRepository
import com.watchout.mobile.car.WatchRepository
import com.watchout.mobile.car.WifiCarFinder
import com.watchout.mobile.sms.PrefsSmsSettingsStore
import com.watchout.mobile.sms.SmsSettingsStore
import com.watchout.mobile.guardian.FirestoreGuardianRepository
import com.watchout.mobile.guardian.GuardianRepository
import com.watchout.mobile.link.FirestoreLinkRepository
import com.watchout.mobile.link.LinkRepository
import com.watchout.mobile.profile.FirestoreProfileRepository
import com.watchout.mobile.profile.ProfileRepository
import com.watchout.mobile.sms.AndroidSmsSender
import com.watchout.mobile.sms.SmsSender

/**
 * 앱 전체에서 함께 쓰는 부품 보관함.
 * 저장소를 한 번만 만들어 모든 화면이 같은 것을 쓰게 한다.
 * (by lazy: 처음 쓸 때 만든다)
 */
class AppContainer(context: Context) {
    val authRepository: AuthRepository by lazy { FirebaseAuthRepository(FirebaseAuth.getInstance()) }
    val guardianRepository: GuardianRepository by lazy {
        FirestoreGuardianRepository(FirebaseFirestore.getInstance())
    }
    val profileRepository: ProfileRepository by lazy {
        FirestoreProfileRepository(FirebaseFirestore.getInstance())
    }
    val linkRepository: LinkRepository by lazy { FirestoreLinkRepository(FirebaseFirestore.getInstance()) }
    val alertRepository: AlertRepository by lazy { FirestoreAlertRepository(FirebaseFirestore.getInstance()) }
    val smsSender: SmsSender by lazy { AndroidSmsSender(context) }
    val googleSignIn: GoogleSignInHelper by lazy { GoogleSignInHelper(context.applicationContext) }

    // 로봇카 찾기(Wi-Fi)·워치 연결(Data Layer)·응급 문자 설정 (#42 데모)
    val carFinder: CarFinder by lazy { WifiCarFinder(context.applicationContext) }
    val watchRepository: WatchRepository by lazy { DataLayerWatchRepository(context.applicationContext) }
    val carSession: CarSession by lazy { ServiceCarSession(context.applicationContext) }
    val smsSettingsStore: SmsSettingsStore by lazy { PrefsSmsSettingsStore(context.applicationContext) }
}

/** 앱이 켜질 때 가장 먼저 만들어지는 객체. AndroidManifest의 android:name으로 연결한다. */
class WatchOutApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
