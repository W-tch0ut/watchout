package com.watchout.mobile.car

import com.watchout.gesture.CarInfo
import com.watchout.gesture.LinkState
import com.watchout.mobile.MainDispatcherExtension
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/** 로봇카 연결 대신 쓰는 가짜: 연결 요청을 기록한다 */
private class FakeSession : CarSession {
    override val link = MutableStateFlow(CarLinkState())
    override val events = MutableStateFlow<List<String>>(emptyList())
    val connected = mutableListOf<CarInfo>()
    var disconnects = 0
    override fun connect(car: CarInfo) {
        connected += car
        link.value = CarLinkState(LinkState.CONNECTING, car, "연결 중…")
    }
    override fun disconnect() {
        disconnects++
        link.value = CarLinkState()
    }
}

@ExtendWith(MainDispatcherExtension::class)
class CarViewModelTest {

    private val car = CarInfo("demo-car", "데모 로봇카(PC)", "192.168.0.34", 1883)
    private val session = FakeSession()
    private val watch = FakeWatch()
    private var found: List<CarInfo>? = listOf(car)
    private val vm by lazy { CarViewModel({ found ?: error("wifi off") }, session, watch) }

    @Test
    fun `찾기를 누르면 로봇카 목록과 안내를 보여준다`() {
        vm.scan()

        assertEquals(listOf(car), vm.state.value.cars)
        assertFalse(vm.state.value.scanning)
        assertTrue(vm.state.value.scanMessage!!.startsWith("1대"))
    }

    @Test
    fun `못 찾거나 Wi-Fi 오류면 이유를 알려준다`() {
        found = emptyList()
        vm.scan()
        assertTrue(vm.state.value.scanMessage!!.contains("켜져 있고"))

        found = null
        vm.scan()
        assertTrue(vm.state.value.scanMessage!!.contains("Wi-Fi"))
    }

    @Test
    fun `로봇카를 고르면 폰이 직접 연결하고 상태를 보여준다`() {
        vm.connect(car)

        assertEquals(listOf(car), session.connected)
        assertEquals(LinkState.CONNECTING, vm.state.value.link.state)
        assertTrue(watch.sent.isEmpty()) // 워치에 연결하라고 보내지 않는다

        session.link.value = CarLinkState(LinkState.CONNECTED, car, "대기 중")
        assertEquals("대기 중", vm.state.value.link.message)
    }

    @Test
    fun `끊기를 누르면 연결을 끊는다`() {
        vm.connect(car)
        vm.disconnect()

        assertEquals(1, session.disconnects)
        assertEquals(LinkState.OFF, vm.state.value.link.state)
    }

    @Test
    fun `워치 연결 여부와 최근 일을 보여준다`() {
        watch.connected = false
        vm.checkWatch()
        assertEquals(false, vm.state.value.watchConnected)

        session.events.value = listOf("✋ 부르기 → 이리 와")
        assertEquals(listOf("✋ 부르기 → 이리 와"), vm.state.value.events)
    }
}
