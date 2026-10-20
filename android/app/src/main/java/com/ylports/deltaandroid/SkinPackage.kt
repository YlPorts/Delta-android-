package com.ylports.deltaandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Importer for genuine Delta .deltaskin ZIP packages.
 *
 * This reads the actual info.json representation and renders its PNG/PDF
 * artwork (without recreating it). Only GB/GBC layouts are actionable until
 * the remaining console cores are ported.
 */
data class SkinControl(
    val frame: RectF,
    val hit: RectF,
    val inputs: List<String>,
    val directions: Map<String, String>
)

data class SkinScreen(val input: RectF?, val output: RectF)

data class SkinPresentation(
    val name: String,
    val width: Float,
    val height: Float,
    val artwork: Bitmap,
    val controls: List<SkinControl>,
    val screens: List<SkinScreen>,
    val translucent: Boolean
) {
    fun inputAt(x: Float, y: Float): List<String> {
        val found = mutableListOf<String>()
        for (control in controls) {
            if (!control.hit.contains(x, y)) continue
            if (control.directions.isNotEmpty()) {
                // Delta's D-pad occupies a 3x3 grid; center is neutral.
                val px = ((x - control.frame.left) / control.frame.width()).coerceIn(0f, 0.999f)
                val py = ((y - control.frame.top) / control.frame.height()).coerceIn(0f, 0.999f)
                if (px < 1f / 3f) control.directions["left"]?.let(found::add)
                if (px > 2f / 3f) control.directions["right"]?.let(found::add)
                if (py < 1f / 3f) control.directions["up"]?.let(found::add)
                if (py > 2f / 3f) control.directions["down"]?.let(found::add)
            } else found.addAll(control.inputs)
        }
        return found
    }
}

object SkinPackage {
    private const val MAX_PACKAGE = 32L * 1024L * 1024L
    private const val MAX_ENTRY = 24L * 1024L * 1024L
    private const val MAX_JSON = 1024 * 1024
    private const val GBC_ID = "com.rileytestut.delta.game.gbc"

    fun installedFile(context: Context): File =
        File(File(context.filesDir, "skins"), "gbc.deltaskin")

