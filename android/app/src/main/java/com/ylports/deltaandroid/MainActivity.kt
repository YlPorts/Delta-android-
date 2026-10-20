package com.ylports.deltaandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Original Delta-style navigation: game library -> emulation -> pause / settings.
 * This is a native Android adaptation, not a port of UIKit itself.
 * GB/GBC is the only system whose original engine is integrated so far.
 */
class MainActivity : Activity() {
    companion object {
        private const val SELECT_ROM = 101
        private const val SELECT_SKIN = 102
        private val BACKGROUND = Color.rgb(16, 17, 21)
        private val SURFACE = Color.rgb(34, 35, 43)
        private val ACCENT = Color.rgb(121, 83, 231)
    }

    private val library by lazy { GameLibrary(this) }
    private var emulator: EmulatorView? = null
    private var activeGame: LibraryGame? = null
    private var inSettings = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(label: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun action(label: String, callback: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 13)
            setOnClickListener { callback() }
        }

    private fun picker(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, request)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = BACKGROUND
        window.statusBarColor = BACKGROUND
        showLibrary()
    }

    private fun page(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
            setBackgroundColor(BACKGROUND)
        }

    private fun bottomNavigation(root: LinearLayout) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        fun tab(label: String, selected: Boolean, click: () -> Unit) {
            val view = text(label, 14f, selected).apply {
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else Color.LTGRAY)
                setOnClickListener { click() }
                background = rounded(if (selected) SURFACE else BACKGROUND, 12)
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            }
            nav.addView(view)
        }
        tab("Juegos", !inSettings) { showLibrary() }
        tab("Ajustes", inSettings) { showSettings() }
        root.addView(nav)
    }

    private fun showLibrary() {
        emulator?.stop()
        emulator = null
        if (activeGame != null) {
            EmulatorBridge.close()
            activeGame = null
        }
        inSettings = false
        val root = page()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = text("Delta", 35f, true)
        header.addView(heading, LinearLayout.LayoutParams(0, dp(68), 1f))
        header.addView(action("+") { picker(SELECT_ROM) },
            LinearLayout.LayoutParams(dp(56), dp(46)))
        root.addView(header)
        root.addView(text("Game Boy Color", 23f, true).apply {
            setPadding(0, dp(14), 0, dp(18))
        })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val games = library.games()
        if (games.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            empty.addView(text("Tu biblioteca está vacía", 20f, true).apply {
                gravity = Gravity.CENTER
            })
            empty.addView(text("Importa un juego .gb o .gbc con el botón +", 14f).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            empty.addView(action("Importar juego") { picker(SELECT_ROM) })
            scroll.addView(empty)
        } else {
            val grid = GridLayout(this).apply {
                columnCount = 3
                setPadding(0, 0, 0, dp(20))
            }
            val screenDp = resources.displayMetrics.widthPixels
            val tileWidth = (screenDp - dp(42)) / 3
            for (game in games) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(14))
                    setOnClickListener { startGame(game) }
                }
                val art = TextView(this).apply {
                    text = if (game.system == "gbc") "GBC" else "GB"
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(89, 55, 173), Color.rgb(37, 28, 66))
                    ).apply { cornerRadius = dp(10).toFloat() }
                }
                card.addView(art, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (tileWidth * 1.24f).toInt()))
                card.addView(text(game.title, 12f, true).apply {
                    maxLines = 2
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(43)))
                grid.addView(card, GridLayout.LayoutParams().apply {
                    width = tileWidth
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                })
            }
            scroll.addView(grid)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomNavigation(root)
        setContentView(root)
    }

    private fun showSettings() {
        if (activeGame != null) {
            emulator?.stop()
            EmulatorBridge.close()
            emulator = null
            activeGame = null
        }
        inSettings = true
        val root = page()
        root.addView(text("Ajustes", 34f, true).apply {
            setPadding(0, dp(16), 0, dp(28))
        })
        root.addView(text("Skins de controles", 22f, true))
        val skinFile = SkinPackage.installedFile(this)
        val title = SkinPackage.metadata(skinFile)?.optString("name")
        root.addView(text(title ?: "Controles táctiles predeterminados", 15f).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(14), 0, dp(14))
        })
        root.addView(action("Importar skin original .deltaskin") {
            picker(SELECT_SKIN)
        })
        root.addView(action("Restaurar controles predeterminados") {
            skinFile.delete()
            showSettings()
        }.apply { setPadding(0, dp(4), 0, dp(4)) })
        root.addView(text("Compatible actualmente: skins originales de GB/GBC con " +
            "PNG o PDF y coordenadas de info.json.", 13f).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(24), 0, dp(20))
        })
        root.addView(text("Acerca de", 22f, true))
        root.addView(text("Delta Android 0.2.0-dev\n" +
            "Motor original de Game Boy / Game Boy Color: Gambatte.\n" +
            "Port en desarrollo; los demás sistemas, carátulas originales, " +
            "filtros avanzados y Delta Sync siguen pendientes.", 14f).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(14), 0, dp(12))
        })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        bottomNavigation(root)
        setContentView(root)
    }

    private fun startGame(game: LibraryGame) {
        emulator?.stop()
        EmulatorBridge.close()
        activeGame = null
        val core = java.io.File(applicationInfo.nativeLibraryDir, "libgambatte_libretro.so")
        val message = EmulatorBridge.open(core.absolutePath, game.file.absolutePath,
            filesDir.absolutePath)
        if (message != null) {
            AlertDialog.Builder(this).setTitle("No se pudo iniciar")
                .setMessage(message).setPositiveButton("Aceptar", null).show()
            return
        }
        activeGame = game
        val player = EmulatorView(this)
        player.onSkinMenu = { showPauseMenu() }
        emulator = player
        val frame = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        frame.addView(player, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pause = TextView(this).apply {
            text = "Ⅱ"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.argb(150, 38, 38, 47))
            setOnClickListener { showPauseMenu() }
        }
        frame.addView(pause, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(10), dp(12), 0, 0) })
        setContentView(frame)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        player.start()
    }

    private fun showPauseMenu() {
        val game = activeGame ?: return
        emulator?.stop()
        AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(arrayOf(
                "Reanudar", "Guardar estado", "Cargar estado", "Cambiar skin", "Biblioteca"
            )) { _, which ->
                when (which) {
                    0 -> emulator?.start()
                    1 -> selectStateSlot(true)
                    2 -> selectStateSlot(false)
                    3 -> picker(SELECT_SKIN)
                    4 -> showLibrary()
                }
            }
            .setOnCancelListener { if (activeGame != null) emulator?.start() }
            .show()
    }

    private fun selectStateSlot(saving: Boolean) {
        if (activeGame == null) return
        val slots = (1..5).map { "Ranura $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (saving) "Guardar estado" else "Cargar estado")
            .setItems(slots) { _, selection ->
                // The emulation thread was stopped before opening the pause menu.
                val error = if (saving) EmulatorBridge.saveState(selection)
                            else EmulatorBridge.loadState(selection)
                if (error == null) {
                    android.widget.Toast.makeText(this,
                        if (saving) "Estado guardado" else "Estado cargado",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, error,
                        android.widget.Toast.LENGTH_LONG).show()
                }
                if (activeGame != null) emulator?.start()
            }
            .setOnCancelListener { if (activeGame != null) emulator?.start() }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = if (resultCode == RESULT_OK) data?.data else null
        if (requestCode == SELECT_ROM) {
            if (uri == null) return
            try {
                library.importGame(uri)
                showLibrary()
            } catch (error: Exception) {
                AlertDialog.Builder(this).setTitle("Error al importar")
                    .setMessage(error.message).setPositiveButton("Aceptar", null).show()
            }
        }
        if (requestCode == SELECT_SKIN) {
            if (uri != null) {
                try {
                    val name = SkinPackage.install(this, uri)
                    emulator?.refreshSkin()
                    if (activeGame == null) showSettings()
                    else {
                        android.widget.Toast.makeText(this, "Skin instalada: $name",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    AlertDialog.Builder(this).setTitle("Skin no compatible")
                        .setMessage(error.message).setPositiveButton("Aceptar", null).show()
                }
            }
            if (activeGame != null) emulator?.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mask = EmulatorView.mapPhysicalKey(keyCode)
        if (activeGame != null && mask != 0) {
            emulator?.pressKey(mask, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mask = EmulatorView.mapPhysicalKey(keyCode)
        if (activeGame != null && mask != 0) {
            emulator?.pressKey(mask, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Overridden for backwards compatibility with physical Back key")
    override fun onBackPressed() {
        if (activeGame != null) showPauseMenu()
        else if (inSettings) showLibrary()
        else super.onBackPressed()
    }

    override fun onPause() {
        emulator?.stop()
        if (activeGame != null) EmulatorBridge.flushSave()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (activeGame != null) emulator?.start()
    }

    override fun onDestroy() {
        emulator?.stop()
        EmulatorBridge.close()
        super.onDestroy()
    }
}
