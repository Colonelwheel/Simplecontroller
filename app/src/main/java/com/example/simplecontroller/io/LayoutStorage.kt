package com.example.simplecontroller.io

import android.content.Context
import android.util.Log
import com.example.simplecontroller.model.Control
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "LayoutStorage"
private const val EXT = ".json"
// LayoutStorage.kt
private val json = Json {
    prettyPrint     = true
    ignoreUnknownKeys = true    // ← load files created by older versions
    encodeDefaults  = true      // ← write *all* default-valued fields
}


/* ---------- helpers ---------- */
private fun fileFor(ctx: Context, name: String) =
    ctx.getFileStreamPath("layout_${name.lowercase()}$EXT")

/* ---------- public API ---------- */

/** Return a list of saved layout names (without extension). */
fun listLayouts(ctx: Context): List<String> =
    ctx.filesDir.listFiles()
        ?.filter { it.name.startsWith("layout_") && it.name.endsWith(EXT) }
        ?.map  { it.name.removePrefix("layout_").removeSuffix(EXT) }
        .orEmpty()

/** Decode the named layout or null on error / not found. */
fun loadControls(ctx: Context, name: String): List<Control>? = runCatching {
    fileFor(ctx, name).takeIf { it.exists() }?.readText()?.let {
        json.decodeFromString<List<Control>>(it)
    }
}.onFailure { Log.w(TAG, "load $name failed", it) }.getOrNull()

/** Encode & persist the current layout. */
fun saveControls(ctx: Context, name: String, controls: List<Control>) = runCatching {
    fileFor(ctx, name).writeText(json.encodeToString(controls))
    Log.i(TAG, "saved ${controls.size} controls → $name")
}.onFailure { Log.e(TAG, "save $name failed", it) }
