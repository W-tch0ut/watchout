package com.watchout.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.watchout.gesture.ClockConverter
import com.watchout.gesture.SessionCsv
import com.watchout.gesture.WearingHand
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 삼성 제스처 수집용 연속 기록기.
 *
 * 화면이 켜져 있는 동안 센서 값을 **실제 시각**과 함께 파일에 계속 쓴다.
 * 나중에 PC에서 삼성 유니버설 제스처 로그의 인식 시각에 맞춰 잘라낸다.
 *
 * - 가속도·자이로: 200Hz 요청 (특별한 권한 없이 받을 수 있는 최대 속도. 실제 워치는 약 100Hz)
 * - 심박 원시 신호(PPG, 삼성 "HR Raw Sensor"): 신체 센서 권한이 있을 때만
 *
 * 삼성 제스처 인식 로그는 워치 앱이 읽을 수 없다(안드로이드 13+ 로그 접근 제한).
 * 그래서 수집이 끝난 뒤 PC가 워치 로그 버퍼를 꺼낸다. (tools/collect_samsung_gestures.ps1)
 *
 * 센서 값이 많아서(초당 400줄 이상) 화면 스레드가 아닌 별도 스레드에서 받고 쓴다.
 * 파일은 워치 안에만 저장한다. 센서 원본은 서버로 보내지 않는다. (CLAUDE.md 보안 규칙)
 */
class ContinuousRecorder(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 삼성 심박 원시 신호 센서. 기종에 따라 없을 수 있다. */
    val ppgSensor: Sensor? = sensorManager.getSensorList(Sensor.TYPE_ALL)
        .firstOrNull { it.stringType == PPG_STRING_TYPE }

    val directory: File = (context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME))
        .apply { mkdirs() }

    /** [acc, gyr, ppg] 받은 개수. 화면에서 기록이 잘 되는지 보여주는 용도 */
    val counts = AtomicIntegerArray(3)

    private var thread: HandlerThread? = null

    // 화면 스레드(start/stop)와 기록 스레드(onSensorChanged)가 같이 보므로 @Volatile
    @Volatile private var writer: BufferedWriter? = null

    @Volatile private var clock: ClockConverter? = null

    /** onSensorChanged 전용 (기록 스레드에서만 쓴다) */
    private val lineBuffer = StringBuilder(128)

    var currentFile: File? = null
        private set

    /**
     * 기록을 시작한다.
     * @param withPpg 심박 원시 신호도 기록할지 (권한이 있을 때만 true로 부른다)
     * @return 기록 중인 파일. 가속도·자이로가 없으면 null
     */
    fun start(hand: WearingHand, withPpg: Boolean): File? {
        if (accelerometer == null || gyroscope == null) return null
        stop()
        for (i in 0 until counts.length()) counts.set(i, 0)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(directory, "session__$stamp.csv")
        val ppg = ppgSensor.takeIf { withPpg }
        // 1초에 200줄 넘게 쓰므로 버퍼를 크게 잡아 실제 쓰기 횟수를 줄인다.
        val out = file.bufferedWriter(bufferSize = WRITE_BUFFER_CHARS)
        out.write(
            SessionCsv.infoLine(
                linkedMapOf(
                    "hand" to hand.name,
                    "imu_period_us" to IMU_PERIOD_US.toString(),
                    "ppg" to (ppg?.name ?: "none"),
                ),
            ),
        )
        out.newLine()
        out.write(SessionCsv.HEADER)
        out.newLine()

        clock = ClockConverter(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        writer = out
        currentFile = file
        val handlerThread = HandlerThread("session-recorder").apply { start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        sensorManager.registerListener(this, accelerometer, IMU_PERIOD_US, handler)
        sensorManager.registerListener(this, gyroscope, IMU_PERIOD_US, handler)
        if (ppg != null) sensorManager.registerListener(this, ppg, SensorManager.SENSOR_DELAY_FASTEST, handler)
        return file
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        val t = thread ?: return
        // 닫을 파일을 지금 붙잡아 둔다. 곧바로 start()가 불려 새 파일이 생겨도 그 파일은 건드리지 않는다.
        val closing = writer
        writer = null
        clock = null
        thread = null
        // 기록 스레드에 이미 쌓인 값을 다 처리한 뒤 파일을 닫고 스레드를 끝낸다.
        Handler(t.looper).post {
            runCatching { closing?.flush(); closing?.close() }
            t.quitSafely()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val out = writer ?: return
        val c = clock ?: return
        val (name, index) = when (event.sensor) {
            accelerometer -> SessionCsv.ACC to 0
            gyroscope -> SessionCsv.GYRO to 1
            else -> SessionCsv.PPG to 2
        }
        // 기록 스레드 하나에서만 불리므로 StringBuilder 하나를 재사용한다. (쓰레기·GC 감소)
        lineBuffer.setLength(0)
        SessionCsv.appendLine(lineBuffer, c.toEpochMs(event.timestamp), name, event.values).append('\n')
        out.append(lineBuffer)
        counts.incrementAndGet(index)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val DIR_NAME = "sessions"
        const val IMU_PERIOD_US = 5_000 // 200Hz
        const val PPG_STRING_TYPE = "com.samsung.sensor.hr_raw"
        private const val WRITE_BUFFER_CHARS = 64 * 1024
    }
}
