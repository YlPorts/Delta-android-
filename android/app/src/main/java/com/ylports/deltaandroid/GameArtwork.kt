package com.ylports.deltaandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Original Delta's local custom-box-art workflow, implemented with Android SAF. */
object GameArtwork {
    fun file(context: Context, game: LibraryGame): File =
        File(File(context.filesDir, "artwork"), "${game.id}.png")

    fun load(context: Context, game: LibraryGame): Bitmap? {
        val location = file(context, game)
        if (!location.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(location.absolutePath, bounds)
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
        return BitmapFactory.decodeFile(location.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun import(context: Context, game: LibraryGame, uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("La imagen no se puede abrir.")
        require(bounds.outWidth in 1..10000 && bounds.outHeight in 1..10000) {
            "Dimensiones de imagen no válidas."
        }
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDimension / sample > 1200) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("No se pudo decodificar la carátula.")
        val directory = file(context, game).parentFile!!
        directory.mkdirs()
        val temp = File(directory, "artwork.tmp")
        try {
            FileOutputStream(temp).use { output ->
                require(raw.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "No se pudo guardar la carátula."
                }
            }
            val dest = file(context, game)
            require(temp.renameTo(dest)) { "No se pudo actualizar la carátula." }
        } finally {
            raw.recycle()
            temp.delete()
        }
    }
}
