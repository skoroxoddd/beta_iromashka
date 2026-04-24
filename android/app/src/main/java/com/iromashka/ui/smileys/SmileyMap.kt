package com.iromashka.ui.smileys

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SmileyMap {
    private var byShortcode: Map<String, String> = emptyMap()
    private var ordered: List<Pair<String, String>> = emptyList()

    fun load(ctx: Context) {
        if (byShortcode.isNotEmpty()) return
        runCatching {
            val json = ctx.assets.open("smileys/smiley_map.json").bufferedReader().use { it.readText() }
            val map: Map<String, String> = Gson().fromJson(json, object : TypeToken<Map<String, String>>(){}.type)
            byShortcode = map
            ordered = map.entries.map { it.key to it.value }
        }
    }

    fun all(): List<Pair<String, String>> = ordered

    fun shortcodeForFile(file: String): String? = byShortcode.entries.firstOrNull { it.value == file }?.key

    fun fileFor(shortcode: String): String? = byShortcode[shortcode]
}
