package com.watchout.wear.voice

import android.util.Log
import com.watchout.command.CommandSender
import com.watchout.command.RobotCommand

/**
 * 임시 명령 전송: 로봇카 대신 Logcat에 명령 JSON만 찍는다.
 *
 * #42(워치 ↔ 로봇카 통신)가 정해지면 MQTT/BLE로 보내는 [CommandSender] 구현으로 바꾼다.
 * 로그에는 **명령만** 남긴다. 받아쓴 말(사용자가 한 말)은 이 클래스까지 오지 않는다.
 *
 * Logcat에서 보기: `adb logcat -s WatchOutCmd`
 */
class LogCommandSender : CommandSender {
    override fun send(command: RobotCommand) {
        Log.i(TAG, command.toJson())
    }

    companion object {
        const val TAG = "WatchOutCmd"
    }
}
