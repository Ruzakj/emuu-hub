from pathlib import Path
import re

p=Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s=p.read_text()

# Track full library so console cards can filter without rescanning.
s=s.replace('    private var pendingArchiveSession: File? = null\n','    private var pendingArchiveSession: File? = null\n    private var allLibraryGames: List<GameEntry> = emptyList()\n    private var activeConsoleFilter: String? = null\n')

# Preserve nested folder names for disc-console inference.
s=s.replace('    private fun collectGames(dir:DocumentFile,out:MutableList<GameEntry>,limit:Int,folder:String){if(out.size>=limit)return;for(f in runCatching{dir.listFiles()}.getOrDefault(emptyArray())){if(out.size>=limit)return;if(f.isDirectory)collectGames(f,out,limit,folder) else{val n=f.name?:continue;val e=extension(n);if(e in RECOGNIZED)out.add(GameEntry(f.uri.toString(),n,e,folder))}}}\n', '''    private fun collectGames(dir:DocumentFile,out:MutableList<GameEntry>,limit:Int,folder:String){
        if(out.size>=limit)return
        for(f in runCatching{dir.listFiles()}.getOrDefault(emptyArray())){
            if(out.size>=limit)return
            if(f.isDirectory){
                val child=if(folder.isBlank())(f.name?:"") else "$folder/${f.name?:""}"
                collectGames(f,out,limit,child)
            }else{
                val n=f.name?:continue;val e=extension(n)
                if(e in RECOGNIZED)out.add(GameEntry(f.uri.toString(),n,e,folder))
            }
        }
    }
''')

old='''    private fun consoleRank(e:String)=when(e){"cso"->0;"bin","cue","ecm"->1;"iso","chd"->2;"gb","gbc","gba"->3;"nes"->4;"sfc","smc"->5;"xci","nsp","nro"->6;in ARCHIVES->7;else->99}
    private fun consoleGroup(e:String)=when(e){"cso"->"PSP • PPSSPP";"bin","cue","ecm"->"PLAYSTATION • PCSX-REARMED";"iso","chd"->"DISC IMAGE • PS1 / PSP / PS2";"gb","gbc","gba"->"GAME BOY • MGBA";"nes"->"NES • FCEUMM";"sfc","smc"->"SNES • SNES9X";"xci","nsp","nro"->"NINTENDO SWITCH • EDEN";in ARCHIVES->"COMPRESSED ROMS • AUTO TEMP";else->"OTHER"}
    private fun sortGames(games:List<GameEntry>)=games.sortedWith(compareBy<GameEntry>{consoleRank(it.ext)}.thenBy{it.name.lowercase()})
'''
new='''    private fun pathHint(g:GameEntry)=(g.folder+"/"+g.name).lowercase()
    private fun inferredConsole(g:GameEntry):String=when(g.ext){
        "cso"->"PSP"
        "bin","cue","ecm"->"PS1"
        "gb","gbc","gba"->"GBA"
        "nes"->"NES"
        "sfc","smc"->"SNES"
        "xci","nsp","nro"->"SWITCH"
        "iso","chd"->{
            val h=pathHint(g)
            when{
                listOf("ps2","playstation 2","pcsx2","armsx2").any{h.contains(it)}->"PS2"
                listOf("psp","playstation portable").any{h.contains(it)}->"PSP"
                listOf("ps1","psx","psone","playstation 1").any{h.contains(it)}->"PS1"
                else->"DISC"
            }
        }
        in ARCHIVES->"ARCHIVE"
        else->"OTHER"
    }
    private fun consoleRank(g:GameEntry)=when(inferredConsole(g)){"PSP"->0;"PS1"->1;"PS2"->2;"GBA"->3;"NES"->4;"SNES"->5;"SWITCH"->6;"DISC"->7;"ARCHIVE"->8;else->99}
    private fun consoleGroup(g:GameEntry)=when(inferredConsole(g)){
        "PSP"->"PSP • PPSSPP"
        "PS1"->"PLAYSTATION • PCSX-REARMED"
        "PS2"->"PLAYSTATION 2 • ARMSX2"
        "GBA"->"GAME BOY • MGBA"
        "NES"->"NES • FCEUMM"
        "SNES"->"SNES • SNES9X"
        "SWITCH"->"NINTENDO SWITCH • EDEN"
        "DISC"->"DISC IMAGE • AUTO DETECT"
        "ARCHIVE"->"COMPRESSED ROMS • AUTO TEMP"
        else->"OTHER"
    }
    private fun sortGames(games:List<GameEntry>)=games.sortedWith(compareBy<GameEntry>{consoleRank(it)}.thenBy{it.name.lowercase()})
'''
if old not in s: raise SystemExit('grouping block not found')
s=s.replace(old,new)

