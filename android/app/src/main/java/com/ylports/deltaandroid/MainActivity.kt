package com.ylports.deltaandroid

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.*
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

/** Continues the existing native host, using the checked-in UIKit reference. */
class MainActivity : Activity() {
    companion object {
        private const val SELECT_ROM = 101
        private const val SELECT_SKIN = 102
        private const val SELECT_ARTWORK = 103
        private const val EXPORT_SCREENSHOT = 104
    }
    private val ui by lazy { DeltaUi(this) }
    private val library by lazy { GameLibrary(this) }
    private val prefs by lazy { getSharedPreferences("delta-ios", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private var emulator: EmulatorView? = null
    private var activeGame: LibraryGame? = null
    private var gameContainer: FrameLayout? = null
    private var pauseOverlay: View? = null
    private var inSettings = false
    private var currentPage = "gbc"
    private var filterText = ""
    private var selectedGame: LibraryGame? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swiping = false
    private var userPaused = false
    private var foreground = false
    private var externalFlow = false
    private var modal: Dialog? = null
    private var pendingScreenshot: Bitmap? = null
    private var audioFocus = false
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        runOnUiThread {
            audioFocus = change == AudioManager.AUDIOFOCUS_GAIN
            if (!audioFocus && activeGame != null) showPauseMenu()
        }
    }
    private fun dp(v: Int) = ui.dp(v)
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun error(title: String, message: String?) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message ?: "Unknown error")
            .setPositiveButton("OK", null).show()
    }
    private fun requestAudioFocus(): Boolean {
        if (audioFocus) return true
        @Suppress("DEPRECATION")
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        audioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return audioFocus
    }
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else { @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener) }
        audioFocus = false
    }
    private fun updatePlayback() {
        if (foreground && activeGame != null && !userPaused && !externalFlow && modal == null) {
            if (requestAudioFocus()) emulator?.start()
            else showPauseMenu()
        } else emulator?.stop()
    }
    private fun picker(request: Int) {
        externalFlow = true
        updatePlayback()
        val intent = if (request == EXPORT_SCREENSHOT) Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "image/png"; putExtra(Intent.EXTRA_TITLE,"${activeGame?.title ?: "Delta"}.png")
        } else Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = if (request == SELECT_ARTWORK) "image/*" else "*/*"
            if (request == SELECT_ROM) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, request)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) { handleBack() }
        currentPage = savedInstanceState?.getString("page") ?: prefs.getString("libraryPage", "gbc")!!
        filterText = savedInstanceState?.getString("search").orEmpty()
        showLibrary()
        val gameId = savedInstanceState?.getString("activeGame")
        val game = gameId?.let { id -> library.games().firstOrNull { it.id == id } }
        if (game != null) {
            startGame(game, resumeAfterOpen = false)
            if (EmulatorBridge.loadState(9) != null) toast("The previous game could not be restored automatically.")
            showPauseMenu()
        }
    }
    private fun showChrome(root: View, immersive: Boolean = false) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 30) window.decorView.systemUiVisibility = if (immersive)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            // Window.getInsetsController() can dereference a null DecorView
            // during onCreate. Configure only the attached native content view.
            root.post {
                val lightStatus = inSettings && !ui.dark
                // Keep the decor flag in sync: later platform color-view updates
                // otherwise restore the dark theme's white status icons.
                @Suppress("DEPRECATION")
                run {
                    val decor = window.decorView
                    decor.systemUiVisibility = if (lightStatus)
                        decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    else decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
                root.windowInsetsController?.apply {
                    if (immersive) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
                    systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    setSystemBarsAppearance(if (lightStatus) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                }
            }
        }
        // Android 15+ enforces edge-to-edge; keep library/settings inside real insets.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (!immersive) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                }
            }
            insets
        }
        @Suppress("DEPRECATION")
        run { window.statusBarColor = if (inSettings) ui.grouped else Color.rgb(29,29,31)
            window.navigationBarColor = if (inSettings) ui.grouped else OriginalDeltaColors.darkGray }
        setContentView(root)
        root.requestApplyInsets()
    }
    private fun stopSession() {
        emulator?.stop()
        if (activeGame != null) EmulatorBridge.flushSave()?.let { toast(it) }
        EmulatorBridge.close()
        abandonAudioFocus()
        emulator = null; activeGame = null; gameContainer = null; pauseOverlay = null
        userPaused = false
    }
    private fun pageList(games: List<LibraryGame>): List<Pair<String,String>> {
        val pages = mutableListOf<Pair<String,String>>()
        if (games.any { it.favorite }) pages.add("favorites" to "Favorites")
        if (games.any { it.lastPlayed > 0 }) pages.add("recent" to "Recently Played")
        for (core in CoreSpec.all) if (games.any { it.core.id == core.id }) pages.add(core.id to core.title)
        return pages
    }
    private fun showLibrary(animateDirection: Int = 0) {
        if (activeGame != null) stopSession()
        inSettings = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(OriginalDeltaColors.darkGray)
        }
        val all = library.games()
        library.catalogReadFailure?.let { toast(it) }
        val pages = pageList(all)
        if (pages.isNotEmpty() && pages.none { it.first == currentPage }) currentPage = pages.last().first
        prefs.edit().putString("libraryPage",currentPage).apply()
        val gear = ui.settingsIcon().apply { setOnClickListener { showSettings() } }
        val plus = ui.link("+",OriginalDeltaColors.purple) { picker(SELECT_ROM) }.apply {
            textSize = 30f; contentDescription = "Import Games"
        }
        root.addView(ui.navigation(if (all.isEmpty()) "Games" else pages.first { it.first == currentPage }.second,
            true, gear, plus), LinearLayout.LayoutParams(-1,dp(44)))
        val search = EditText(this).apply {
            setSingleLine(); textSize = 17f; includeFontPadding = false
            hint = "Search"; setHintTextColor(Color.rgb(153,153,158)); setTextColor(Color.WHITE)
            background = ui.rounded(Color.rgb(58,58,60),10f)
            setPadding(dp(12),0,dp(12),0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(filterText)
            contentDescription = "Search Games"
        }
        // Search magnifier uses a native path; Apple's SF Symbols cannot be bundled.
        search.setCompoundDrawablesWithIntrinsicBounds(SearchGlyph(dp(17)),null,null,null)
        search.compoundDrawablePadding = dp(7)
        root.addView(search,LinearLayout.LayoutParams(-1,dp(36)).apply { setMargins(dp(16),dp(8),dp(16),dp(8)) })
        val content = FrameLayout(this)
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        fun updateGames() {
            content.removeAllViews()
            val selected = if (filterText.isNotBlank()) all.filter { it.title.contains(filterText,true) }
            else when (currentPage) {
                "favorites" -> all.filter { it.favorite }
                "recent" -> all.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }
                else -> all.filter { it.core.id == currentPage }
            }
            if (selected.isEmpty()) {
                val empty = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(20),0,dp(20),0)
                }
                empty.addView(ui.text(if (filterText.isNotBlank()) "No Games Found" else "No Games",28f,Color.rgb(153,153,153))
                    .apply { gravity = Gravity.CENTER })
                empty.addView(ui.text(if (filterText.isNotBlank())
                    "Please make sure the name is correct, or try searching for another game."
                    else "You can import games by pressing the + button in the top right.",16f,Color.rgb(153,153,153))
                    .apply { gravity = Gravity.CENTER; setPadding(0,dp(8),0,0) })
                if (filterText.isBlank()) empty.addView(ui.link("Learn More…",OriginalDeltaColors.purple) {
                    openLink("https://faq.deltaemulator.com/")
                }.apply { textSize = 20f },LinearLayout.LayoutParams(-2,dp(30)).apply { topMargin = dp(20) })
                content.addView(empty,FrameLayout.LayoutParams(-1,-1))
            } else {
                val scroll = ScrollView(this)
                val grid = DeltaGrid(this)
                for (game in selected) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        contentDescription = "${game.title}, ${game.core.title}"
                        setOnClickListener {
                            animate().scaleX(.94f).scaleY(.94f).setDuration(100).withEndAction { startGame(game) }.start()
                        }
                        setOnLongClickListener { showGameMenu(game); true }
                    }
                    card.addView(ImageView(this).apply {
                        val art = GameArtwork.load(this@MainActivity,game)
                        if (art == null) setImageResource(R.drawable.delta_box_art) else setImageBitmap(art)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = ui.rounded(Color.TRANSPARENT,5f); clipToOutline = true
                    },LinearLayout.LayoutParams(dp(90),dp(90)))
                    card.addView(ui.text(game.title,12f,Color.GRAY).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0,dp(8),0,0)
                    },LinearLayout.LayoutParams(-1,-2))
                    grid.addView(card)
                }
                scroll.addView(grid,FrameLayout.LayoutParams(-1,-2))
                content.addView(scroll,FrameLayout.LayoutParams(-1,-1))
            }
        }
        updateGames()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                filterText = s?.toString().orEmpty(); updateGames()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // UIPageViewController's page indicators are dots, including special pages.
        if (pages.size > 1) {
            val pager = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
            for ((id,title) in pages) pager.addView(FrameLayout(this).apply {
                contentDescription = title
                addView(View(this@MainActivity).apply {
                    background = ui.rounded(if (id == currentPage) Color.WHITE else Color.rgb(100,100,100),4f)
                },FrameLayout.LayoutParams(dp(7),dp(7),Gravity.CENTER))
                setOnClickListener { currentPage = id; showLibrary() }
            },LinearLayout.LayoutParams(dp(16),dp(37)))
            root.addView(pager,LinearLayout.LayoutParams(-1,dp(37)))
        }
        showChrome(root)
        if (animateDirection != 0) {
            content.translationX = dp(40).toFloat()*animateDirection; content.alpha = .5f
            content.animate().translationX(0f).alpha(1f).setDuration(220).start()
        }
    }
    private fun showGameMenu(game: LibraryGame) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(20)) }
        val group = ui.section(body,game.title)
        lateinit var dialog: Dialog
        ui.row(group,"Play") { dialog.dismiss(); startGame(game) }
        ui.row(group,if (game.favorite) "Remove from Favorites" else "Add to Favorites") {
            dialog.dismiss(); library.toggleFavorite(game.id); showLibrary()
        }
        ui.row(group,"Change Artwork…") { dialog.dismiss(); selectedGame = game; picker(SELECT_ARTWORK) }
        dialog = ui.sheet(game.title,body,"Cancel")
    }
    private fun showSettings() {
        if (activeGame != null) stopSession()
        inSettings = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.grouped) }
        root.addView(ui.navigation("", left = ui.link("Cancel") { showLibrary() }),LinearLayout.LayoutParams(-1,dp(44)))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(32)) }
        body.addView(ui.text("Settings",34f,ui.label,true).apply { setPadding(0,dp(4),0,dp(8)) })
        var group = ui.section(body,"Controllers")
        val devices = controllers()
        val selectedController = devices.firstOrNull { it.descriptor == prefs.getString("controller","") }
        ui.row(group,"Player 1",selectedController?.name ?: "Touch Screen") { showControllers() }
        for (player in 2..4) ui.row(group,"Player $player","None",false)
        group = ui.section(body,"Controller Skins")
        for (core in CoreSpec.all) ui.row(group,core.title,if (core.playable) "" else "Unavailable",core.playable,
            action = if (core.playable) ({ showSkins(core.id) }) else null)
        ui.row(group,"Import Controller Skin…") { picker(SELECT_SKIN) }
        group = ui.section(body,"Controller Opacity")
        val opacityValue = ui.text("${prefs.getInt("controllerOpacity",70)}%",17f,ui.secondary)
        val sliderRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),0,dp(16),0) }
        val slider = SeekBar(this).apply {
            max = 100; progress = prefs.getInt("controllerOpacity",70)
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(v: SeekBar?,value: Int,fromUser: Boolean) {
                    opacityValue.text = "$value%"
                    if (fromUser) prefs.edit().putInt("controllerOpacity",value).apply()
                }
                override fun onStartTrackingTouch(v: SeekBar?) {}
                override fun onStopTrackingTouch(v: SeekBar?) {}
            })
        }
        sliderRow.addView(slider,LinearLayout.LayoutParams(0,dp(51),1f)); sliderRow.addView(opacityValue)
        group.addView(sliderRow)
        ui.footer(body,"Determines how translucent the controller appears, if supported by the controller skin.")
        group = ui.section(body,"Display")
        ui.row(group,"Change App Icon","Unavailable",false)
        group = ui.section(body,"Game Audio")
        ui.row(group,"Respect Silent Mode",accessory = ui.toggle(prefs.getBoolean("respectSilentMode",true)) {
            prefs.edit().putBoolean("respectSilentMode",it).apply()
        })
        ui.footer(body,"When enabled, Delta will only play game audio if your device isn't silenced.")
        group = ui.section(body,"Haptic Feedback")
        ui.row(group,"Buttons",accessory = ui.toggle(prefs.getBoolean("buttonHaptics",true)) {
            prefs.edit().putBoolean("buttonHaptics",it).apply()
        })
        ui.footer(body,"When enabled, your device will vibrate in response to touch screen controls.")
        group = ui.section(body,"Delta Sync")
        ui.row(group,"Service","Unavailable",false)
        ui.footer(body,"Sync services are not available in this development version.")
        group = ui.section(body,"Gestures")
        ui.row(group,"Menu Button Gestures",accessory = ui.toggle(prefs.getBoolean("menuGestures",true)) {
            prefs.edit().putBoolean("menuGestures",it).apply()
        })
        group = ui.section(body,"Core Settings")
        for (core in CoreSpec.all) ui.row(group,core.title,core.engine,core.playable,
            action = if (core.playable) ({ showCoreSettings(core) }) else null)
        group = ui.section(body,"Advanced")
        ui.row(group,"Build Information","${BuildConfig.VERSION_NAME}") { showBuildInfo() }
        group = ui.section(body,"Credits")
        ui.row(group,"Contributors") { openLink("https://github.com/rileytestut/Delta/graphs/contributors") }
        ui.row(group,"Software Licenses") { showLicenses() }
        group = ui.section(body,"Support")
        ui.row(group,"Frequently Asked Questions") { openLink("https://faq.deltaemulator.com/") }
        ui.row(group,"Report an Issue") { openLink("https://github.com/YlPorts/Delta-android-/issues") }
        body.addView(ui.text("Delta Android ${BuildConfig.VERSION_NAME}",13f,ui.secondary).apply {
            gravity = Gravity.CENTER; setPadding(0,dp(28),0,0)
        })
        scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        showChrome(root)
    }
    private fun showSkins(system: String) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(20)) }
        for (landscape in listOf(false,true)) {
            val group = ui.section(body,if (landscape) "Landscape" else "Portrait")
            val selected = SkinPackage.selectedId(this,system,landscape)
            for (skin in SkinPackage.installed(this,system)) {
                ui.row(group,skin.name,if (skin.id == selected) "✓" else "") {
                    try { SkinPackage.select(this,system,landscape,skin.id); modal?.dismiss(); modal = null; showSkins(system) }
                    catch (e: Exception) { error("Controller Skin",e.message) }
                }
            }
        }
        ui.row(ui.section(body,"Controller Skins"),"Import Controller Skin…") { modal?.dismiss(); modal = null; picker(SELECT_SKIN) }
        modal = ui.sheet(CoreSpec.forId(system)!!.title,body)
        modal?.setOnDismissListener { modal = null }
    }
    private fun controllers() = InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice).filter {
        it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }
    private fun showControllers() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0) }
        val group = ui.section(body,"Player 1")
        val selected = prefs.getString("controller","")
        ui.row(group,"Touch Screen",if (selected.isNullOrEmpty()) "✓" else "") {
            prefs.edit().remove("controller").apply(); modal?.dismiss()
        }
        for (device in controllers()) ui.row(group,device.name,if (selected == device.descriptor) "✓" else "") {
            prefs.edit().putString("controller",device.descriptor).apply(); modal?.dismiss()
        }
        ui.footer(body,"Connect a Bluetooth or USB controller in Android settings. Touch controls remain available.")
        modal = ui.sheet("Controllers",body)
        modal?.setOnDismissListener { modal = null }
    }
    private fun showCoreSettings(core: CoreSpec) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0) }
        val group = ui.section(body,core.title)
        ui.row(group,"Core",core.engine)
        ui.footer(body,"The core controls the console's original timing. Save states are tied to this ROM and core build.")
        ui.sheet(core.title,body)
    }
    private fun showBuildInfo() {
        val body = ui.text("${BuildConfig.VERSION_NAME}\nARM64 native build\n\nGB/GBC: Gambatte\nNES: Nestopia\nGBA: VBA-M\n\nSNES, Genesis, N64 and DS: pending.\nDelta Sync: unavailable.\n\niOS reference: Delta 2.0b5 (163), UIKit grid UI.\nVisual 1:1 parity and Galaxy A15 validation remain pending.",15f).apply { setPadding(dp(20),dp(20),dp(20),dp(20)) }
        ui.sheet("Build Information",body)
    }
    private fun showLicenses() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(20)) }
        val group = ui.section(body,"Software Licenses")
        for (name in assets.list("licenses").orEmpty()) ui.row(group,name.removeSuffix(".txt")) {
            val text = assets.open("licenses/$name").bufferedReader().use { it.readText() }
            ui.sheet(name,ui.text(text,12f).apply { setPadding(dp(16),dp(16),dp(16),dp(16)) })
        }
        ui.sheet("Software Licenses",body)
    }
    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        catch (e: Exception) { error("Open Link",e.message) }
    }
    private fun startGame(game: LibraryGame, resumeAfterOpen: Boolean = true) {
        stopSession()
        val name = game.core.library ?: return error("Unavailable","This console's Android adapter is still pending.")
        val message = EmulatorBridge.open(File(applicationInfo.nativeLibraryDir,name).absolutePath,
            game.file.absolutePath,filesDir.absolutePath)
        if (message != null) return error("Could Not Start Game",message)
        activeGame = game; inSettings = false; userPaused = !resumeAfterOpen
        library.markPlayed(game.id)
        EmulatorBridge.setCheats(cheats(game))
        val player = EmulatorView(this,game.core.id)
        player.onSkinMenu = { showPauseMenu() }
        player.onHoldSelection = { showPauseMenu() }
        fun quickState(saving: Boolean) {
            player.stop()
            val error = if (saving) EmulatorBridge.saveState(8) else EmulatorBridge.loadState(8)
            if (error == null) toast(if (saving) "Quick State Saved" else "Quick State Loaded") else toast(error)
            updatePlayback()
        }
        player.onQuickSave = { quickState(true) }
        player.onQuickLoad = { quickState(false) }
        player.onFailure = { if (!isDestroyed && !isFinishing && activeGame?.id == game.id) { showPauseMenu(); error("Emulation Error",it) } }
        emulator = player
        gameContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(player,FrameLayout.LayoutParams(-1,-1))
        }
        showChrome(gameContainer!!,true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePlayback()
    }
    private fun clearPauseOverlay() {
        pauseOverlay?.let { gameContainer?.removeView(it) }; pauseOverlay = null
        if (Build.VERSION.SDK_INT >= 31) emulator?.setRenderEffect(null)
    }
    private fun resumeGame() {
        clearPauseOverlay(); userPaused = false; updatePlayback()
    }
    private fun showPauseMenu() {
        if (activeGame == null || pauseOverlay != null) return
        userPaused = true; emulator?.stop()
        EmulatorBridge.flushSave()?.let { toast(it) }
        if (Build.VERSION.SDK_INT >= 31) emulator?.setRenderEffect(RenderEffect.createBlurEffect(dp(10).toFloat(),dp(10).toFloat(),Shader.TileMode.CLAMP))
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.argb(65,0,0,0)); setOnClickListener { resumeGame() } }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Color.argb(215,36,36,40),0f)
            setOnClickListener { }
        }
        panel.addView(ui.navigation("Paused",true,
            ui.link("Main Menu",Color.rgb(255,69,58)) { modal?.dismiss(); modal = null; showLibrary() },
            ui.link("Resume",OriginalDeltaColors.lightPurple) { resumeGame() }))
        val identity = FrameLayout(this)
        val identityStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(15),dp(15),dp(15),dp(15))
        }
        val pauseIcon = ui.icon(R.drawable.delta_pause,80,Color.WHITE)
        identityStack.addView(pauseIcon,LinearLayout.LayoutParams(dp(64),dp(80)))
        identityStack.addView(ui.text(activeGame!!.title,28f,Color.WHITE).apply {
            gravity = Gravity.CENTER; maxLines = 2
            setPadding(0,dp(15),0,0)
        },LinearLayout.LayoutParams(-1,-2))
        identity.addView(identityStack,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
        overlay.addView(identity,FrameLayout.LayoutParams(-1,0,Gravity.TOP))
        val grid = DeltaGrid(this,90,10,20,20,true)
        data class PauseAction(val title: String,val icon: Int,val selected: Boolean = false,val run: () -> Unit)
        val actions = listOf(
            PauseAction("Save State",R.drawable.delta_save_state) { selectStateSlot(true) },
            PauseAction("Load State",R.drawable.delta_load_state) { selectStateSlot(false) },
            PauseAction("Cheat Codes",R.drawable.delta_cheat_codes) { showCheats() },
            PauseAction("Fast Forward",R.drawable.delta_fast_forward,emulator?.fastForward == true) {
                emulator?.let { it.fastForward = !it.fastForward }; resumeGame()
            },
            PauseAction("Hold Buttons",R.drawable.delta_sustain_buttons,emulator?.hasHeldButtons() == true) {
                emulator?.let { player ->
                    if (player.hasHeldButtons()) { player.clearHeldButtons(); resumeGame() }
                    else {
                        clearPauseOverlay(); player.selectingHeldButtons = true
                        toast("Touch the buttons to hold, then press Menu.")
                    }
                }
            },
            PauseAction("Screenshot",R.drawable.delta_screenshot) {
                pendingScreenshot?.recycle(); pendingScreenshot = emulator?.screenshot(); picker(EXPORT_SCREENSHOT)
            }
        )
        for (action in actions) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = action.title; setOnClickListener { action.run() }
            }
            cell.addView(ui.icon(action.icon,78,if (action.selected) Color.BLACK else Color.WHITE).apply {
                background = ui.rounded(if (action.selected) Color.WHITE else Color.argb(77,255,255,255),16f)
            },LinearLayout.LayoutParams(dp(78),dp(78)))
            cell.addView(ui.text(action.title,12f,Color.WHITE,true).apply {
                gravity = Gravity.CENTER; setPadding(0,dp(8),0,0)
            },LinearLayout.LayoutParams(-1,-2))
            grid.addView(cell)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        panel.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        overlay.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        overlay.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            val available = (overlay.height-panel.height).coerceAtLeast(0)
            if (identity.layoutParams.height != available) {
                identity.layoutParams = FrameLayout.LayoutParams(-1,available,Gravity.TOP)
                pauseIcon.visibility = if (available < dp(140)) View.GONE else View.VISIBLE
            }
        }
        gameContainer?.addView(overlay,FrameLayout.LayoutParams(-1,-1)); pauseOverlay = overlay
        panel.translationY = dp(45).toFloat(); panel.alpha = .3f
        panel.animate().translationY(0f).alpha(1f).setDuration(250).start()
    }
    private fun selectStateSlot(saving: Boolean) {
        val game = activeGame ?: return
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),dp(20)) }
        val group = ui.section(body,if (saving) "Save States" else "Load States")
        for (slot in 0..4) {
            val file = File(game.file.absolutePath+".state$slot")
            val label = if (file.exists()) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(file.lastModified())) else "Empty"
            val row = ui.row(group,"Slot ${slot+1}",label,saving || file.exists(),action = if (saving || file.exists()) ({
                fun applyState() {
                    val result = if (saving) EmulatorBridge.saveState(slot) else EmulatorBridge.loadState(slot)
                    if (result == null) { modal?.dismiss(); modal = null; resumeGame(); toast(if (saving) "State Saved" else "State Loaded") }
                    else error("Save State",result)
                }
                if (saving && file.exists()) AlertDialog.Builder(this).setTitle("Replace Save State?")
                    .setNegativeButton("Cancel",null).setPositiveButton("Replace") { _,_ -> applyState() }.show()
                else applyState()
            }) else null)
            row.contentDescription = "Slot ${slot+1}, $label"
        }
        modal = ui.sheet(if (saving) "Save State" else "Load State",body,"Cancel")
        modal?.setOnDismissListener { modal = null }
    }
    private fun cheats(game: LibraryGame): Array<String> = prefs.getString("cheats.${game.id}","").orEmpty()
        .lineSequence().map(String::trim).filter(String::isNotEmpty).toList().toTypedArray()
    private fun showCheats() {
        val game = activeGame ?: return
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(20)) }
        body.addView(ui.text(if (game.core.id == "gba") "GameShark / Code Breaker" else "Game Genie / GameShark",17f))
        val input = EditText(this).apply {
            setText(cheats(game).joinToString("\n")); setTextColor(ui.label)
            hint = "One code per line"; setHintTextColor(ui.secondary)
            minLines = 5; gravity = Gravity.TOP; typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        body.addView(input,LinearLayout.LayoutParams(-1,-2))
        val group = ui.section(body,"Cheat Codes")
        ui.row(group,"Apply Codes") {
            val codes = input.text.toString().uppercase().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val valid = if (game.core.id == "gba") Regex("(?:[0-9A-F]{8} ?[0-9A-F]{8}|[0-9A-F]{8} ?[0-9A-F]{4})")
                else if (game.core.id == "gbc") Regex("(?:[0-9A-F]{8}|[0-9A-F]{3}-[0-9A-F]{3}(?:-[0-9A-F]{3})?)") else Regex("(?:[APZLGITYEOXUKSVN]{6}|[APZLGITYEOXUKSVN]{8}|[0-9A-F]{4}:[0-9A-F]{2})")
            if (codes.size > 128 || codes.any { !valid.matches(it) }) error("Cheat Codes","A code has an invalid format.")
            else {
                val result = EmulatorBridge.setCheats(codes.toTypedArray())
                if (result != null) error("Cheat Codes",result)
                else { prefs.edit().putString("cheats.${game.id}",codes.joinToString("\n")).apply(); modal?.dismiss(); modal = null }
            }
        }
        ui.footer(body,"Leave empty to disable all codes. Code effects depend on the game and region.")
        modal = ui.sheet("Cheat Codes",body,"Cancel")
        modal?.setOnDismissListener { modal = null }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        externalFlow = false
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (requestCode == SELECT_ROM && resultCode == RESULT_OK) {
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(uri)
            io.execute {
                val errors = mutableListOf<String>()
                for (item in uris) try { library.importGame(item) } catch (e: Exception) { errors.add(e.message.orEmpty()) }
                runOnUiThread { if (!isFinishing && !isDestroyed) { showLibrary(); if (errors.isNotEmpty()) error("Import Games",errors.joinToString("\n")) } }
            }
        } else if (requestCode == SELECT_ARTWORK) {
            val game = selectedGame; selectedGame = null
            if (uri != null && game != null) io.execute {
                val failure = runCatching { GameArtwork.import(this,game,uri) }.exceptionOrNull()
                runOnUiThread { if (!isDestroyed) { showLibrary(); if (failure != null) error("Change Artwork",failure.message) } }
            }
        } else if (requestCode == SELECT_SKIN) {
            if (uri != null) io.execute {
                val result = runCatching { SkinPackage.install(this,uri) }
                runOnUiThread { if (!isDestroyed) {
                    result.fold({ emulator?.refreshSkin(); if (activeGame == null) showSettings(); toast(it) },{ error("Controller Skin",it.message) })
                } }
            }
        } else if (requestCode == EXPORT_SCREENSHOT) {
            val image = pendingScreenshot; pendingScreenshot = null
            if (uri != null && image != null) io.execute {
                val failure = runCatching { contentResolver.openOutputStream(uri)?.use {
                    check(image.compress(Bitmap.CompressFormat.PNG,100,it))
                } ?: error("Cannot write screenshot") }.exceptionOrNull()
                image.recycle()
                runOnUiThread { if (!isDestroyed) { if (failure == null) toast("Screenshot Saved") else error("Screenshot",failure.message) } }
            } else image?.recycle()
        }
        updatePlayback()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (activeGame == null && !inSettings && filterText.isBlank()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { swipeStartX = event.x; swipeStartY = event.y; swiping = false }
                MotionEvent.ACTION_MOVE -> if (!swiping && kotlin.math.abs(event.x-swipeStartX) > dp(40) &&
                    kotlin.math.abs(event.x-swipeStartX) > kotlin.math.abs(event.y-swipeStartY)*1.5f) {
                    swiping = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                MotionEvent.ACTION_UP -> if (swiping) {
                    val pages = pageList(library.games()); val index = pages.indexOfFirst { it.first == currentPage }
                    if (pages.size > 1) {
                        val direction = if (event.x < swipeStartX) 1 else -1
                        currentPage = pages[(index+direction+pages.size)%pages.size].first
                        showLibrary(direction)
                    }
                    swiping = false; return true
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            if (swiping) return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun acceptsController(event: android.view.InputEvent): Boolean {
        val selected = prefs.getString("controller","").orEmpty()
        return selected.isEmpty() || event.device?.descriptor == selected
    }
    override fun onKeyDown(keyCode: Int,event: KeyEvent): Boolean {
        if (activeGame != null && keyCode == KeyEvent.KEYCODE_BUTTON_MODE) { handleBack(); return true }
        val mask = EmulatorView.mapPhysicalKey(keyCode)
        if (activeGame != null && !userPaused && mask != 0 && acceptsController(event)) { emulator?.pressKey(mask,true); return true }
        return super.onKeyDown(keyCode,event)
    }
    override fun onKeyUp(keyCode: Int,event: KeyEvent): Boolean {
        val mask = EmulatorView.mapPhysicalKey(keyCode)
        if (activeGame != null && mask != 0 && acceptsController(event)) { emulator?.pressKey(mask,false); return true }
        return super.onKeyUp(keyCode,event)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (activeGame != null && !userPaused && acceptsController(event) &&
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            fun axis(id: Int): Float {
                val value = event.getAxisValue(id)
                return if (kotlin.math.abs(value) > (event.device?.getMotionRange(id,event.source)?.flat ?: .2f)) value else 0f
            }
            val hatX = axis(MotionEvent.AXIS_HAT_X); val hatY = axis(MotionEvent.AXIS_HAT_Y)
            emulator?.setAxes(if (hatX != 0f) hatX else axis(MotionEvent.AXIS_X),if (hatY != 0f) hatY else axis(MotionEvent.AXIS_Y))
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    private fun handleBack() {
        if (activeGame != null) {
            emulator?.selectingHeldButtons = false
            if (pauseOverlay != null) resumeGame() else showPauseMenu()
        } else if (inSettings) showLibrary() else finish()
    }
    @Deprecated("Physical Back compatibility")
    override fun onBackPressed() { handleBack() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (activeGame != null) {
            clearPauseOverlay()
            emulator?.refreshSkin()
            if (userPaused && emulator?.selectingHeldButtons != true) showPauseMenu()
        } else if (inSettings) showSettings() else showLibrary()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page",currentPage); outState.putString("search",filterText)
        activeGame?.let { outState.putString("activeGame",it.id) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        foreground = false; updatePlayback()
        if (activeGame != null) {
            EmulatorBridge.flushSave()?.let { toast(it) }
            EmulatorBridge.saveState(9)?.let { android.util.Log.w("DeltaSave",it) }
        }
        abandonAudioFocus()
        super.onPause()
    }
    override fun onResume() { super.onResume(); foreground = true; updatePlayback() }
    override fun onDestroy() {
        foreground = false; modal?.dismiss(); modal = null
        stopSession(); io.shutdown(); pendingScreenshot?.recycle()
        super.onDestroy()
    }
}

private class SearchGlyph(private val size: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(153,153,158); style = Paint.Style.STROKE; strokeWidth = size*.105f }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.drawCircle(size*.4f,size*.4f,size*.29f,paint)
        canvas.drawLine(size*.62f,size*.62f,size*.94f,size*.94f,paint); canvas.restore()
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
