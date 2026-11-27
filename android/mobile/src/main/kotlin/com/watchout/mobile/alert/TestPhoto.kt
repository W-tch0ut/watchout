package com.watchout.mobile.alert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/**
 * [테스트 응급 알림]에 붙이는 가짜 사진 (#50).
 * 로봇카 카메라가 아직 연결되지 않아서, 회색 바탕에 글자를 그린 작은 그림을 JPEG으로 만든다.
 * 실제 사람 사진이 아니라서 개인정보가 들어 있지 않다.
 */
object TestPhoto {
    fun create(): AlertPhoto {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(90, 98, 110))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("테스트 사진", WIDTH / 2f, HEIGHT / 2f - 10, paint)
        paint.textSize = 18f
        canvas.drawText("(로봇카 카메라 자리)", WIDTH / 2f, HEIGHT / 2f + 24, paint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        bitmap.recycle()
        return AlertPhoto(out.toByteArray())
    }

    private const val WIDTH = 320
    private const val HEIGHT = 240
}
