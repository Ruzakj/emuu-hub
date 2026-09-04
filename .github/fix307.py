from pathlib import Path
p=Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s=p.read_text()
start=s.index('    private fun gameTitle(g: GameEntry): String = gameTitleCache.getOrPut(g.uri) {')
end=s.index('    private fun decodeCoverSampled(', start)
block='''    private fun gameTitle(g: GameEntry): String = gameTitleCache.getOrPut(g.uri) {
        val raw = g.name.substringBeforeLast('.', g.name).trim()
        val folderName = g.folder.trim('/').substringAfterLast('/').trim()
        val direct = directGameFile(Uri.parse(g.uri))
        val sidecar = direct?.parentFile?.let { parent ->
            val base = direct.nameWithoutExtension
            listOf(File(parent, "$base.title.txt"), File(parent, "$base.name.txt"), File(parent, "title.txt"), File(parent, "game.title.txt"))
                .firstOrNull { it.isFile && it.canRead() }
                ?.let { runCatching { it.useLines { lines -> lines.firstOrNull()?.trim() }.orEmpty() }.getOrDefault("") }
                ?.takeIf { it.isNotBlank() }
        }
        if (sidecar != null) return@getOrPut sidecar
        val discLike = g.ext.lowercase() in setOf("iso", "cso", "chd", "bin", "cue", "ecm", "pbp")
        val genericFolders = setOf("ps1", "ps2", "psp", "rom", "roms", "games", "game", "iso", "isos", "disc", "discs", "playstation", "playstation 2")
        val seed = if (discLike && folderName.isNotBlank() && folderName.lowercase() !in genericFolders) folderName else raw
        seed
            .replace(Regex("""(?i)^\\s*(?:sony\\s+)?(?:playstation\\s*2|playstation|ps2|ps1|psx|psp)\\s*[-_:|]+\\s*"""), "")
            .replace(Regex("""(?i)\\[[^]]*]"""), " ")
            .replace(Regex("""(?i)\\([^)]*(?:USA|Europe|EUR|Japan|JPN|Asia|World|PAL|NTSC|En(?:,[A-Za-z]{2})*|Rev(?:ision)? ?[A-Z0-9]*|Disc ?[0-9]+|Disk ?[0-9]+|Beta|Demo)[^)]*\\)"""), " ")
            .replace(Regex("""(?i)\\b(?:SLUS|SLES|SCUS|SCES|SLPS|SLPM|SCPS|ULUS|ULES|UCUS|UCES|NPJH|NPUH|NPUG)[-_ .]?\\d{3,6}\\b"""), " ")
            .replace(Regex("""(?i)\\b(?:USA|EUR|JPN|PAL|NTSC|MULTI\\d*|REPACK|PROPER|RIP|FULL)\\b"""), " ")
            .replace('_', ' ')
            .replace(Regex("""\\s{2,}"""), " ")
            .trim(' ', '-', '_', '.', '[', ']', '(', ')')
            .ifBlank { folderName.takeIf { it.isNotBlank() } ?: raw }
    }

'''
p.write_text(s[:start]+block+s[end:])
