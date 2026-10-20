package com.ylports.deltaandroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
        private const val SELECT_ARTWORK = 103
        private val BACKGROUND = OriginalDeltaColors.darkGray
        private val SURFACE = Color.rgb(34, 35, 43)
        private val ACCENT = OriginalDeltaColors.purple
    }

    private val library by lazy { GameLibrary(this) }
    private var emulator: EmulatorView? = null
    private var activeGame: LibraryGame? = null
    private var inSettings = false
    private var currentPage = "all"
    private var filterText = ""
    private var selectedGame: LibraryGame? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f

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
            type = if (request == SELECT_ARTWORK) "image/*" else "*/*"
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

    // Navigation below mirrors Main.storyboard: left gear, centered Games title,
    // right "+" import button, a search controller and system page indicators.
    private fun showLibrary() {
        emulator?.stop()
        emulator = null
        if (activeGame != null) {
            EmulatorBridge.close()
            activeGame = null
        }
        inSettings = false
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        window.navigationBarColor = BACKGROUND
        window.statusBarColor = BACKGROUND

        val root = page().apply { setPadding(0, 0, 0, 0) }
        val all = library.games()
        val pages = mutableListOf<Pair<String, String>>()
        if (all.any { it.favorite }) pages.add("favorites" to "Favorites")
        if (all.any { it.lastPlayed > 0 }) pages.add("recent" to "Recently Played")
        pages.add("all" to "Game Boy Color")
        if (pages.none { it.first == currentPage }) currentPage = "all"

        val bar = FrameLayout(this).apply {
            background = rounded(Color.rgb(29, 30, 35), 0)
        }
        val title = text(pages.first { it.first == currentPage }.second, 17f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        bar.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(dp(63), 0, dp(63), 0)
        })
        val gear = text("⚙", 27f).apply {
            gravity = Gravity.CENTER
            setTextColor(OriginalDeltaColors.lightPurple)
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
        }
        bar.addView(gear, FrameLayout.LayoutParams(dp(56), dp(54), Gravity.START or Gravity.CENTER_VERTICAL))
        val plus = text("+", 33f).apply {
            gravity = Gravity.CENTER
            setTextColor(OriginalDeltaColors.lightPurple)
            contentDescription = "Import"
            setOnClickListener { picker(SELECT_ROM) }
        }
        bar.addView(plus, FrameLayout.LayoutParams(dp(56), dp(54), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(bar)

        val search = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            hint = "Search"
            setHintTextColor(Color.rgb(144, 145, 153))
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(SURFACE, 11)
            setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.ic_menu_search, 0, 0, 0
            )
            compoundDrawablePadding = dp(7)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(filterText)
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            setMargins(dp(16), dp(12), dp(16), dp(10))
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val pager = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(29, 30, 35), 0)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun updateCells() {
            scroll.removeAllViews()
            val chosen = when (currentPage) {
                "favorites" -> all.filter { it.favorite }
                "recent" -> all.filter { it.lastPlayed > 0 }
                    .sortedByDescending { it.lastPlayed }
                else -> all
            }.filter { it.title.contains(filterText, ignoreCase = true) }
            if (chosen.isEmpty()) {
                val empty = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(50), dp(20), dp(30))
                }
                empty.addView(text(if (filterText.isNotBlank()) "No Results" else "No Games",
                    24f, true).apply { gravity = Gravity.CENTER })
                empty.addView(text(
                    if (filterText.isNotBlank()) "Try a different search."
                    else "You can import games by pressing the + button in the top right.",
                    15f
                ).apply {
                    setTextColor(Color.rgb(170, 171, 179))
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(26))
                })
                if (filterText.isBlank()) empty.addView(
                    action("Import Game…") { picker(SELECT_ROM) }
                )
                scroll.addView(empty, ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
                return
            }

            // Delta's compact GridCollectionViewLayout uses 90pt cover art
            // with 12pt minimum column spacing (see iOS original).
            val widthPx = resources.displayMetrics.widthPixels - dp(24)
            val columns = ((widthPx + dp(12)) / dp(102)).coerceAtLeast(1)
            val tile = kotlin.math.min(dp(90), (widthPx - dp(12) * columns) / columns)
            val grid = GridLayout(this).apply {
                columnCount = columns
                alignmentMode = GridLayout.ALIGN_BOUNDS
                useDefaultMargins = false
            }
            for (game in chosen) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(14))
                    setOnClickListener { startGame(game) }
                    setOnLongClickListener { showGameMenu(game); true }
                }
                val artwork = TextView(this).apply {
                    text = if (game.system == "gbc") "GBC" else "GB"
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(84, 73, 119), Color.rgb(37, 32, 57))
                    ).apply { cornerRadius = dp(7).toFloat() }
                }
                val customArt = GameArtwork.load(this, game)
                if (customArt != null) {
                    card.addView(ImageView(this).apply {
                        setImageBitmap(customArt)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(SURFACE, 7)
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (tile * 1.12f).toInt()))
                } else {
                    card.addView(artwork, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (tile * 1.12f).toInt()))
                }
                card.addView(text(game.title, 12f, true).apply {
                    maxLines = 2
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(43)))
                grid.addView(card, GridLayout.LayoutParams().apply {
                    width = tile + dp(12)
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                })
            }
            scroll.addView(grid)
        }
        fun redrawPager() {
            pager.removeAllViews()
            for ((id, label) in pages) {
                val indicator = text(when(id) {
                    "favorites" -> "★"
                    "recent" -> "◷"
                    else -> "●"
                }, 22f, true).apply {
                    gravity = Gravity.CENTER
                    setTextColor(if (id == currentPage)
                        Color.WHITE else Color.rgb(115, 115, 122))
                    contentDescription = label
                    setOnClickListener {
                        currentPage = id
                        title.text = label
                        redrawPager()
                        updateCells()
                    }
                }
                pager.addView(indicator, LinearLayout.LayoutParams(dp(64), dp(44)))
            }
        }
        redrawPager()
        root.addView(pager)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterText = s?.toString().orEmpty()
                updateCells()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateCells()
        setContentView(root)
    }

    private fun showGameMenu(game: LibraryGame) {
        AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(arrayOf(
                "Play",
                if (game.favorite) "Remove from Favorites" else "Add to Favorites",
                "Change Artwork…", "Cancel"
            )) { _, item ->
                when(item) {
                    0 -> startGame(game)
                    1 -> {
                        library.toggleFavorite(game.id)
                        showLibrary()
                    }
                    2 -> {
                        selectedGame = game
                        picker(SELECT_ARTWORK)
                    }
                }
            }.show()
    }

    // Inset grouped sections and navigation match Settings.storyboard structure.
    // Unported settings are visibly marked rather than pretending to function.
    private fun showSettings() {
        if (activeGame != null) {
            emulator?.stop()
            EmulatorBridge.close()
            emulator = null
            activeGame = null
        }
        inSettings = true
        val root = page().apply {
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.rgb(19, 19, 24))
        }
        val toolbar = FrameLayout(this).apply {
            background = rounded(Color.rgb(29, 30, 35), 0)
        }
        toolbar.addView(text("Settings", 17f, true).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        toolbar.addView(text("Done", 17f, true).apply {
            setTextColor(OriginalDeltaColors.lightPurple)
            gravity = Gravity.CENTER
            setOnClickListener { showLibrary() }
        }, FrameLayout.LayoutParams(dp(74), dp(54), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(toolbar)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(22))
        }
        fun section(name: String) {
            body.addView(text(name.uppercase(), 13f).apply {
                setTextColor(Color.rgb(160, 162, 171))
                setPadding(dp(18), dp(24), 0, dp(8))
            })
        }
        fun row(label: String, detail: String = "", enabled: Boolean = true,
                callback: (() -> Unit)? = null) {
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(SURFACE, 10)
                setPadding(dp(16), dp(10), dp(14), dp(10))
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(label, 16f).apply {
                if (!enabled) setTextColor(Color.rgb(172, 172, 181))
            })
            if (detail.isNotEmpty()) labels.addView(text(detail, 12f).apply {
                setTextColor(Color.rgb(167, 165, 175))
                setPadding(0, dp(3), 0, 0)
            })
            wrapper.addView(labels, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            wrapper.addView(text(if (enabled && callback != null) "›" else "", 26f).apply {
                setTextColor(Color.rgb(140, 140, 154))
            })
            if (enabled && callback != null) wrapper.setOnClickListener { callback() }
            body.addView(wrapper, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(if(detail.isEmpty()) 53 else 66)
            ).apply { setMargins(0, 0, 0, dp(4)) })
        }
        section("Controllers")
        row("Player 1", "On-screen controls")
        row("Player 2", "No Connected Controllers", false)
        row("Player 3", "No Connected Controllers", false)
        row("Player 4", "No Connected Controllers", false)

        section("Controller Skins")
        val skin = SkinPackage.installedFile(this)
        val skinName = SkinPackage.metadata(skin)?.optString("name")
            ?: "Default Controller Skin"
        row("Game Boy Color", skinName) { picker(SELECT_SKIN) }
        row("Import Controller Skin…") { picker(SELECT_SKIN) }
        row("Restore Default Skin") {
            AlertDialog.Builder(this).setTitle("Restore Controller Skin?")
                .setMessage("The imported skin will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ ->
                    skin.delete()
                    showSettings()
                }.show()
        }

        section("Controller Opacity")
        val opacity = getSharedPreferences("delta-ios", MODE_PRIVATE)
        body.addView(SeekBar(this).apply {
            max = 100
            progress = opacity.getInt("controllerOpacity", 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) opacity.edit().putInt("controllerOpacity", value).apply()
                }
                override fun onStartTrackingTouch(view: SeekBar?) {}
                override fun onStopTrackingTouch(view: SeekBar?) {}
            })
        })
        section("Display")
        row("Change App Icon", "Not ported yet", false)
        section("Game Audio")
        row("Respect Silent Mode", "Not ported yet", false)
        section("Haptic Feedback")
        row("Buttons / Control Sticks", "Not ported yet", false)
        section("Delta Sync")
        row("Service", "Not ported yet", false)
        section("Core Settings")
        row("Game Boy Color", "Gambatte")
        section("Advanced")
        row("Experimental Features", "Not ported yet", false)
        section("Credits")
        row("Licenses", "Delta AGPL-3.0") {
            AlertDialog.Builder(this).setTitle("Licenses")
                .setMessage("Delta is licensed under AGPLv3. Gambatte retains its own upstream license.")
                .setPositiveButton("Done", null).show()
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        library.markPlayed(game.id)
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
        if (requestCode == SELECT_ARTWORK) {
            val game = selectedGame
            selectedGame = null
            if (uri != null && game != null) {
                try {
                    GameArtwork.import(this, game, uri)
                    showLibrary()
                } catch (error: Exception) {
                    AlertDialog.Builder(this).setTitle("Artwork Import Error")
                        .setMessage(error.message)
                        .setPositiveButton("Done", null).show()
                }
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

    // Gesture navigation from Delta's UIPageViewController: horizontal swipes
    // move between Favorites / Recently Played / console collections.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (activeGame == null && !inSettings && filterText.isBlank()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (kotlin.math.abs(dx) > dp(72) &&
                        kotlin.math.abs(dy) < dp(60)) {
                        val all = library.games()
                        val ids = mutableListOf<String>()
                        if (all.any { it.favorite }) ids.add("favorites")
                        if (all.any { it.lastPlayed > 0 }) ids.add("recent")
                        ids.add("all")
                        val current = ids.indexOf(currentPage).coerceAtLeast(0)
                        val next = (current + if (dx < 0) 1 else -1)
                            .coerceIn(0, ids.lastIndex)
                        if (next != current) {
                            currentPage = ids[next]
                            showLibrary()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
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
