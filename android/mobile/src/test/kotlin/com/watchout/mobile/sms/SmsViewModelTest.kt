package com.watchout.mobile.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

private class MemoryStore(var value: SmsSettings = SmsSettings()) : SmsSettingsStore {
    override fun load() = value
    override fun save(settings: SmsSettings) { value = settings }
}

class SmsTemplateTest {

    private val time = LocalDateTime.of(2026, 11, 27, 13, 0, 57)

    @Test
    fun `데모 로봇카(demo-alert-py)와 같은 모양의 문자를 만든다`() {
        val text = SmsTemplate.compose(
            SmsSettings("홍길동", "서울시 예시구 예시동", "고혈압 약 복용"),
            time, impactG = 3.04f, confirmedBy = "로봇카 카메라로 쓰러짐 확인", moving = false,
        )

        assertEquals(
            """
            [WatchOut] 낙상사고가 일어났습니다.
            - 대상: 홍길동
            - 시각: 2026-11-27 13:00:57
            - 장소: 서울시 예시구 예시동
            - 상황: 워치 충격 3.0g, 10초간 응답 없음, 로봇카 카메라로 쓰러짐 확인
            - 움직임: 없음
            - 참고: 고혈압 약 복용
            현장 사진을 함께 보냅니다.
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `참고가 비면 그 줄을 빼고, 이름·주소가 비면 안내 문구를 넣는다`() {
        val text = SmsTemplate.compose(SmsSettings(), time, 3f, "확인", moving = null)

        assertTrue(text.contains("- 대상: (이름 없음)"))
        assertTrue(text.contains("- 장소: (주소 없음)"))
        assertFalse(text.contains("참고"))
        assertFalse(text.contains("움직임"))
    }
}

class SmsViewModelTest {

    private val store = MemoryStore(SmsSettings("홍길동", "예시 주소", ""))
    private val vm = SmsViewModel(store) { LocalDateTime.of(2026, 1, 1, 9, 0, 0) }

    @Test
    fun `저장된 값을 불러오고 미리보기를 만든다`() {
        assertEquals("홍길동", vm.state.value.settings.name)
        assertTrue(vm.state.value.preview.contains("- 대상: 홍길동"))
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `고치면 미리보기가 바뀌고 저장 버튼이 켜진다`() {
        vm.onAddress("새 주소")

        assertTrue(vm.state.value.dirty)
        assertTrue(vm.state.value.preview.contains("- 장소: 새 주소"))
        assertEquals("예시 주소", store.value.address) // 아직 저장 안 함
    }

    @Test
    fun `저장하면 앞뒤 공백을 지워 저장한다`() {
        vm.onNote("  당뇨 ")
        vm.save()

        assertEquals("당뇨", store.value.note)
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `원래 값으로 되돌리면 저장할 것이 없다`() {
        vm.onName("김철수")
        vm.onName("홍길동")

        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `너무 길게 입력하면 자른다`() {
        vm.onName("가".repeat(50))

        assertEquals(SmsTemplate.MAX_NAME, vm.state.value.settings.name.length)
    }
}
