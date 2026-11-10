package com.universal3d.viewer.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Recently opened files (persisted URIs with display info), newest first. */
class RecentFiles(context: Context) {
    class Entry(val uri: String, val name: String, val size: Long, val format: String, val openedAt: Long, val triangles: Long)

    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    fun list(): List<Entry> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); Entry(o.getString("uri"), o.getString("name"), o.optLong("size"), o.optString("format"), o.optLong("openedAt"), o.optLong("triangles")) }
        } catch (e: Exception) { emptyList() }
    }

    fun add(uri: Uri, name: String, size: Long, format: String, triangles: Long) {
        try { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val items = list().filter { it.uri != uri.toString() }.toMutableList()
        items.add(0, Entry(uri.toString(), name, size, format, System.currentTimeMillis(), triangles))
        save(items.take(30))
    }

    fun remove(uri: String) = save(list().filter { it.uri != uri })
    fun clear() = save(emptyList())

    private fun save(items: List<Entry>) {
        val arr = JSONArray()
        for (e in items) arr.put(JSONObject().put("uri", e.uri).put("name", e.name).put("size", e.size).put("format", e.format).put("openedAt", e.openedAt).put("triangles", e.triangles))
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
