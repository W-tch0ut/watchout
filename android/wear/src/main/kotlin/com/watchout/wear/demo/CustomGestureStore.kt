package com.watchout.wear.demo

import android.content.Context
import com.watchout.gesture.CarCommand
import com.watchout.gesture.CustomGesture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 사용자가 등록한 "내 제스처"를 워치 안 파일(custom_gestures.json)에 저장한다.
 * 동작 모양(템플릿)만 저장하고 센서 원본 녹화는 저장하지 않는다.
 */
class CustomGestureStore(context: Context) {

    private val file = File(context.filesDir, "custom_gestures.json")

    fun load(): List<CustomGesture> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val command = CarCommand.fromId(o.getString("command")) ?: return@mapNotNull null
                val t = o.getJSONArray("templates")
                CustomGesture(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    command = command,
                    templates = (0 until t.length()).map { k ->
                        val a = t.getJSONArray(k)
                        FloatArray(a.length()) { a.getDouble(it).toFloat() }
                    },
                    threshold = o.getDouble("threshold").toFloat(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(gestures: List<CustomGesture>) {
        val arr = JSONArray()
        for (g in gestures) {
            arr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("command", g.command.id)
                    .put("threshold", g.threshold.toDouble())
                    .put(
                        "templates",
                        JSONArray().apply { g.templates.forEach { t -> put(JSONArray().apply { t.forEach { put(it.toDouble()) } }) } },
                    ),
            )
        }
        file.writeText(arr.toString())
    }

    fun add(gesture: CustomGesture) = save(load().filterNot { it.id == gesture.id } + gesture)

    fun delete(id: String) = save(load().filterNot { it.id == id })
}