# Replace console strip so console cards act as filters; JAVA still opens its dedicated direct library.
pat=re.compile(r'    private fun buildConsoleStrip\(\):View\{.*?\n    \}\n\n    private fun showEmptyLibrary',re.S)
repl='''    private fun buildConsoleStrip():View{
        val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val consoles=listOf(arrayOf("PSP","PPSSPP","P"),arrayOf("PS1","PCSX","1"),arrayOf("PS2","ARMSX2","2"),arrayOf("GBA","mGBA","G"),arrayOf("NES","FCEUmm","N"),arrayOf("SNES","Snes9x","S"),arrayOf("JAVA","JL-Mod","J"),arrayOf("SWITCH","Eden","▰"))
        consoles.forEachIndexed{index,item->
            val count=if(item[0]=="JAVA") null else allLibraryGames.count{inferredConsole(it)==item[0]}
            val chip=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(11),dp(8),dp(11),dp(8));background=rounded(if(activeConsoleFilter==item[0])0xFF182232.toInt() else if(index==0)0xFF151B24.toInt() else 0xFF0C1016.toInt(),19,if(activeConsoleFilter==item[0])0xFF57759E.toInt() else if(index==0)0xFF364152.toInt() else 0xFF202630.toInt());isClickable=true;isFocusable=true
                val icon=textView(item[2],15f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(if(activeConsoleFilter==item[0])0xFF2A3D58.toInt() else 0xFF171C24.toInt(),13)}
                addView(icon,LinearLayout.LayoutParams(dp(36),dp(36)))
                addView(textView(item[0],11f,0xFFF7F8FA.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})
                addView(textView(if(count==null)item[1] else "${count} game",8.5f,0xFF6F7988.toInt()).apply{maxLines=1},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(1)})
                when(item[0]){
                    "JAVA"->setOnClickListener{startActivity(Intent(this@MainActivity,J2meLibraryActivity::class.java))}
                    else->setOnClickListener{toggleConsoleFilter(item[0])}
                }
            }
            row.addView(chip,LinearLayout.LayoutParams(dp(86),dp(90)).apply{if(index>0)leftMargin=dp(8)})
        }
        hsv.addView(row,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(90)));return hsv
    }

    private fun toggleConsoleFilter(console:String){
        activeConsoleFilter=if(activeConsoleFilter==console)null else console
        if(allLibraryGames.isEmpty())return
        renderLibrary(allLibraryGames,if(activeConsoleFilter==null)"${allLibraryGames.size} game" else "$console library")
    }

    private fun showEmptyLibrary'''
s,n=pat.subn(repl,s,count=1)
if n!=1: raise SystemExit('console strip replacement failed')

# Library rendering now groups on inferred console and supports active filter.
pat=re.compile(r'    private fun renderLibrary\(games:List<GameEntry>,label:String\)\{.*?\n    \}\n\n    private fun addGameCard',re.S)
repl='''    private fun renderLibrary(games:List<GameEntry>,label:String){
        allLibraryGames=games
        library.removeAllViews()
        val visible=activeConsoleFilter?.let{f->games.filter{inferredConsole(it)==f}}?:games
        countBadge.text="${visible.size} GAMES"
        status.text=if(activeConsoleFilter==null)"$label • auto grouped by console" else "${activeConsoleFilter} • ${visible.size} game • tap console again for all"
        if(visible.isEmpty()){
            val empty=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(22),dp(20),dp(22));background=rounded(0xFF0D1117.toInt(),20,0xFF202630.toInt())}
            empty.addView(textView("No ${activeConsoleFilter?:""} games here",15f,0xFFF6F7F9.toInt(),true));empty.addView(textView("Tap the same console card to return to the full library.",10f,0xFF747E8D.toInt()).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)});library.addView(empty);return
        }
        val sorted=sortGames(visible);var lastGroup=""
        sorted.forEach{g->
            val group=consoleGroup(g)
            if(group!=lastGroup){
                if(lastGroup.isNotEmpty())library.addView(View(this).apply{setBackgroundColor(0xFF171D26.toInt())},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)).apply{topMargin=dp(8);bottomMargin=dp(14)})
                val gamesInGroup=sorted.count{consoleGroup(it)==group}
                val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
                header.addView(textView(group,11f,0xFFB9C2CF.toInt(),true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                header.addView(textView("$gamesInGroup",10f,0xFF697586.toInt(),true))
                library.addView(header,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(9)})
                lastGroup=group
            }
            addGameCard(g)
        }
    }

    private fun addGameCard'''
s,n=pat.subn(repl,s,count=1)
if n!=1: raise SystemExit('renderLibrary replacement failed')

# Replace addGameCard metadata + launch to use inferred console.
s=s.replace('val badge=FrameLayout(this).apply{background=rounded(systemColor(g.ext),16)}','val badge=FrameLayout(this).apply{background=rounded(systemColorFor(g),16)}')
s=s.replace('badge.addView(textView(systemCode(g.ext),11f,0xFFFFFFFF.toInt(),true)','badge.addView(textView(systemCodeFor(g),11f,0xFFFFFFFF.toInt(),true)')
s=s.replace('info.addView(textView(systemName(g.ext),10.5f,0xFF8B8B8B.toInt())','info.addView(textView(systemNameFor(g),10.5f,0xFF8B8B8B.toInt())')
s=s.replace('row.setOnClickListener{openLibraryGame(Uri.parse(g.uri),g.name,g.ext)}','row.setOnClickListener{openLibraryGame(g)}')

