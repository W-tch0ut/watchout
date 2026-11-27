package com.watchout.mobile.patient

import com.watchout.mobile.MainDispatcherExtension
import com.watchout.mobile.alert.AlertError
import com.watchout.mobile.alert.AlertPhoto
import com.watchout.mobile.alert.AlertSource
import com.watchout.mobile.fakes.FakeAlertRepository
import com.watchout.mobile.fakes.FakeLinkRepository
import com.watchout.mobile.fakes.FakeSmsSender
import com.watchout.mobile.guardian.Guardian
import com.watchout.mobile.link.LinkError
import com.watchout.mobile.link.LinkedGuardian
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class PatientViewModelTest {

    private val links = FakeLinkRepository()
    private val alerts = FakeAlertRepository()
    private val sms = FakeSmsSender()
    private var photo: AlertPhoto? = AlertPhoto(byteArrayOf(1, 2, 3))

    // lazy: 가짜 메인 디스패처를 끼운 뒤에 만든다
    private val vm by lazy {
        PatientViewModel("patient", "홍길동", links, alerts, sms, testPhoto = { photo }, clock = { 0L })
    }

    private val daughter = Guardian("g1", "김딸", "딸", "01000000001")
    private val son = Guardian("g2", "김아들", "아들", "01000000002")

    @Test
    fun `초대하면 코드를 만들고 그 보호자 번호로 코드를 문자로 보낸다`() {
        vm.sendInvite(daughter)

        assertEquals(FakeLinkRepository.StoredInvite("patient", "홍길동", "g1", expired = false), links.invites["123456"])
        val (phone, text) = sms.sent.single()
        assertEquals("01000000001", phone)
        assertTrue(text.contains("123456"))
        assertEquals(InviteResult("김딸", "123456", smsSent = true), vm.state.value.invite)
        assertNull(vm.state.value.invitingId)
    }

    @Test
    fun `문자 권한이 없으면 문자 없이 코드만 보여준다`() {
        sms.permitted = false
        vm.sendInvite(daughter)

        assertTrue(sms.sent.isEmpty())
        assertEquals(InviteResult("김딸", "123456", smsSent = false), vm.state.value.invite)
    }

    @Test
    fun `코드를 못 만들면 안내하고 문자도 보내지 않는다`() {
        links.failCreate = true
        vm.sendInvite(daughter)

        assertEquals(LinkError.INVITE_FAILED.message, vm.state.value.errorMessage)
        assertTrue(sms.sent.isEmpty())
        assertNull(vm.state.value.invite)
    }

    @Test
    fun `테스트 응급 알림은 알림 저장 + 모든 보호자에게 문자`() {
        vm.sendTestEmergency(listOf(daughter, son), moving = false)

        val (uid, created) = alerts.created.single()
        assertEquals("patient", uid)
        assertEquals(AlertSource.TEST, created.source)
        assertFalse(created.moving)
        assertEquals(photo, created.photo)

        assertEquals(listOf("01000000001", "01000000002"), sms.sent.map { it.first })
        assertTrue(sms.sent.all { it.second.startsWith("[WatchOut 테스트]") })
        assertEquals(EmergencyResult(alertSaved = true, smsSent = 2, smsFailed = 0, noSmsPermission = false), vm.state.value.emergency)
        // 119 번호로는 아무것도 보내지 않는다
        assertTrue(sms.sent.none { it.first == "119" })
    }

    @Test
    fun `알림 저장이 실패해도 문자는 보낸다`() {
        alerts.failCreate = true
        vm.sendTestEmergency(listOf(daughter), moving = true)

        assertEquals(1, sms.sent.size)
        assertEquals(EmergencyResult(alertSaved = false, smsSent = 1, smsFailed = 0, noSmsPermission = false), vm.state.value.emergency)
        assertEquals(AlertError.SEND_FAILED.message, vm.state.value.errorMessage)
    }

    @Test
    fun `한 명에게 문자가 실패해도 나머지에게는 보낸다`() {
        sms.failingPhones += "01000000001"
        vm.sendTestEmergency(listOf(daughter, son), moving = false)

        assertEquals(listOf("01000000002"), sms.sent.map { it.first })
        assertEquals(1, vm.state.value.emergency?.smsFailed)
    }

    @Test
    fun `사진이 너무 크면 사진 없이 알림을 보낸다`() {
        photo = AlertPhoto(ByteArray(AlertPhoto.MAX_BYTES + 1))
        vm.sendTestEmergency(listOf(daughter), moving = false)

        assertNull(alerts.created.single().second.photo)
    }

    @Test
    fun `연결된 보호자를 연락처별로 알고, 연결을 끊을 수 있다`() {
        links.putLink("patient", LinkedGuardian("guardianUid", "g1", "김딸"))

        assertTrue(vm.state.value.isLinked("g1"))
        assertFalse(vm.state.value.isLinked("g2"))

        vm.unlink("g1")
        assertFalse(vm.state.value.isLinked("g1"))
    }
}
