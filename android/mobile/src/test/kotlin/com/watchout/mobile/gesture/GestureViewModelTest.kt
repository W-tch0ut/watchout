package com.watchout.mobile.gesture

import com.watchout.gesture.CarCommand
import com.watchout.gesture.GestureSummary
import com.watchout.gesture.RegAction
import com.watchout.gesture.RegPhase
import com.watchout.gesture.RegState
import com.watchout.gesture.WatchLink
import com.watchout.mobile.MainDispatcherExtension
import com.watchout.mobile.car.FakeWatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class GestureViewModelTest {

    private val watch = FakeWatch()
    private val vm by lazy { GestureViewModel(watch) }

    private fun lastAction() = WatchLink.parseRegRequest(watch.sent.last().second)?.action

    @Test
    fun `워치가 올린 제스처 목록을 보여준다`() {
        val list = listOf(GestureSummary("g1", "부르기", CarCommand.APPROACH, 5))
        vm.state.value // ViewModel 만들기 (만들 때 목록을 한 번 다시 읽는다)
        watch.gestures.value = list

        assertEquals(list, vm.state.value.gestures)
        assertEquals(1, watch.refreshCount)
    }

    @Test
    fun `등록을 시작하면 워치에 등록 화면을 띄우라고 보낸다`() {
        vm.start("부르기", CarCommand.APPROACH)

        assertEquals(WatchLink.MSG_REG, watch.sent.single().first)
        val req = WatchLink.parseRegRequest(watch.sent.single().second)!!
        assertEquals(RegAction.START, req.action)
        assertEquals(CarCommand.APPROACH, req.command)
        assertNotNull(vm.state.value.registration)
    }

    @Test
    fun `시연과 저장은 워치 상태를 보고 보낸다`() {
        vm.start("부르기", CarCommand.APPROACH)
        watch.regState.value = RegState(RegPhase.READY, "부르기")
        vm.record()
        assertEquals(RegAction.RECORD, lastAction())

        // 녹화 중에는 또 누르지 않는다
        watch.regState.value = RegState(RegPhase.RECORDING, "부르기")
        vm.record()
        assertEquals(2, watch.sent.size)

        // 2번만 시연했으면 저장하지 않는다
        watch.regState.value = RegState(RegPhase.ADDED, "부르기", count = 2)
        vm.save()
        assertEquals(2, watch.sent.size)

        watch.regState.value = RegState(RegPhase.ADDED, "부르기", count = 3)
        vm.save()
        assertEquals(RegAction.SAVE, lastAction())
    }

    @Test
    fun `저장되면 목록을 다시 읽고, 닫아도 취소를 보내지 않는다`() {
        vm.start("부르기", CarCommand.APPROACH)
        watch.regState.value = RegState(RegPhase.SAVED, "부르기", count = 5)
        assertEquals(2, watch.refreshCount) // 처음 + 저장 뒤

        vm.close()
        assertNull(vm.state.value.registration)
        assertEquals(1, watch.sent.size) // start만
    }

    @Test
    fun `저장 전에 닫으면 워치에 취소를 보낸다`() {
        vm.start("부르기", CarCommand.APPROACH)
        vm.close()

        assertEquals(RegAction.CANCEL, lastAction())
        assertNull(vm.state.value.registration)
    }

    @Test
    fun `삭제는 워치에 보낸다`() {
        vm.delete("g1")

        assertEquals(WatchLink.regDelete("g1"), watch.sent.single().second)
    }

    @Test
    fun `워치가 없으면 안내한다`() {
        watch.connected = false
        vm.start("부르기", CarCommand.APPROACH)

        assertTrue(vm.state.value.message!!.contains("워치가 연결돼 있지 않아요"))
    }
}
