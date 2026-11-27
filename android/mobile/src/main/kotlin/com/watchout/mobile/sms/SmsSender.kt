package com.watchout.mobile.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * 문자 보내기의 "약속(인터페이스)" (#50).
 * 실제 구현은 [AndroidSmsSender], 테스트에서는 보낸 내용을 기억만 하는 가짜를 쓴다.
 *
 * ⚠️ 이 앱은 보호자 번호(환자가 등록한 번호)로만 문자를 보낸다. 119로는 절대 보내지 않는다.
 */
interface SmsSender {
    /** 문자 권한(SEND_SMS)이 있나 */
    fun canSend(): Boolean

    /** [phone](숫자만) 번호로 [text]를 보낸다. 실패하면 예외. */
    fun send(phone: String, text: String)
}

/**
 * 폰의 문자 기능(SmsManager)으로 진짜 문자를 보낸다. 폰 요금제 문자를 쓴다.
 * - 유심이 없는 폰이나 에뮬레이터에서는 보내는 척만 하거나 실패한다.
 * - 번호와 내용은 로그(Logcat)에 찍지 않는다.
 */
class AndroidSmsSender(context: Context) : SmsSender {
    private val appContext = context.applicationContext

    override fun canSend(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    override fun send(phone: String, text: String) {
        // 119 같은 긴급 번호는 문자 대상이 될 수 없게 한 번 더 막는다 (휴대폰 번호만)
        require(SmsMessages.isMobileNumber(phone)) { "휴대폰 번호가 아니에요" }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("SEND_SMS 권한이 없어요")
        }
        val manager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        // 한글 문자는 70자가 넘으면 여러 개로 나눠 보낸다 (받는 폰에서는 하나로 합쳐 보인다)
        val parts = manager.divideMessage(text)
        if (parts.size == 1) {
            manager.sendTextMessage(phone, null, text, null, null)
        } else {
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
        }
    }
}
