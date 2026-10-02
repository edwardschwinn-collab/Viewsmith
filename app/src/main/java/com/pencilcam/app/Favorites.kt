package com.pencilcam.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 * Favorites: looks you've saved under a name, kept between launches.
 *
 * Each favorite is a name plus the whole look (style, finishes, every
 * slider) written out as JSON text by SketchSettings.toJson(). The list
 * lives in its own little SharedPreferences file, "viewsmith_favorites",
 * as one JSON array, in the order they were saved.
 * ============================================================================
 */
class Favorites(context: Context) {

    /** One saved look. */
    class Fav(var name: String, val look: JSONObject)

    private val prefs = context.getSharedPreferences("viewsmith_favorites", Context.MODE_PRIVATE)
    val list = mutableListOf<Fav>()

    init { load() }

    private fun load() {
        list.clear()
        val raw = prefs.getString("list", null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val look = o.optJSONObject("look") ?: continue
                list += Fav(o.optString("name", "Look ${i + 1}"), look)
            }
        } catch (e: Exception) { /* a damaged list just starts empty */ }
    }

    private fun save() {
        val a = JSONArray()
        list.forEach { f -> a.put(JSONObject().put("name", f.name).put("look", f.look)) }
        prefs.edit().putString("list", a.toString()).apply()
    }

    /** Save the current look. A blank name becomes "Look 1", "Look 2", and so on. */
    fun add(name: String, s: SketchSettings) {
        list += Fav(name.trim().ifEmpty { nextName() }, s.toJson()); save()
    }

    fun rename(f: Fav, name: String) {
        if (name.isNotBlank()) { f.name = name.trim(); save() }
    }

    fun delete(f: Fav) { list.remove(f); save() }

    /** First "Look N" name that isn't taken yet. */
    fun nextName(): String {
        var n = list.size + 1
        while (list.any { it.name == "Look $n" }) n++
        return "Look $n"
    }
}
