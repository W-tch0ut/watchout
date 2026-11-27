package com.watchout.wear

import android.content.Context
import com.watchout.gesture.Gesture
import com.watchout.gesture.GestureRecording
import java.io.File

/**
 * 녹화한 제스처를 워치 안에 CSV 파일로 저장한다.
 *
 * 위치: `/sdcard/Android/data/com.watchout.wear/files/gestures/`
 * PC로 가져오기: `adb pull /sdcard/Android/data/com.watchout.wear/files/gestures data/gestures`
 *
 * 센서 원본은 민감 정보라서 서버로 보내지 않고 워치 안에만 둔다. (CLAUDE.md 보안 규칙)
 */
class RecordingStore(context: Context) {

    val directory: File = (context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME))
        .apply { mkdirs() }

    fun save(recording: GestureRecording): File =
        File(directory, recording.fileName()).apply { writeText(recording.toCsv()) }

    /** 라벨별 저장된 녹화 개수 */
    fun counts(): Map<Gesture, Int> = files()
        .mapNotNull { GestureRecording.labelFromFileName(it.name) }
        .groupingBy { it }
        .eachCount()

    /** 잘못 녹화했을 때 그 라벨의 가장 최근 파일을 지운다. @return 지웠으면 true */
    fun deleteLatest(label: Gesture): Boolean = files()
        .filter { GestureRecording.labelFromFileName(it.name) == label }
        .maxByOrNull { it.name } // 파일 이름에 시각이 들어 있어서 이름순 = 시간순
        ?.delete() ?: false

    private fun files(): List<File> = directory.listFiles { f -> f.extension == "csv" }?.toList().orEmpty()

    companion object {
        const val DIR_NAME = "gestures"
    }
}