    /** Writes to app-private storage and validates the actual Delta metadata. */
    fun install(context: Context, uri: Uri): String {
        val directory = installedFile(context).parentFile!!
        directory.mkdirs()
        val temp = File(directory, "import.partial")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        size += count
                        require(size <= MAX_PACKAGE) { "Skin demasiado grande (máximo 32 MB)." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("No se pudo leer el archivo .deltaskin.")
            ZipFile(temp).use { archive ->
                val json = readMetadata(archive)
                require(json.optString("gameTypeIdentifier") == GBC_ID) {
                    "Por ahora se admiten skins de Game Boy y Game Boy Color."
                }
                require(json.has("representations")) { "Skin sin representaciones." }
            }
            val destination = installedFile(context)
            if (destination.exists() && !destination.delete()) error("No se pudo reemplazar la skin.")
            require(temp.renameTo(destination)) { "No se pudo guardar la skin." }
            return metadata(destination)?.optString("name", "Skin de Delta") ?: "Skin de Delta"
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun metadata(file: File): JSONObject? = try {
        if (!file.isFile || file.length() > MAX_PACKAGE) null
        else ZipFile(file).use { readMetadata(it) }
    } catch (_: Exception) { null }

    private fun readMetadata(zip: ZipFile): JSONObject {
        val entry = zip.getEntry("info.json") ?: error("Falta info.json.")
        require(entry.size in 1..MAX_JSON.toLong()) { "info.json inválido o demasiado grande." }
        return zip.getInputStream(entry).use {
            val bytes = it.readBytes()
            require(bytes.size <= MAX_JSON) { "info.json demasiado grande." }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    private fun rect(obj: JSONObject?): RectF {
        if (obj == null) return RectF()
        val x = obj.optDouble("x", 0.0).toFloat()
        val y = obj.optDouble("y", 0.0).toFloat()
        val w = obj.optDouble("width", 0.0).toFloat()
        val h = obj.optDouble("height", 0.0).toFloat()
        return RectF(x, y, x + w, y + h)
    }

    fun load(context: Context, landscape: Boolean): SkinPresentation? = try {
        val file = installedFile(context)
        if (!file.isFile) null
        else ZipFile(file).use { zip ->
            val info = readMetadata(zip)
            if (info.optString("gameTypeIdentifier") != GBC_ID) return@use null
            val devices = info.getJSONObject("representations")
            val device = devices.optJSONObject("iphone") ?: devices.optJSONObject("ipad")
                ?: return@use null
            val shape = device.optJSONObject("edgeToEdge") ?: device.optJSONObject("standard")
                ?: return@use null
            val direction = if (landscape) "landscape" else "portrait"
            val layout = shape.optJSONObject(direction) ?: return@use null
            val mapping = layout.optJSONObject("mappingSize")
            val defaultW = if (landscape) 896f else 414f
            val defaultH = if (landscape) 414f else 896f
            val width = mapping?.optDouble("width", defaultW.toDouble())?.toFloat() ?: defaultW
            val height = mapping?.optDouble("height", defaultH.toDouble())?.toFloat() ?: defaultH
            require(width in 100f..4000f && height in 100f..4000f)
            val assets = layout.optJSONObject("assets") ?: return@use null
            val name = listOf("resizable", "large", "medium", "small")
                .firstNotNullOfOrNull { key ->
                    assets.optString(key).takeIf { it.isNotEmpty() && zip.getEntry(it) != null }
                } ?: return@use null
            val entry = zip.getEntry(name) ?: return@use null
            require(!name.startsWith("/") && !name.contains("..") && entry.size in 1..MAX_ENTRY) {
                "Imagen de skin no válida."
            }
            val bytes = zip.getInputStream(entry).use {
                val raw = it.readBytes()
                require(raw.size <= MAX_ENTRY) { "Imagen demasiado grande." }
                raw
            }
            val artwork = when {
                name.endsWith(".png", true) || name.endsWith(".webp", true) ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                name.endsWith(".pdf", true) -> {
                    val pdf = File(context.cacheDir, "delta-skin-temporary.pdf")
                    try {
                        FileOutputStream(pdf).use { it.write(bytes) }
                        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { renderer ->
                                renderer.openPage(0).use { page ->
                                    val scale = minOf(1600f / width, 1600f / height, 3f)
                                    val target = Bitmap.createBitmap(
                                        (width * scale).toInt().coerceAtLeast(1),
                                        (height * scale).toInt().coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    Canvas(target).drawColor(Color.TRANSPARENT)
                                    page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    target
                                }
                            }
                        }
                    } finally { pdf.delete() }
                }
                else -> null
            } ?: return@use null
            val controls = mutableListOf<SkinControl>()
            val padding = layout.optJSONObject("extendedEdges")
            val items = layout.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val frame = rect(item.optJSONObject("frame"))
                if (frame.width() <= 0 || frame.height() <= 0) continue
                val edge = item.optJSONObject("extendedEdges") ?: padding
                val hit = RectF(
                    frame.left - (edge?.optDouble("left", 0.0)?.toFloat() ?: 0f),
                    frame.top - (edge?.optDouble("top", 0.0)?.toFloat() ?: 0f),
                    frame.right + (edge?.optDouble("right", 0.0)?.toFloat() ?: 0f),
                    frame.bottom + (edge?.optDouble("bottom", 0.0)?.toFloat() ?: 0f)
                )
                val inputs = mutableListOf<String>()
                val directions = mutableMapOf<String, String>()
                when (val raw = item.opt("inputs")) {
                    is JSONArray -> for (j in 0 until raw.length()) {
                        raw.optString(j).takeIf { it.isNotBlank() }?.let(inputs::add)
                    }
                    is JSONObject -> for (key in listOf("up", "down", "left", "right")) {
                        raw.optString(key).takeIf { it.isNotBlank() }?.let { directions[key] = it }
                    }
                    is String -> inputs.add(raw)
                }
                if (inputs.isNotEmpty() || directions.isNotEmpty())
                    controls.add(SkinControl(frame, hit, inputs, directions))
            }
            val screens = mutableListOf<SkinScreen>()
            val screenArray = layout.optJSONArray("screens") ?: JSONArray()
            for (i in 0 until screenArray.length()) {
                val screen = screenArray.optJSONObject(i) ?: continue
                val output = rect(screen.optJSONObject("outputFrame"))
                if (output.width() > 0 && output.height() > 0) {
                    val source = if (screen.has("inputFrame")) rect(screen.optJSONObject("inputFrame")) else null
                    screens.add(SkinScreen(source, output))
                }
            }
            SkinPresentation(info.optString("name", "Delta"), width, height, artwork,
                controls, screens, layout.optBoolean("translucent", false))
        }
    } catch (_: Exception) { null }
}