# Inject inferred display helpers and automatic disc probe before existing openLibraryGame.
anchor='    private fun openLibraryGame(uri:Uri,name:String,ext:String){'
if anchor not in s: raise SystemExit('openLibraryGame anchor missing')
helpers=r'''    private fun systemCodeFor(g:GameEntry)=when(inferredConsole(g)){"PSP"->"PSP";"PS1"->"PS1";"PS2"->"PS2";"GBA"->"GBA";"NES"->"NES";"SNES"->"SNES";"SWITCH"->"NSW";"DISC"->"DISC";"ARCHIVE"->g.ext.uppercase().take(4);else->systemCode(g.ext)}
    private fun systemNameFor(g:GameEntry)=when(inferredConsole(g)){"PSP"->"PSP • PPSSPP";"PS1"->"PlayStation • PCSX-ReARMed";"PS2"->"PlayStation 2 • ARMSX2 Vulkan";"GBA"->"Game Boy • mGBA";"NES"->"NES • FCEUmm";"SNES"->"SNES • Snes9x";"SWITCH"->"Nintendo Switch • Eden";"DISC"->"Disc image • auto detect on launch";else->systemName(g.ext)}
    private fun systemColorFor(g:GameEntry)=when(inferredConsole(g)){"PSP"->0xFF243C4B.toInt();"PS1"->0xFF3A3347.toInt();"PS2"->0xFF3B2D46.toInt();"GBA"->0xFF2D4138.toInt();"NES"->0xFF493237.toInt();"SNES"->0xFF39364A.toInt();"SWITCH"->0xFF243847.toInt();else->systemColor(g.ext)}

    private fun probeIsoTarget(uri:Uri):String?{
        val file=directGameFile(uri)?:return null
        if(!file.extension.equals("iso",true))return null
        return runCatching{
            file.inputStream().buffered().use{input->
                val buf=ByteArray(256*1024);var total=0;val max=12*1024*1024;val tail=StringBuilder()
                while(total<max){
                    val n=input.read(buf);if(n<=0)break;total+=n
                    val chunk=String(buf,0,n,Charsets.ISO_8859_1).uppercase()
                    tail.append(chunk)
                    if(tail.length>700000)tail.delete(0,tail.length-700000)
                    val t=tail.toString()
                    if(t.contains("PSP_GAME")||t.contains("UMD_DATA.BIN"))return@runCatching "PSP"
                    if(t.contains("BOOT2")||t.contains("CDROM0:"))return@runCatching "PS2"
                    if(t.contains("BOOT = CDROM:")||t.contains("BOOT=CDROM:"))return@runCatching "PS1"
                }
                null
            }
        }.getOrNull()
    }

    private fun openLibraryGame(g:GameEntry){
        val uri=Uri.parse(g.uri)
        when(val target=inferredConsole(g)){
            "PSP"->{writePspResolution(prefs.getString(KEY_PSP_RESOLUTION,"960x544")?:"960x544");copyAndLaunchInternal(uri,g.name,g.ext,"ppsspp")}
            "PS1"->copyAndLaunchInternal(uri,g.name,g.ext,"pcsx")
            "PS2"->launchPs2OrSetup(uri,g.name)
            "GBA","NES","SNES"->copyAndLaunchInternal(uri,g.name,g.ext,null)
            "SWITCH"->launchEden(uri)
            "ARCHIVE"->openArchive(uri,g.name)
            "DISC"->{
                when(probeIsoTarget(uri)){
                    "PSP"->{status.text="Detected PSP disc • PPSSPP";writePspResolution(prefs.getString(KEY_PSP_RESOLUTION,"960x544")?:"960x544");copyAndLaunchInternal(uri,g.name,g.ext,"ppsspp")}
                    "PS2"->{status.text="Detected PS2 disc • ARMSX2";launchPs2OrSetup(uri,g.name)}
                    "PS1"->{status.text="Detected PS1 disc • PCSX-ReARMed";copyAndLaunchInternal(uri,g.name,g.ext,"pcsx")}
                    else->{status.text="Disc type ambiguous • choose once";if(g.ext=="chd")showChdChooser(uri,g.name) else showIsoChooser(uri,g.name)}
                }
            }
            else->openLibraryGame(uri,g.name,g.ext)
        }
    }

'''
s=s.replace(anchor,helpers+anchor)

# Hero text to make auto organization behavior obvious.
s=s.replace('hero.addView(textView("Your games. Instantly.",25f,0xFFFFFFFF.toInt(),true)','hero.addView(textView("Pick a game. We pick the engine.",23f,0xFFFFFFFF.toInt(),true)')
s=s.replace('status=textView("Offline engines ready • choose a console or game",11f,0xFF8E98A8.toInt())','status=textView("Auto grouping • auto engine routing • fully offline",11f,0xFF8E98A8.toInt())')
s=s.replace('sectionTitle("CHOOSE A CONSOLE")','sectionTitle("BROWSE BY CONSOLE")')

p.write_text(s)
print('patched MainActivity.kt')
