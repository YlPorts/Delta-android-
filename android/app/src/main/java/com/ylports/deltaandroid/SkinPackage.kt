package com.ylports.deltaandroid

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.min

/** Coordinates/placement follow DeltaCore/Model/ControllerSkin.swift. */
data class SkinControl(val frame: RectF, val hit: RectF, val inputs: List<String>,
                       val directions: Map<String, String>, val appPlacement: Boolean = false)
data class SkinScreen(val input: RectF?, val output: RectF?, val appPlacement: Boolean = false)
data class SkinGeometry(val controller: RectF, val available: RectF)
data class InstalledSkin(val file: File, val name: String, val id: String, val isDefault: Boolean)

data class SkinPresentation(val name: String, val width: Float, val height: Float,
                            val artwork: Bitmap?, val controls: List<SkinControl>,
                            val screens: List<SkinScreen>, val translucent: Boolean) {
    fun geometry(w: Float, h: Float): SkinGeometry {
        // Same split/fit rules as DeltaCore UI/Game/GameViewController.swift.
        if (w < h && screens.none { !it.appPlacement }) {
            val controlHeight = min(h, w / width * height)
            return SkinGeometry(RectF(0f, h - controlHeight, w, h), RectF(0f, 0f, w, h - controlHeight))
        }
        return SkinGeometry(fit(width, height, RectF(0f, 0f, w, h)), RectF(0f, 0f, w, h))
    }
    fun inputAt(x: Float, y: Float, geometry: SkinGeometry, appFrame: RectF): List<String> {
        val found = mutableListOf<String>()
        for (control in controls) {
            val bounds = if (control.appPlacement) appFrame else geometry.controller
            val sx = if (control.appPlacement) 1f else width
            val sy = if (control.appPlacement) 1f else height
            val px = (x - bounds.left) / bounds.width() * sx
            val py = (y - bounds.top) / bounds.height() * sy
            if (!control.hit.contains(px, py)) continue
            if (control.directions.isEmpty()) found.addAll(control.inputs)
            else {
                val dx = (px - control.frame.left) / control.frame.width()
                val dy = (py - control.frame.top) / control.frame.height()
                if (dx < 1f/3f) control.directions["left"]?.let(found::add)
                if (dx > 2f/3f) control.directions["right"]?.let(found::add)
                if (dy < 1f/3f) control.directions["up"]?.let(found::add)
                if (dy > 2f/3f) control.directions["down"]?.let(found::add)
            }
        }
        return found
    }
    companion object {
        fun fit(w: Float, h: Float, bounds: RectF): RectF {
            val scale = min(bounds.width() / w, bounds.height() / h).coerceAtLeast(0f)
            val left = bounds.centerX() - w * scale / 2f
            val top = bounds.centerY() - h * scale / 2f
            return RectF(left, top, left + w * scale, top + h * scale)
        }
    }
}

