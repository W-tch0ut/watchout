package com.watchout.wear.demo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 진동 (서비스·등록에서 함께 쓴다) */
object Haptics {
    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** [pattern]: 쉬기, 떨림, 쉬기, 떨림 … (ms) */
    fun pulse(context: Context, pattern: LongArray) =
        vibrator(context).vibrate(VibrationEffect.createWaveform(pattern, -1))

    fun stop(context: Context) = vibrator(context).cancel()
}
