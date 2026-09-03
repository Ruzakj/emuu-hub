from pathlib import Path

p = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = p.read_text()

s = s.replace('import android.graphics.BitmapFactory\n', 'import android.graphics.BitmapFactory\nimport android.graphics.Bitmap\n')
s = s.replace('    private var activeConsoleFilter: String? = null\n', '''    private var activeConsoleFilter: String? = null
    private val consoleHintCache = HashMap<String, String>()
    private val gameTitleCache = HashMap<String, String>()
    private var libraryRenderLimit = 60
''')
s = s.replace('''        if (::library.isInitialized && allLibraryGames.isNotEmpty()) {
            renderLibrary(allLibraryGames, "${allLibraryGames.size} game")
        }
''', '')

old = '''    private fun inferredConsole(g:GameEntry):String=when(g.ext){
        "cso"->"PSP"
        "bin","cue","ecm"->"PS1"
        "gb","gbc","gba"->"GBA"
        "nes"->"NES"
        "sfc","smc"->"SNES"
        "xci","nsp","nro"->"SWITCH"
        "iso","chd"->folderConsoleHint(g) ?: probeIsoTarget(Uri.parse(g.uri)) ?: "DISC"
        in ARCHIVES->"ARCHIVE"
        else->"OTHER"
    }
'''
new = '''    private fun inferredConsole(g:GameEntry):String {
        consoleHintCache[g.uri]?.let { return it }
        val value = when(g.ext){
            "cso"->"PSP"
            "bin","cue","ecm"->"PS1"
            "gb","gbc","gba"->"GBA"
            "nes"->"NES"
            "sfc","smc"->"SNES"
            "xci","nsp","nro"->"SWITCH"
            "iso","chd"->folderConsoleHint(g) ?: probeIsoTarget(Uri.parse(g.uri)) ?: "DISC"
            in ARCHIVES->"ARCHIVE"
            else->"OTHER"
        }
        consoleHintCache[g.uri] = value
        return value
    }
'''
if old not in s:
    raise SystemExit('inferredConsole block not found')
s = s.replace(old, new)

marker = '    private fun sortGames(games:List<GameEntry>)=games.sortedWith(compareBy<GameEntry>{consoleRank(it)}.thenBy{it.name.lowercase()})\n'
if marker not in s:
    raise SystemExit('sortGames marker not found')
addition = '''
    private fun gameTitle(g: GameEntry): String = gameTitleCache.getOrPut(g.uri) {
        val raw = g.name.substringBeforeLast('.', g.name).trim()
        val generic = setOf("game", "disc", "disk", "image", "rom", "dvd", "cd", "track")
        val folderName = g.folder.trim('/').substringAfterLast('/').trim()
        val seed = if (raw.lowercase() in generic && folderName.isNotBlank()) folderName else raw
        seed
            .replace(Regex("(?i)\\[[^]]*(?:SLUS|SLES|SCUS|SCES|ULUS|ULES|NPJH|NPUH|NPUG|USA|EUR|JPN|ASIA|PAL|NTSC)[^]]*]"), " ")
            .replace(Regex("(?i)\\([^)]*(?:USA|Europe|EUR|Japan|JPN|Asia|World|En(?:,[A-Za-z]{2})+|Rev ?[A-Z0-9]*|Disc ?[0-9]+|Disk ?[0-9]+)[^)]*\\)"), " ")
            .replace(Regex("(?i)\\b(?:SLUS|SLES|SCUS|SCES|SLPS|SLPM|ULUS|ULES|UCUS|UCES|NPJH|NPUH|NPUG)[-_ ]?\\d{3,6}\\b"), " ")
            .replace('_', ' ')
            .replace(Regex("\\s+-\\s+(?:PSP|PS2|PSX|PS1)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', '-', '_', '.')
            .ifBlank { raw }
    }

    private fun decodeCoverSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqWidth * 2 || bounds.outHeight / sample > reqHeight * 2) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }.getOrNull()
'''
s = s.replace(marker, marker + addition)

s = s.replace('''        val sorted=sortGames(visible)
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        sorted.chunked(2).forEach{pair->''', '''        val sorted=sortGames(visible)
        val rendered=sorted.take(libraryRenderLimit)
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        rendered.chunked(2).forEach{pair->''')
s = s.replace('''        library.addView(grid)
    }

    private fun buildFilterStrip():View{''', '''        library.addView(grid)
        if(rendered.size<sorted.size){
            val more=Button(this).apply{
                text="SHOW MORE  •  ${sorted.size-rendered.size} REMAINING"
                setOnClickListener{libraryRenderLimit+=60;renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}
            }
            library.addView(more,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)).apply{topMargin=dp(4);bottomMargin=dp(8)})
        }
    }

    private fun buildFilterStrip():View{''')
s = s.replace('else{activeConsoleFilter=if(label=="ALL")null else label;renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}', 'else{activeConsoleFilter=if(label=="ALL")null else label;libraryRenderLimit=60;renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}')
s = s.replace('else->setOnClickListener{activeConsoleFilter=if(activeConsoleFilter==item[0])null else item[0];renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}', 'else->setOnClickListener{activeConsoleFilter=if(activeConsoleFilter==item[0])null else item[0];libraryRenderLimit=60;renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}')
s = s.replace('val bmp=runCatching{BitmapFactory.decodeFile(cover.absolutePath)}.getOrNull()', 'val bmp=decodeCoverSampled(cover,dp(180),height)')
s = s.replace("g.name.substringBeforeLast('.',g.name).take(42)", 'gameTitle(g).take(42)')
s = s.replace("g.name.substringBeforeLast('.',g.name),12f", 'gameTitle(g),12f')
s = s.replace("g.name.substringBeforeLast('.',g.name),10.5f", 'gameTitle(g),10.5f')

p.write_text(s)
