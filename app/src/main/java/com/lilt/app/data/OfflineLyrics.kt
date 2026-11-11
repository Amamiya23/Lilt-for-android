package com.lilt.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Retains QRC word times and translations instead of reducing downloaded lyrics to plain text. */
internal object OfflineLyrics {
    fun encode(lyrics: Lyrics): String = JSONArray(lyrics.lines.map { line ->
        JSONObject().put("timeMs", line.timeMs).put("endMs", line.endMs)
            .put("text", line.text).put("translation", line.translation)
            .put("words", JSONArray(line.words.map { word ->
                JSONObject().put("text", word.text).put("startMs", word.startMs).put("endMs", word.endMs)
            }))
    }).toString()

    fun decode(text: String): Lyrics {
        val lines = JSONArray(text)
        return Lyrics((0 until lines.length()).map { index ->
            val line = lines.getJSONObject(index)
            val words = line.getJSONArray("words")
            LyricLine(line.getLong("timeMs"), line.getString("text"), line.getString("translation"),
                line.getLong("endMs"), (0 until words.length()).map { wordIndex ->
                    val word = words.getJSONObject(wordIndex)
                    LyricWord(word.getString("text"), word.getLong("startMs"), word.getLong("endMs"))
                })
        })
    }
}
