package com.ylports.deltaandroid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
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
) {
    val core get() = CoreSpec.forExtension(system) ?: error("Unknown system: $system")
}

class GameLibrary(private val context: Context) {
    private val directory = File(context.filesDir, "games").apply { mkdirs() }
    private val catalog = File(directory, "library.json")
    private val atomicCatalog = AtomicFile(catalog)

    var catalogReadFailure: String? = null
        private set
    @Synchronized fun games(): List<LibraryGame> {
        val data = try {
            atomicCatalog.openRead().use { input ->
                require(input.channel.size() <= 8L * 1024 * 1024) { "Catálogo demasiado grande." }
                JSONArray(input.bufferedReader(Charsets.UTF_8).readText())
            }.also { catalogReadFailure = null }
        } catch (_: java.io.FileNotFoundException) {
            catalogReadFailure = null
            JSONArray()
        } catch (error: Exception) {
            catalogReadFailure = "No se pudo leer la biblioteca; se conservan el catálogo, los juegos y sus guardados. ${error.message.orEmpty()}"
            return emptyList()
        }
        val result = mutableListOf<LibraryGame>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val extension = item.optString("system")
            if (id.length != 64 || !id.matches(Regex("[0-9a-f]{64}")) ||
                CoreSpec.forExtension(extension) == null) continue
            val file = File(directory, "$id.$extension")
            if (file.isFile) result.add(
                LibraryGame(id, item.optString("title", "Juego"), extension, file,
                    item.optBoolean("favorite", false), item.optLong("lastPlayed", 0L))
            )
        }
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    @Synchronized fun markPlayed(id: String) {
        save(games().map { if (it.id == id) it.copy(lastPlayed = System.currentTimeMillis()) else it })
    }

    @Synchronized fun toggleFavorite(id: String) {
        save(games().map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    private fun save(games: List<LibraryGame>) {
        check(catalogReadFailure == null) { catalogReadFailure.orEmpty() }
        val json = JSONArray()
        for (game in games) json.put(JSONObject().apply {
            put("id", game.id)
            put("title", game.title)
            put("system", game.system)
            put("favorite", game.favorite)
            put("lastPlayed", game.lastPlayed)
        })
        val output = atomicCatalog.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomicCatalog.finishWrite(output)
        } catch (e: Exception) { atomicCatalog.failWrite(output); throw e }
    }

    @Synchronized fun importGame(uri: Uri): LibraryGame {
        val name = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val core = CoreSpec.forExtension(extension)
        require(core?.playable == true) { "Se admiten .gb, .gbc, .gba y .nes; los demás núcleos siguen pendientes." }
        val temp = File.createTempFile("rom-import-", ".partial", directory)
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
                        require(length <= core!!.maxRomBytes) { "La ROM supera el tamaño máximo de esta consola." }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    require(length > 0) { "El archivo está vacío." }
                    output.fd.sync()
                }
            } ?: error("No se puede abrir ese archivo.")
            temp.inputStream().use { input ->
                val header = ByteArray(0x150)
                val read = input.read(header)
                if (extension == "nes") require(read >= 16 && header.copyOfRange(0, 4)
                    .contentEquals(byteArrayOf(0x4e, 0x45, 0x53, 0x1a))) { "Cabecera iNES no válida." }
                else if (extension == "gba") require(read >= 0xc0 && header[0xb2].toInt() and 0xff == 0x96) {
                    "Cabecera de Game Boy Advance no válida."
                }
                else require(read == header.size && temp.length() >= 32768) { "ROM de Game Boy incompleta." }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = games().find { it.id == hash }
            check(catalogReadFailure == null) { catalogReadFailure.orEmpty() }
            // Same content imported with a different GB/GBC suffix must keep its
            // existing ROM path, SRAM, RTC and state paths.
            val dest = existing?.file ?: File(directory, "$hash.$extension")
            if (!dest.exists()) require(temp.renameTo(dest)) { "No se pudo guardar la ROM." }
            else temp.delete()
            val title = name.substringBeforeLast('.').replace('_', ' ')
            val result = LibraryGame(hash, existing?.title ?: title, existing?.system ?: extension, dest,
                existing?.favorite ?: false, existing?.lastPlayed ?: 0L)
            save(games().filterNot { it.id == hash } + result)
            return result
        } finally { temp.delete() }
    }
}
