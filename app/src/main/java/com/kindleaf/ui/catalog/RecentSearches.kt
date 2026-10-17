package com.kindleaf.ui.catalog

import android.content.Context

/** Recent search terms, stored locally (most recent first, max [MAX]). */
class RecentSearches(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("kindleaf_search", Context.MODE_PRIVATE)

    fun load(): List<String> =
        prefs.getString(KEY, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.take(MAX)
            ?: emptyList()

    /** Adds [term] to the front (case-insensitive de-dupe) and returns the new list. */
    fun add(term: String): List<String> {
        val clean = term.replace(SEPARATOR, " ").trim()
        if (clean.isBlank()) return load()
        val updated = (listOf(clean) + load().filterNot { it.equals(clean, ignoreCase = true) }).take(MAX)
        save(updated)
        return updated
    }

    fun remove(term: String): List<String> {
        val updated = load().filterNot { it == term }
        save(updated)
        return updated
    }

    fun clear(): List<String> {
        prefs.edit().remove(KEY).apply()
        return emptyList()
    }

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY = "recent_searches"
        const val SEPARATOR = "\n"
        const val MAX = 8
    }
}
