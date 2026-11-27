package com.watchout.wear.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.watchout.command.CommandSender
import com.watchout.command.RobotCommand
import com.watchout.gesture.CarProtocol
import com.watchout.gesture.WatchLink
import com.watchout.wear.demo.DemoState
import com.watchout.wear.demo.PhoneLink

/**
 * 음성 명령을 폰으로 보낸다 → 폰이 로봇카에 전달한다 (제스처 명령과 같은 길, #42).
 *
 * 메시지는 [RobotCommand.toJson] 그대로에 `source:"voice"`와 시각만 덧붙인다
 * (로봇카는 모르는 필드를 무시하고, 폰·로봇카 기록에서 어디서 온 명령인지 구분할 수 있다).
 * 받아쓴 말(사용자가 한 말)은 보내지 않는다. 명령만 나간다.
 */
class PhoneCommandSender(context: Context) : CommandSender {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun send(command: RobotCommand) {
        val json = CarProtocol.withSource(command.toJson(), "voice", System.currentTimeMillis())
        Log.i(LogCommandSender.TAG, json) // adb logcat -s WatchOutCmd 로 계속 볼 수 있게
        PhoneLink.sendToPhone(app, WatchLink.MSG_CMD, json)
        main.post { DemoState.log("🎤 ${command.toJson()}") }
    }
}