object SkinPackage {
    private const val MAX_PACKAGE = 32L * 1024 * 1024
    private const val MAX_ENTRY = 24 * 1024 * 1024
    private const val MAX_JSON = 1024 * 1024
    private fun directory(context: Context) = File(context.filesDir, "skins").apply { mkdirs() }
    // Retain the old 0.2 custom package; selection migration is non-destructive.
    fun installedFile(context: Context) = File(directory(context), "gbc.deltaskin")
    private fun defaults(context: Context, system: String): File {
        val file = File(context.cacheDir, "standard-$system-${BuildConfig.VERSION_CODE}.deltaskin")
        val complete = file.isFile && runCatching {
            ZipFile(file).use { it.getEntry("info.json") != null }
        }.getOrDefault(false)
        if (!complete) {
            val temp = File.createTempFile("standard-$system-", ".partial", context.cacheDir)
            try {
                context.assets.open("skins/$system.deltaskin").use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                require(temp.renameTo(file)) { "No se pudo preparar la skin original." }
            } finally { temp.delete() }
        }
        return file
    }
    fun installed(context: Context, system: String): List<InstalledSkin> {
        val standard = defaults(context, system)
        val list = mutableListOf(InstalledSkin(standard, "Standard", "standard", true))
        for (file in directory(context).listFiles().orEmpty().filter { it.extension == "deltaskin" }) {
            val data = metadata(file) ?: continue
            if (data.optString("gameTypeIdentifier") != "com.rileytestut.delta.game.$system") continue
            list.add(InstalledSkin(file, data.optString("name"), file.name, false))
        }
        return list
    }
    fun selectedId(context: Context, system: String, landscape: Boolean): String {
        val prefs = context.getSharedPreferences("delta-ios", Context.MODE_PRIVATE)
        return prefs.getString("skin.$system.${if (landscape) "landscape" else "portrait"}", null)
            ?: if (system == "gbc" && installedFile(context).isFile) "gbc.deltaskin" else "standard"
    }
    fun select(context: Context, system: String, landscape: Boolean, id: String) {
        val file = installed(context, system).first { it.id == id }.file
        ZipFile(file).use { parse(context, it, readMetadata(it), landscape).artwork?.recycle() }
        context.getSharedPreferences("delta-ios", Context.MODE_PRIVATE).edit()
            .putString("skin.$system.${if (landscape) "landscape" else "portrait"}", id).apply()
    }
    fun install(context: Context, uri: Uri): String {
        val temp = File.createTempFile("skin-import-", ".partial", directory(context))
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= MAX_PACKAGE) { "Skin demasiado grande (32 MB máximo)." }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            } ?: error("No se pudo leer la skin.")
            val info = ZipFile(temp).use { zip ->
                val json = readMetadata(zip)
                val system = CoreSpec.all.firstOrNull { it.gameType == json.optString("gameTypeIdentifier") }
                    ?: error("Consola desconocida en info.json.")
                require(json.optString("identifier").isNotBlank() && json.optString("name").isNotBlank()) {
                    "Faltan el nombre o el identificador de la skin."
                }
                var supported = 0
                for (landscape in listOf(false, true)) {
                    if (representation(json, landscape) != null) {
                        parse(context, zip, json, landscape).artwork?.recycle()
                        supported++
                    }
                }
                require(supported > 0) { "La skin no contiene representaciones iPhone compatibles." }
                system to json
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(temp.readBytes())
                .joinToString("") { "%02x".format(it) }
            val dest = File(directory(context), "$digest.deltaskin")
            require(temp.renameTo(dest)) { "No se pudo guardar la skin; se conserva la anterior." }
            for (landscape in listOf(false, true)) if (representation(info.second, landscape) != null)
                select(context, info.first.id, landscape, dest.name)
            return info.second.getString("name")
        } finally { temp.delete() }
    }
    fun metadata(file: File): JSONObject? = try {
        if (!file.isFile || file.length() > MAX_PACKAGE) null
        else ZipFile(file).use { readMetadata(it) }
    } catch (_: Exception) { null }

    private fun read(zip: ZipFile, name: String, limit: Int): ByteArray {
        require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "Ruta de recurso inválida." }
        val entry = zip.getEntry(name) ?: error("Falta $name.")
        require(entry.size in 1..limit.toLong()) { "Recurso demasiado grande: $name." }
        return zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream(min(entry.size.toInt(), 8192))
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= limit) { "Recurso descomprimido demasiado grande." }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }
    private fun readMetadata(zip: ZipFile) = JSONObject(String(read(zip, "info.json", MAX_JSON), Charsets.UTF_8))
    private fun rect(obj: JSONObject?): RectF? {
        if (obj == null) return null
        val x = obj.optDouble("x", 0.0).toFloat()
        val y = obj.optDouble("y", 0.0).toFloat()
        val w = obj.getDouble("width").toFloat()
        val h = obj.getDouble("height").toFloat()
        require(listOf(x, y, w, h).all { it.isFinite() } && w > 0 && h > 0) { "Coordenadas inválidas." }
        return RectF(x, y, x + w, y + h)
    }
    private fun representation(info: JSONObject, landscape: Boolean): JSONObject? {
        val devices = info.getJSONObject("representations")
        val orientation = if (landscape) "landscape" else "portrait"
        for (device in listOf("iphone", "ipad")) {
            val reps = devices.optJSONObject(device) ?: continue
            // Modern traits, then pre-displayType format used by early Delta skins.
            for (shape in listOf("edgeToEdge", "standard", "splitView"))
                reps.optJSONObject(shape)?.optJSONObject(orientation)?.let { return it }
            reps.optJSONObject(orientation)?.let { return it }
        }
        return null
    }
    fun load(context: Context, landscape: Boolean, system: String = "gbc"): SkinPresentation {
        val list = installed(context, system)
        val selected = list.firstOrNull { it.id == selectedId(context, system, landscape) } ?: list.first()
        try {
            return ZipFile(selected.file).use { parse(context, it, readMetadata(it), landscape) }
        } catch (e: Exception) {
            if (selected.isDefault) throw e
            android.util.Log.w("DeltaSkin", "Custom representation unavailable; using original Standard skin", e)
            return ZipFile(list.first().file).use { parse(context, it, readMetadata(it), landscape) }
        }
    }
    private fun parse(context: Context, zip: ZipFile, info: JSONObject, landscape: Boolean): SkinPresentation {
        val layout = representation(info, landscape) ?: error("La skin no admite esta orientación.")
        val mapping = layout.getJSONObject("mappingSize")
        val width = mapping.getDouble("width").toFloat()
        val height = mapping.getDouble("height").toFloat()
        require(width.isFinite() && height.isFinite() && width in 1f..8192f && height in 1f..8192f)
        val assets = layout.optJSONObject("assets") ?: JSONObject()
        val asset = listOf("resizable", "large", "medium", "small")
            .firstNotNullOfOrNull { assets.optString(it).takeIf(String::isNotBlank) }
        val artwork = asset?.let { name ->
            val bytes = read(zip, name, MAX_ENTRY)
            when {
                name.endsWith(".pdf", true) -> renderPdf(context, bytes, width, height)
                name.endsWith(".png", true) || name.endsWith(".webp", true) -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "Imagen de skin demasiado grande." }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Imagen dañada.")
                }
                else -> error("Recurso de skin aún no compatible: $name")
            }
        }
        try {
            val controls = mutableListOf<SkinControl>()
            val inherited = layout.optJSONObject("extendedEdges")
            val items = layout.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val frame = rect(item.optJSONObject("frame")) ?: continue
                require(!item.has("thumbstick")) { "Esta skin requiere un stick gráfico todavía no portado." }
                val edge = item.optJSONObject("extendedEdges")
                fun padding(key: String) = (if (edge?.has(key) == true) edge.getDouble(key)
                    else inherited?.optDouble(key, 0.0) ?: 0.0).toFloat().also { require(it.isFinite()) }
                val hit = RectF(frame.left - padding("left"), frame.top - padding("top"),
                    frame.right + padding("right"), frame.bottom + padding("bottom"))
                val inputs = mutableListOf<String>()
                val directions = mutableMapOf<String, String>()
                when (val raw = item.opt("inputs")) {
                    is JSONArray -> for (j in 0 until raw.length()) inputs.add(raw.getString(j))
                    is JSONObject -> for (key in listOf("up", "down", "left", "right"))
                        raw.optString(key).takeIf(String::isNotBlank)?.let { directions[key] = it }
                    is String -> inputs.add(raw)
                }
                if (inputs.isNotEmpty() || directions.isNotEmpty()) controls.add(SkinControl(frame, hit,
                    inputs, directions, item.optString("placement") == "app"))
            }
            val screens = mutableListOf<SkinScreen>()
            val legacy = rect(layout.optJSONObject("gameScreenFrame"))
            if (legacy != null) screens.add(SkinScreen(null, legacy))
            else {
                val raw = layout.optJSONArray("screens") ?: JSONArray()
                for (i in 0 until raw.length()) {
                    val screen = raw.getJSONObject(i)
                    require((screen.optJSONArray("filters")?.length() ?: 0) == 0) {
                        "Esta skin requiere filtros Core Image todavía no portados."
                    }
                    val output = rect(screen.optJSONObject("outputFrame"))
                    val app = screen.optString("placement", if (output == null) "app" else "controller") == "app"
                    screens.add(SkinScreen(rect(screen.optJSONObject("inputFrame")), output, app))
                }
            }
            return SkinPresentation(info.getString("name"), width, height, artwork, controls,
                screens, layout.optBoolean("translucent", false))
        } catch (e: Exception) { artwork?.recycle(); throw e }
    }
    internal fun renderPdf(context: Context, bytes: ByteArray, width: Float, height: Float): Bitmap {
        val file = File.createTempFile("delta-pdf-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    require(pdf.pageCount > 0) { "PDF vacío." }
                    pdf.openPage(0).use { page ->
                        val scale = minOf(2048f / width, 2048f / height, 3f)
                        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.TRANSPARENT)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } finally { file.delete() }
    }
}
