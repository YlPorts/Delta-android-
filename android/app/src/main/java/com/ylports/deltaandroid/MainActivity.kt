package com.ylports.deltaandroid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Android entry point. Only the first real core (GB/GBC) is wired in this stage. */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROM = 101
        private const val MAX_ROM_BYTES = 8L * 1024L * 1024L
    }

    private lateinit var emulator: EmulatorView
    private lateinit var status: TextView
    private var hasGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(12, 15, 19))
        }
        val picker = Button(this).apply {
            text = "Importar juego Game Boy / Color"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_ROM)
            }
        }
        content.addView(picker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emulator = EmulatorView(this)
        content.addView(emulator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            text = "Delta Android 0.1.0-dev • Motor Gambatte"
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(12, 4, 12, 12)
        }
        content.addView(status)
        setContentView(content)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
            }
        return uri.lastPathSegment.orEmpty()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension != "gb" && extension != "gbc") {
            status.text = "Por ahora solo se admiten archivos .gb y .gbc sin comprimir."
            return
        }
        emulator.stop()
        EmulatorBridge.close()
        hasGame = false
        try {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            val rom = File(filesDir, "rom-$hash.$extension")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(rom).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val bytes = input.read(buffer)
                        if (bytes == -1) break
                        total += bytes
                        if (total > MAX_ROM_BYTES) error("El archivo supera los 8 MB.")
                        output.write(buffer, 0, bytes)
                    }
                    if (total == 0L) error("El archivo está vacío.")
                }
            } ?: error("No se pudo abrir el archivo.")
            val corePath = File(applicationInfo.nativeLibraryDir, "libgambatte_libretro.so")
            val error = EmulatorBridge.open(corePath.absolutePath,
                rom.absolutePath, filesDir.absolutePath)
            if (error != null) {
                status.text = error
            } else {
                hasGame = true
                status.text = "Jugando: $name"
                emulator.start()
            }
        } catch (e: Exception) {
            status.text = "Error al importar ROM: ${e.message}"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mask = EmulatorView.mapPhysicalKey(keyCode)
        if (mask != 0) {
            emulator.pressKey(mask, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mask = EmulatorView.mapPhysicalKey(keyCode)
        if (mask != 0) {
            emulator.pressKey(mask, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        emulator.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (hasGame) emulator.start()
    }

    override fun onDestroy() {
        emulator.stop()
        EmulatorBridge.close()
        super.onDestroy()
    }
}
