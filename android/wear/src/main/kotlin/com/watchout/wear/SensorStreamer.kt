package com.watchout.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.watchout.gesture.SampleRateLimiter
import com.watchout.gesture.SensorSample

/**
 * 워치의 가속도·자이로를 50Hz로 읽어서 [SensorSample]로 묶어 준다.
 *
 * 가속도와 자이로는 따로따로 도착한다. 그래서 가장 최근 자이로 값을 기억해 두었다가
 * 가속도가 들어올 때 같이 묶는다. 가속도 이벤트가 50Hz보다 자주 오면(기기마다 다름)
 * 18ms보다 가까운 샘플은 건너뛰어 대략 50Hz로 맞춘다.
 */
class SensorStreamer(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val latestGyro = FloatArray(3)
    private val rateLimiter = SampleRateLimiter(MIN_INTERVAL_MS)
    private var listener: ((SensorSample) -> Unit)? = null

    val isAvailable: Boolean get() = accelerometer != null && gyroscope != null

    /** @return 센서가 없으면 false */
    fun start(onSample: (SensorSample) -> Unit): Boolean {
        if (!isAvailable) return false
        stop()
        listener = onSample
        rateLimiter.reset()
        sensorManager.registerListener(this, gyroscope, SAMPLING_PERIOD_US)
        sensorManager.registerListener(this, accelerometer, SAMPLING_PERIOD_US)
        return true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> event.values.copyInto(latestGyro, endIndex = 3)
            Sensor.TYPE_ACCELEROMETER -> {
                val timestampMs = event.timestamp / 1_000_000 // ns → ms
                if (!rateLimiter.accept(timestampMs)) return
                listener?.invoke(
                    SensorSample(
                        timestampMs = timestampMs,
                        ax = event.values[0], ay = event.values[1], az = event.values[2],
                        gx = latestGyro[0], gy = latestGyro[1], gz = latestGyro[2],
                    ),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val SAMPLING_PERIOD_US = 20_000 // 50Hz
        private const val MIN_INTERVAL_MS = 18L
    }
}
