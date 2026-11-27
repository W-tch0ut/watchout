package com.watchout.mobile.sms

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 응급 문자에 들어갈 내 정보. 지금은 폰 안에만 저장한다 (나중에 Firestore로 옮겨 로봇카·서버가 읽게 한다).
 *
 * @property name 이름 (문자의 "대상")
 * @property address 주소 (119·보호자가 찾아올 곳)
 * @property note 참고 사항 (복용 약, 지병 등. 비워도 된다)
 */
data class SmsSettings(
    val name: String = "",
    val address: String = "",
    val note: String = "",
)

/**
 * 응급 문자 본문 만들기. 데모 로봇카(demo/alert.py의 compose_message)와 같은 모양이다.
 * 한쪽을 바꾸면 다른 쪽도 맞춘다.
 */
object SmsTemplate {
    const val MAX_NAME = 20
    const val MAX_ADDRESS = 100
    const val MAX_NOTE = 100
    private const val MAX_TEXT = 1000 // 문자 API 본문 제한
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun compose(
        s: SmsSettings,
        time: LocalDateTime,
        impactG: Float,
        confirmedBy: String,
        moving: Boolean?,
    ): String {
        val lines = mutableListOf(
            "[WatchOut] 낙상사고가 일어났습니다.",
            "- 대상: ${s.name.ifBlank { "(이름 없음)" }}",
            "- 시각: ${time.format(TIME)}",
            "- 장소: ${s.address.ifBlank { "(주소 없음)" }}",
            "- 상황: 워치 충격 ${String.format(Locale.ROOT, "%.1f", impactG)}g, 10초간 응답 없음, $confirmedBy",
        )
        if (moving != null) lines += "- 움직임: ${if (moving) "있음" else "없음"}"
        if (s.note.isNotBlank()) lines += "- 참고: ${s.note}"
        lines += "현장 사진을 함께 보냅니다."
        return lines.joinToString("\n").take(MAX_TEXT)
    }

    /** 화면 미리보기용 예시 문자 */
    fun preview(s: SmsSettings, now: LocalDateTime): String =
        compose(s, now, impactG = 3.2f, confirmedBy = "로봇카 카메라로 쓰러짐 확인", moving = false)
}

/** 문자 설정 저장소의 "약속". 테스트에서는 메모리에 저장하는 가짜를 쓴다 */
interface SmsSettingsStore {
    fun load(): SmsSettings
    fun save(settings: SmsSettings)
}

/** 폰 안(앱 전용 저장소)에 저장한다. 앱 백업에서는 빠진다(data_extraction_rules.xml) */
class PrefsSmsSettingsStore(context: Context) : SmsSettingsStore {
    private val prefs = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)

    override fun load() = SmsSettings(
        name = prefs.getString("name", "").orEmpty(),
        address = prefs.getString("address", "").orEmpty(),
        note = prefs.getString("note", "").orEmpty(),
    )

    override fun save(settings: SmsSettings) = prefs.edit {
        putString("name", settings.name)
        putString("address", settings.address)
        putString("note", settings.note)
    }
}
