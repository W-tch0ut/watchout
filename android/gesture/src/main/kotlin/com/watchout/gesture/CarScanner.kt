package com.watchout.gesture

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 같은 Wi-Fi에 있는 로봇카를 찾는다 (폰·워치 공용, 순수 Java 네트워크만 쓴다).
 *
 * 로봇카(데모 PC)는 1초마다 UDP 방송으로 자기 이름과 주소를 알린다 ([CarProtocol.DISCOVERY_PORT]).
 * [durationMs] 동안 귀를 기울여서 들린 차를 목록으로 돌려준다. 그래서 IP를 입력할 필요가 없다.
 *
 * 블로킹 함수라 화면 스레드에서 부르면 안 된다.
 * 안드로이드에서는 절전 중에 방송 패킷이 버려질 수 있어서, 부르는 쪽이 MulticastLock을 잡아야 한다.
 */
object CarScanner {
    fun scan(durationMs: Long = 4000, port: Int = CarProtocol.DISCOVERY_PORT): List<CarInfo> {
        val found = LinkedHashMap<String, CarInfo>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(port))
            socket.soTimeout = 300
            val buf = ByteArray(2048)
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                CarProtocol.parseDiscovery(text)?.let { found[it.id] = it } // 같은 차가 여러 번 들려도 한 번만
            }
        }
        return found.values.toList()
    }
}
