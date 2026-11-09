package com.idanck.shidur.data.db

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers so map/list columns stay readable in a SQLite text column. */
internal object Json {

    fun encodeMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun decodeMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank() || raw == "{}") return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                o.keys().forEach { k -> put(k, o.optString(k, "")) }
            }
        }.getOrDefault(emptyMap())
    }

    fun encodeList(list: List<String>): String {
        if (list.isEmpty()) return "[]"
        val a = JSONArray()
        list.forEach(a::put)
        return a.toString()
    }

    fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
