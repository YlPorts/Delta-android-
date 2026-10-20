package com.ylports.deltaandroid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Device-private Delta-style game library. Game paths are stable across file
 * picker sessions and are identified by ROM content instead of a transient URI.
 */
data class LibraryGame(
    val id: String, val title: String, val system: String, val file: File,
    val favorite: Boolean = false, val lastPlayed: Long = 0L
)

class GameLibrary(private val context: Context) {
    private val directory = File(context.filesDir, "games").apply { mkdirs() }
    private val catalog = File(directory, "library.json")

    fun games(): List<LibraryGame> {
        val data = try { JSONArray(catalog.readText(Charsets.UTF_8)) } catch (_: Exception) { JSONArray() }
        val result = mutableListOf<LibraryGame>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val extension = item.optString("system")
            if (id.length != 64 || !id.matches(Regex("[0-9a-f]{64}")) ||
                extension !in listOf("gb", "gbc")) continue
            val file = File(directory, "$id.$extension")
            if (file.isFile) result.add(
                LibraryGame(id, item.optString("title", "Juego"), extension, file,
                    item.optBoolean("favorite", false), item.optLong("lastPlayed", 0L))
            )
        }
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun markPlayed(id: String) {
        save(games().map { if (it.id == id) it.copy(lastPlayed = System.currentTimeMillis()) else it })
    }

    fun toggleFavorite(id: String) {
        save(games().map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    private fun save(games: List<LibraryGame>) {
        val json = JSONArray()
        for (game in games) json.put(JSONObject().apply {
            put("id", game.id)
            put("title", game.title)
            put("system", game.system)
            put("favorite", game.favorite)
            put("lastPlayed", game.lastPlayed)
        })
        val tmp = File(directory, "library.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        // Atomic replacement on the same filesystem, with no silent catalog loss.
        require(tmp.renameTo(catalog)) { "No se pudo actualizar la biblioteca." }
    }

    fun importGame(uri: Uri): LibraryGame {
        val name = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension == "gb" || extension == "gbc") {
            "Solo se aceptan juegos .gb y .gbc en esta fase."
        }
        val temp = File(directory, "import.partial")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8192)
                    var length = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        length += n
                        require(length <= 8L * 1024 * 1024) { "La ROM supera los 8 MB." }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    require(length > 0) { "El archivo está vacío." }
                }
            } ?: error("No se puede abrir ese archivo.")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val dest = File(directory, "$hash.$extension")
            if (!dest.exists()) require(temp.renameTo(dest)) { "No se pudo guardar la ROM." }
            else temp.delete()
            val title = name.substringBeforeLast('.').replace('_', ' ')
            val existing = games().find { it.id == hash }
            val result = LibraryGame(hash, existing?.title ?: title, extension, dest,
                existing?.favorite ?: false, existing?.lastPlayed ?: 0L)
            save(games().filterNot { it.id == hash } + result)
            return result
        } finally { temp.delete() }
    }
}
