package com.doubao.repkg

import android.content.Context
import android.net.Uri

/**
 * Remembers the user-chosen export folder (a SAF document-tree URI) across
 * launches. When unset, assets fall back to the default MediaStore locations.
 */
object ExportSettings {

    private const val PREFS = "export_settings"
    private const val KEY_TREE_URI = "tree_uri"

    fun getTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setTreeUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TREE_URI).apply()
    }

    fun isCustom(context: Context): Boolean = getTreeUri(context) != null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
