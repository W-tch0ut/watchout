package com.watchout.mobile.sms

import com.watchout.mobile.alert.AlertFormat
import com.watchout.mobile.link.InviteCode

/**
 * 보호자에게 보내는 문자 문구 (#50). 순수 함수라 PC에서 테스트한다.
 *
 * - 의료 진단(병명·원인)은 쓰지 않는다. "쓰러짐"과 "움직임 있음/없음"만 알린다.
 * - 사진은 문자로 보내지 않는다. 앱(보호자 모드)에서만 볼 수 있다.
 */
object SmsMessages {
    private val MOBILE = Regex("^01[016789][0-9]{7,8}$")

    /** 문자를 보내도 되는 번호인가: 한국 휴대폰 번호(숫자만)만 허용. 119·112 같은 번호는 false */
    fun isMobileNumber(phone: String): Boolean = MOBILE.matches(phone)

    fun invite(patientName: String, code: String): String =
        "[WatchOut] ${patientName}님이 보호자로 초대했어요. " +
            "WatchOut 앱 보호자 모드에서 코드 $code 입력 (${InviteCode.VALID_MINUTES}분 유효)"

    /**
     * @param isTest 테스트 버튼으로 보낸 알림이면 맨 앞에 [테스트]를 붙여 놀라지 않게 한다
     */
    fun emergency(patientName: String, moving: Boolean, timeMillis: Long?, isTest: Boolean): String {
        val head = if (isTest) "[WatchOut 테스트]" else "[WatchOut 응급]"
        return "$head ${patientName}님 쓰러짐 확인 (${AlertFormat.moving(moving)}) " +
            "${AlertFormat.time(timeMillis)}. 사진은 WatchOut 앱에서 확인하세요."
    }
}
