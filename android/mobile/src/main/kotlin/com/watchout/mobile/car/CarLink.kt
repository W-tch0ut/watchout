package com.watchout.mobile.car

import com.watchout.gesture.CarInfo
import com.watchout.gesture.CarProtocol
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors

/**
 * 로봇카와의 MQTT 연결.
 *
 * - 명령·낙상 의심을 보내고([send]), 차의 상태·낙상 확인 결과를 받는다([Listener]).
 * - 끊기면 Paho가 스스로 다시 연결한다. 다시 붙으면 구독도 다시 한다.
 * - 네트워크 작업은 전용 스레드 하나에서 한다. 화면 스레드를 막지 않는다.
 *
 * 메시지는 QoS 1(최소 한 번 전달)로 보낸다. "멈춰" 같은 명령이 빠지면 안 되기 때문이다.
 */
class CarLink(private val car: CarInfo, private val listener: Listener) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onStatus(json: String)
        fun onFallResult(json: String)
    }

    private val io = Executors.newSingleThreadExecutor { Thread(it, "car-link") }
    private var client: MqttClient? = null

    fun connect() = io.execute {
        try {
            val c = MqttClient(car.brokerUri, "phone-" + System.currentTimeMillis(), MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    c.subscribe(CarProtocol.statusTopic(car.id), 1)
                    c.subscribe(CarProtocol.fallResultTopic(car.id), 1)
                    listener.onConnected()
                }

                override fun connectionLost(cause: Throwable?) =
                    listener.onDisconnected(cause?.message ?: "연결 끊김")

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val text = String(message.payload, Charsets.UTF_8)
                    when (topic) {
                        CarProtocol.statusTopic(car.id) -> listener.onStatus(text)
                        CarProtocol.fallResultTopic(car.id) -> listener.onFallResult(text)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            c.connect(
                MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 8
                    keepAliveInterval = 15
                },
            )
            client = c
        } catch (e: Exception) {
            listener.onDisconnected("연결 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun send(topic: String, json: String) = io.execute {
        val c = client
        if (c == null || !c.isConnected) return@execute
        runCatching { c.publish(topic, json.toByteArray(Charsets.UTF_8), 1, false) }
    }

    fun close() {
        io.execute {
            runCatching { client?.disconnect(1000) }
            runCatching { client?.close() }
            client = null
        }
        io.shutdown()
    }
}
