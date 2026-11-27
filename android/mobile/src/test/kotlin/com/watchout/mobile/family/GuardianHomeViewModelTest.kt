package com.watchout.mobile.family

import com.watchout.mobile.MainDispatcherExtension
import com.watchout.mobile.alert.AlertError
import com.watchout.mobile.alert.AlertSource
import com.watchout.mobile.alert.EmergencyAlert
import com.watchout.mobile.fakes.FakeAlertRepository
import com.watchout.mobile.fakes.FakeLinkRepository
import com.watchout.mobile.link.LinkError
import com.watchout.mobile.link.LinkedGuardian
import com.watchout.mobile.link.LinkedPatient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class GuardianHomeViewModelTest {

    private val links = FakeLinkRepository()
    private val alerts = FakeAlertRepository()

    // lazy: 가짜 메인 디스패처를 끼운 뒤에 만든다
    private val vm by lazy { GuardianHomeViewModel("guardian", "김딸", links, alerts) }

    private fun alert(id: String, patientUid: String, at: Long?) = EmergencyAlert(
        id = id,
        patientUid = patientUid,
        createdAtMillis = at,
        moving = false,
        source = AlertSource.TEST,
        photo = null,
        ackedByName = null,
    )

    /** 환자가 초대 코드를 만들고, 보호자가 그 코드를 입력해 연결한다 */
    private fun linkWith(patientUid: String, patientName: String, code: String) {
        links.invites[code] = FakeLinkRepository.StoredInvite(patientUid, patientName, "g1", expired = false)
        vm.openCodeDialog()
        vm.onCodeChange(code)
        vm.submitCode()
    }

    @Test
    fun `처음에는 연결된 가족도 알림도 없다`() {
        val s = vm.state.value
        assertFalse(s.isLoading)
        assertTrue(s.patients.isEmpty())
        assertTrue(s.alerts.isEmpty())
    }

    @Test
    fun `올바른 코드를 입력하면 연결되고, 환자 쪽에도 내가 연결된 보호자로 보인다`() {
        linkWith("patient", "홍길동", "123456")

        val s = vm.state.value
        assertEquals(listOf(LinkedPatient("patient", "홍길동")), s.patients)
        assertFalse(s.showCodeDialog)
        assertEquals("홍길동님과 연결됐어요.", s.message)
        assertTrue(links.invites.isEmpty()) // 한 번 쓴 코드는 사라진다

        val patientSide = runBlocking { links.observeLinkedGuardians("patient").first() }
        assertEquals(listOf(LinkedGuardian("guardian", "g1", "김딸")), patientSide)
    }

    @Test
    fun `코드 입력은 숫자만 6자리까지 받는다`() {
        vm.openCodeDialog()
        vm.onCodeChange("12a-34 5678")
        assertEquals("123456", vm.state.value.codeInput)
    }

    @Test
    fun `짧은 코드, 없는 코드, 만료된 코드, 내 코드는 이유를 알려준다`() {
        vm.openCodeDialog()
        vm.onCodeChange("123")
        vm.submitCode()
        assertEquals(LinkError.INVALID_CODE, vm.state.value.codeError)

        vm.onCodeChange("999999")
        vm.submitCode()
        assertEquals(LinkError.NOT_FOUND, vm.state.value.codeError)

        links.invites["111111"] = FakeLinkRepository.StoredInvite("patient", "홍길동", "g1", expired = true)
        vm.onCodeChange("111111")
        vm.submitCode()
        assertEquals(LinkError.EXPIRED, vm.state.value.codeError)

        links.invites["222222"] = FakeLinkRepository.StoredInvite("guardian", "나", "g1", expired = false)
        vm.onCodeChange("222222")
        vm.submitCode()
        assertEquals(LinkError.SELF, vm.state.value.codeError)

        assertTrue(vm.state.value.showCodeDialog) // 실패하면 창은 열어 둔다
    }

    @Test
    fun `여러 가족의 알림을 합쳐 새것부터 보여주고, 새 알림이 오면 바로 붙는다`() {
        linkWith("mom", "엄마", "100000")
        linkWith("dad", "아빠", "200000")
        alerts.put(alert("a1", "mom", at = 1_000))
        alerts.put(alert("b1", "dad", at = 2_000))

        assertEquals(listOf("아빠" to "b1", "엄마" to "a1"), vm.state.value.alerts.map { it.patientName to it.alert.id })
        assertEquals(2, vm.state.value.unacknowledgedCount)

        alerts.put(alert("a2", "mom", at = null)) // 방금 만들어 시각을 아직 모름 → 맨 위
        assertEquals("a2", vm.state.value.alerts.first().alert.id)
    }

    @Test
    fun `확인했어요를 누르면 내 이름으로 확인 표시된다`() {
        linkWith("mom", "엄마", "100000")
        alerts.put(alert("a1", "mom", at = 1_000))

        vm.acknowledge(vm.state.value.alerts.single())

        val a = vm.state.value.alerts.single().alert
        assertEquals("김딸", a.ackedByName)
        assertTrue(vm.state.value.acking.isEmpty())
        assertEquals(0, vm.state.value.unacknowledgedCount)
    }

    @Test
    fun `확인 표시가 실패하면 안내한다`() {
        linkWith("mom", "엄마", "100000")
        alerts.put(alert("a1", "mom", at = 1_000))
        alerts.failAck = true

        vm.acknowledge(vm.state.value.alerts.single())

        assertEquals(AlertError.ACK_FAILED.message, vm.state.value.errorMessage)
        assertNull(vm.state.value.alerts.single().alert.ackedByName)
    }

    @Test
    fun `환자가 연결을 끊어 알림을 못 읽으면 그 가족만 끊김으로 표시한다`() {
        alerts.unreadable += "dad"
        linkWith("mom", "엄마", "100000")
        linkWith("dad", "아빠", "200000")
        alerts.put(alert("a1", "mom", at = 1_000))

        val s = vm.state.value
        assertEquals(setOf("dad"), s.disconnected)
        assertEquals(listOf("a1"), s.alerts.map { it.alert.id }) // 엄마 알림은 그대로 보인다
    }

    @Test
    fun `연결을 끊으면 그 가족과 알림이 사라진다`() {
        linkWith("mom", "엄마", "100000")
        alerts.put(alert("a1", "mom", at = 1_000))

        vm.leave(LinkedPatient("mom", "엄마"))

        assertTrue(vm.state.value.patients.isEmpty())
        assertTrue(vm.state.value.alerts.isEmpty())
    }

    @Test
    fun `가족 목록을 못 불러오면 안내한다`() {
        links.failObservePatients = true
        assertEquals(LinkError.LOAD_FAILED.message, vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }
}
