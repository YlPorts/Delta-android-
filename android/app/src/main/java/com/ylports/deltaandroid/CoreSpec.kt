package com.ylports.deltaandroid

/** All original engines remain on the roadmap; only packaged adapters are playable. */
data class CoreSpec(val id: String, val title: String, val engine: String,
                    val extensions: Set<String>, val library: String?, val maxRomBytes: Long) {
    val playable get() = library != null
    val gameType get() = "com.rileytestut.delta.game.$id"
    companion object {
        val all = listOf(
            CoreSpec("nes", "Nintendo", "Nestopia", setOf("nes"), "libnestopia_libretro.so", 16L shl 20),
            CoreSpec("snes", "Super Nintendo", "Snes9x", setOf("smc", "sfc"), null, 16L shl 20),
            CoreSpec("n64", "Nintendo 64", "mupen64plus", setOf("n64", "z64", "v64"), null, 64L shl 20),
            CoreSpec("gbc", "Game Boy Color", "Gambatte", setOf("gb", "gbc"), "libgambatte_libretro.so", 8L shl 20),
            CoreSpec("gba", "Game Boy Advance", "VBA-M", setOf("gba"), "libvbam_libretro.so", 32L shl 20),
            CoreSpec("ds", "Nintendo DS", "melonDS", setOf("nds"), null, 512L shl 20),
            CoreSpec("genesis", "Sega Genesis", "Genesis Plus GX", setOf("md", "gen", "bin"), null, 16L shl 20)
        )
        fun forExtension(extension: String) = all.firstOrNull { extension in it.extensions }
        fun forId(id: String) = all.firstOrNull { it.id == id }
    }
}
