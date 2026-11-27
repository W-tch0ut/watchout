package com.watchout.mobile.car

import android.content.Context
import android.net.wifi.WifiManager
import com.watchout.gesture.CarInfo
import com.watchout.gesture.CarScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 로봇카 찾기의 "약속". 테스트에서는 가짜를 쓴다 */
fun interface CarFinder {
    /** 같은 Wi-Fi의 로봇카 목록. 찾는 데 실패하면(네트워크 오류) 예외 */
    suspend fun scan(): List<CarInfo>
}

/**
 * 폰의 Wi-Fi로 로봇카 방송을 듣는다. 실제 듣기는 워치와 같은 [CarScanner] (:gesture 모듈).
 * 안드로이드는 절전 중에 방송 패킷을 버릴 수 있어서, 찾는 동안만 MulticastLock을 잡는다.
 */
class WifiCarFinder(context: Context, private val durationMs: Long = 4000) : CarFinder {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override suspend fun scan(): List<CarInfo> = withContext(Dispatchers.IO) {
        val lock = wifi.createMulticastLock("watchout-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            CarScanner.scan(durationMs)
        } finally {
            lock.release()
        }
    }
}
