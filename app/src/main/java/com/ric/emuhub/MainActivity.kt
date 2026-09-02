package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.ric.emuhub.core.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROM = 1001
        private const val REQUEST_FOLDER = 1002
        private const val REQUEST_ARCHIVE_GAME = 1003
        private const val PREFS = "emuhub_library"
        private const val KEY_ROM_TREE_LEGACY = "rom_tree"
        private const val KEY_ROM_TREES = "rom_trees"
        private const val KEY_LIBRARY_CACHE = "library_cache_v2"
        private const val KEY_PSP_RESOLUTION = "psp_resolution"
        private const val KEY_RECENT_PLAYED = "recent_played_v1"

        private val INTERNAL = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm")
        private val SWITCH = setOf("xci","nsp","nro")
        private val ARCHIVES = ArchiveHelper.ARCHIVE_EXTENSIONS
        private val RECOGNIZED = INTERNAL + SWITCH + ARCHIVES
        private val EDEN_PACKAGES = listOf("com.miHoYo.Yuanshen","com.miHoYo.Yunashen","com.miHoYo.Yuanshen.nightly","dev.eden.eden_emulator","dev.eden.eden_nightly")
        private val PSP_RES_VALUES = arrayOf("480x272","960x544")
        private val PSP_RES_LABELS = arrayOf("1× • 480×272 • Performance","2× • 960×544 • Recommended")
    }

    data class GameEntry(val uri:String,val name:String,val ext:String,val folder:String="")

    private lateinit var library: LinearLayout
    private lateinit var status: TextView
    private lateinit var countBadge: TextView
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val scanExecutor = Executors.newFixedThreadPool(3)
    private var pendingArchiveSession: File? = null
    private var pendingPs2ArchiveRomPath: String? = null
    private var pendingPs2ArchiveRomName: String? = null
    private var allLibraryGames: List<GameEntry> = emptyList()
    private var activeConsoleFilter: String? = null

    private fun dp(v:Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArchiveHelper.cleanupStale(cacheDir)
        ensureDefaultPspResolution()
        migrateLegacyFolder()
        renderHome()
        val cached = loadCache()
        if (cached.isNotEmpty()) renderLibrary(cached, "${cached.size} game • cache") else showEmptyLibrary()
        refreshAllFolders(false)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            val bios = Ps2BiosActivity.selectedBios(this)
            if (bios != null && status.text.toString().startsWith("PS2 BIOS")) status.text = "PS2 BIOS ready • ${bios.name}"
            if (bios != null) resumePendingCompressedPs2()
        }
        if (::library.isInitialized && allLibraryGames.isNotEmpty()) {
            renderLibrary(allLibraryGames, "${allLibraryGames.size} game")
        }
    }

    private fun ensureDefaultPspResolution(){
        val value=prefs.getString(KEY_PSP_RESOLUTION,null)
        if(value !in PSP_RES_VALUES){prefs.edit().putString(KEY_PSP_RESOLUTION,"960x544").apply();writePspResolution("960x544")} else writePspResolution(value!!)
    }

    private fun writePspResolution(value:String){
        val safe=if(value in PSP_RES_VALUES)value else "960x544"
        val root=File(filesDir,"system").apply{mkdirs()}
        File(root,"ppsspp_resolution.cfg").writeText(safe)
    }

    private fun showPspResolutionChooser(uri:Uri,name:String,ext:String){
        val current=prefs.getString(KEY_PSP_RESOLUTION,"960x544")?:"960x544"
        val checked=PSP_RES_VALUES.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("PSP Performance Profile").setSingleChoiceItems(PSP_RES_LABELS,checked){dialog,which->
            val value=PSP_RES_VALUES[which];prefs.edit().putString(KEY_PSP_RESOLUTION,value).apply();writePspResolution(value);dialog.dismiss();copyAndLaunchInternal(uri,name,ext,"ppsspp")
        }.setNegativeButton("Batal",null).show()
    }

    private fun migrateLegacyFolder(){
        val old=prefs.getString(KEY_ROM_TREE_LEGACY,null)?:return
        val set=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toMutableSet()?:mutableSetOf()
        if(set.add(old)) prefs.edit().putStringSet(KEY_ROM_TREES,set).remove(KEY_ROM_TREE_LEGACY).apply() else prefs.edit().remove(KEY_ROM_TREE_LEGACY).apply()
    }

    private fun rounded(color:Int,radiusDp:Int,strokeColor:Int?=null,strokeDp:Int=1)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(radiusDp).toFloat();if(strokeColor!=null)setStroke(dp(strokeDp),strokeColor)
    }

    private fun textView(text:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply{
        this.text=text;textSize=size;setTextColor(color);includeFontPadding=false;if(bold)setTypeface(typeface,Typeface.BOLD)
    }

    private fun renderHome(){
        window.statusBarColor=0xFF030406.toInt();window.navigationBarColor=0xFF030406.toInt()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF030406.toInt())}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER}
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(32))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        brand.addView(textView("EMU HUB",25f,0xFFFFFFFF.toInt(),true).apply{letterSpacing=0.05f})
        brand.addView(textView("CONSOLE HOME",9f,0xFF788291.toInt(),true).apply{letterSpacing=0.19f},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)})
        header.addView(brand,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        header.addView(textView("● ONLINE",9f,0xFF8FF0B5.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF0A1610.toInt(),15,0xFF244D35.toInt());setPadding(dp(11),dp(7),dp(11),dp(7))})
        content.addView(header)

        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(19),dp(18),dp(19),dp(18));background=rounded(0xFF101722.toInt(),27,0xFF26354A.toInt())}
        content.addView(hero,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(15)})
        val heroTop=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroTop.addView(textView("READY TO PLAY",9.5f,0xFF8EA2BE.toInt(),true).apply{letterSpacing=0.16f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        countBadge=textView("0 GAMES",9.5f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF1B2635.toInt(),13,0xFF344861.toInt());setPadding(dp(10),dp(6),dp(10),dp(6))}
        heroTop.addView(countBadge);hero.addView(heroTop)
        hero.addView(textView("Your games. One console.",24f,0xFFFFFFFF.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(10)})
        status=textView("Select a title — Emu Hub routes the right engine automatically",10.5f,0xFF91A0B3.toInt())
        hero.addView(status,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})
        val heroActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroActions.addView(consoleAction("＋","ADD GAMES") { chooseRomFolder() },LinearLayout.LayoutParams(0,dp(46),1f).apply{rightMargin=dp(5)})
        heroActions.addView(consoleAction("▶","PLAY FILE") { openRomPicker() },LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(5)})
        hero.addView(heroActions,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46)).apply{topMargin=dp(16)})

        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5));background=rounded(0xFF090D13.toInt(),20,0xFF1D2632.toInt())}
        nav.addView(consoleNav("↻","SCAN","Library") { refreshAllFolders(true) },LinearLayout.LayoutParams(0,dp(64),1f))
        nav.addView(consoleNav("⚙","PS2","Tuning") { startActivity(Intent(this@MainActivity,Ps2SettingsActivity::class.java)) },LinearLayout.LayoutParams(0,dp(64),1f))
        nav.addView(consoleNav("⇩","UPDATE","System") { startActivity(Intent(this@MainActivity,UpdateActivity::class.java)) },LinearLayout.LayoutParams(0,dp(64),1f))
        content.addView(nav,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(74)).apply{topMargin=dp(11)})

        val consoleHead=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        consoleHead.addView(sectionTitle("CONSOLES"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));consoleHead.addView(textView("CHOOSE SYSTEM",8.5f,0xFF586474.toInt(),true))
        content.addView(consoleHead,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(22)})
        content.addView(buildConsoleStrip(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(94)).apply{topMargin=dp(10)})

        val libraryHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        libraryHeader.addView(sectionTitle("GAME SHELF"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));libraryHeader.addView(textView("RECENT + ALL",8.5f,0xFF667385.toInt(),true))
        content.addView(libraryHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(22)})
        library=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,0)};content.addView(library)
        scroll.addView(content,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)
    }

    private fun consoleAction(icon:String,title:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);background=rounded(0xFFF3F6FA.toInt(),15);isClickable=true;isFocusable=true;addView(textView(icon,15f,0xFF05070A.toInt(),true));addView(textView(title,10f,0xFF05070A.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{leftMargin=dp(7)});setOnClickListener{onClick()}}

    private fun consoleNav(icon:String,title:String,subtitle:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;addView(textView(icon,17f,0xFFF6F8FB.toInt(),true).apply{gravity=Gravity.CENTER});addView(textView(title,9.5f,0xFFF5F7FA.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(3)});addView(textView(subtitle,8f,0xFF697687.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(1)});setOnClickListener{onClick()}}

    private fun sectionTitle(label:String)=textView(label,10.5f,0xFF8D96A5.toInt(),true).apply{letterSpacing=0.14f}
    private fun compactAction(icon:String,title:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);background=rounded(0xFFFFFFFF.toInt(),14);isClickable=true;isFocusable=true;addView(textView(icon,15f,0xFF080A0E.toInt(),true));addView(textView(title,10f,0xFF080A0E.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{leftMargin=dp(7)});setOnClickListener{onClick()}}
    private fun miniTile(icon:String,title:String,subtitle:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(13),dp(10),dp(12),dp(10));background=rounded(0xFF0D1117.toInt(),18,0xFF202630.toInt());isClickable=true;isFocusable=true;val mark=textView(icon,14f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF171D26.toInt(),12,0xFF28313D.toInt())};addView(mark,LinearLayout.LayoutParams(dp(38),dp(38)).apply{rightMargin=dp(10)});val box=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(textView(title,11f,0xFFF5F7FA.toInt(),true));addView(textView(subtitle,9f,0xFF707A89.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)})};addView(box,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));setOnClickListener{onClick()}}

    private fun buildConsoleStrip():View{
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

    private fun showEmptyLibrary(){
        library.removeAllViews();countBadge.text="0 GAMES";status.text="Add a ROM folder to build your hub"
        val empty=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(24),dp(20),dp(24));background=rounded(0xFF0D1117.toInt(),20,0xFF202630.toInt())}
        empty.addView(textView("＋",24f,0xFF8D96A5.toInt(),true));empty.addView(textView("Build your game shelf",16f,0xFFF6F7F9.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(7)});empty.addView(textView("Add one ROM folder. Emu Hub will sort everything by console automatically.",10.5f,0xFF747E8D.toInt()).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)});library.addView(empty,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun chooseRomFolder(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_FOLDER)}
    private fun openRomPicker(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},REQUEST_ROM)}

    private fun refreshAllFolders(userRequested:Boolean){
        val trees=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toSet().orEmpty();if(trees.isEmpty()){if(userRequested)Toast.makeText(this,"Belum ada folder ROM.",Toast.LENGTH_SHORT).show();return}
        status.text=if(userRequested)"Refreshing ${trees.size} folder..." else "Library ready • background scan"
        val pending=AtomicInteger(trees.size);val merged=java.util.Collections.synchronizedList(mutableListOf<GameEntry>())
        trees.forEach{raw->scanExecutor.execute{val root=DocumentFile.fromTreeUri(this,Uri.parse(raw));if(root!=null)collectGames(root,merged,1200,root.name?:"ROM");if(pending.decrementAndGet()==0){val unique=sortGames(merged.distinctBy{it.uri});saveCache(unique);runOnUiThread{if(unique.isEmpty())showEmptyLibrary() else renderLibrary(unique,"${unique.size} game • ${trees.size} folder")}}}}
    }

    private fun collectGames(dir:DocumentFile,out:MutableList<GameEntry>,limit:Int,folder:String){
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

    private fun pathHint(g:GameEntry)=(g.folder+"/"+g.name).lowercase()
    private fun folderTokens(g:GameEntry)=g.folder.lowercase().split('/', '\\', ' ', '-', '_', '.', '(', ')', '[', ']').filter{it.isNotBlank()}.toSet()
    private fun folderConsoleHint(g:GameEntry):String?{
        val t=folderTokens(g); val h=g.folder.lowercase()
        return when{
            "ps2" in t || "pcsx2" in t || "armsx2" in t || h.contains("playstation 2")->"PS2"
            "psp" in t || h.contains("playstation portable")->"PSP"
            "ps1" in t || "psx" in t || "psone" in t || h.contains("playstation 1")->"PS1"
            else->null
        }
    }
    private fun inferredConsole(g:GameEntry):String=when(g.ext){
        "cso"->"PSP"
        "bin","cue","ecm"->"PS1"
        "gb","gbc","gba"->"GBA"
        "nes"->"NES"
        "sfc","smc"->"SNES"
        "xci","nsp","nro"->"SWITCH"
        "iso","chd"->folderConsoleHint(g) ?: probeIsoTarget(Uri.parse(g.uri)) ?: "DISC"
        in ARCHIVES->folderConsoleHint(g) ?: "ARCHIVE"
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

    private fun renderLibrary(games:List<GameEntry>,label:String){
        allLibraryGames=games
        library.removeAllViews()
        val visible=activeConsoleFilter?.let{f->games.filter{inferredConsole(it)==f}}?:games
        countBadge.text="${visible.size} GAMES"
        status.text=if(activeConsoleFilter==null)"$label • tap a game and Emu Hub picks the engine" else "${activeConsoleFilter} • ${visible.size} game"

        val recent=loadRecentlyPlayed().mapNotNull{r->games.firstOrNull{it.uri==r.uri}?:r}.distinctBy{it.uri}.take(10)
        if(recent.isNotEmpty()){
            val recentHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            recentHeader.addView(textView("RECENTLY PLAYED",10.5f,0xFFB9C3D1.toInt(),true).apply{letterSpacing=0.12f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            recentHeader.addView(textView("CONTINUE",9f,0xFF697586.toInt(),true))
            library.addView(recentHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(9)})
            val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false}
            val shelf=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            recent.forEachIndexed{i,g->shelf.addView(buildRecentCard(g),LinearLayout.LayoutParams(dp(128),dp(174)).apply{if(i>0)leftMargin=dp(9)})}
            hsv.addView(shelf,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(174)))
            library.addView(hsv,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(174)))
        }

        val filtersHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        filtersHeader.addView(textView("GAME LIBRARY",10.5f,0xFFB9C3D1.toInt(),true).apply{letterSpacing=0.12f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        filtersHeader.addView(textView("${games.size} TOTAL",9f,0xFF697586.toInt(),true))
        library.addView(filtersHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=if(recent.isNotEmpty())dp(22) else 0;bottomMargin=dp(9)})
        library.addView(buildFilterStrip(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(42)).apply{bottomMargin=dp(12)})

        if(visible.isEmpty()){
            val empty=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(26),dp(20),dp(26));background=rounded(0xFF0D1117.toInt(),20,0xFF202630.toInt())}
            empty.addView(textView("No ${activeConsoleFilter?:""} games",16f,0xFFF6F7F9.toInt(),true))
            empty.addView(textView("Choose ALL or another console filter.",10.5f,0xFF747E8D.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)})
            library.addView(empty);return
        }

        val sorted=sortGames(visible)
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        sorted.chunked(2).forEach{pair->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP}
            pair.forEachIndexed{idx,g->
                row.addView(buildGameTile(g),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{
                    if(idx==0)rightMargin=dp(5) else leftMargin=dp(5)
                })
            }
            if(pair.size==1)row.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply{leftMargin=dp(5)})
            grid.addView(row,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)})
        }
        library.addView(grid)
    }

    private fun buildFilterStrip():View{
        val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val filters=listOf("ALL","PSP","PS1","PS2","GBA","NES","SNES","SWITCH","JAVA")
        filters.forEachIndexed{i,label->
            val selected=(label=="ALL"&&activeConsoleFilter==null)||activeConsoleFilter==label
            val chip=textView(label,9.5f,if(selected)0xFF071018.toInt() else 0xFFB2BCC9.toInt(),true).apply{
                gravity=Gravity.CENTER
                background=rounded(if(selected)0xFFE7F0FA.toInt() else 0xFF0D1219.toInt(),13,if(selected)0xFFE7F0FA.toInt() else 0xFF242C37.toInt())
                setPadding(dp(15),0,dp(15),0);isClickable=true;isFocusable=true
                setOnClickListener{
                    if(label=="JAVA")startActivity(Intent(this@MainActivity,J2meLibraryActivity::class.java))
                    else{activeConsoleFilter=if(label=="ALL")null else label;renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}
                }
            }
            row.addView(chip,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)).apply{if(i>0)leftMargin=dp(7)})
        }
        hsv.addView(row,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)));return hsv
    }

    private fun localCoverFile(g:GameEntry):File?{
        val rom=directGameFile(Uri.parse(g.uri))?:return null
        val base=rom.nameWithoutExtension
        val parent=rom.parentFile?:return null
        val artDirs=listOf(parent,File(parent,"covers"),File(parent,"cover"),File(parent,"boxart"),File(parent,"boxarts"),File(parent,"art"),File(parent,"images"),File(parent,"thumbnails"))
        val names=listOf(base,"$base-front","$base-cover","cover","front","boxart","folder","thumbnail","thumb")
        val imageExts=listOf("jpg","jpeg","png","webp")
        for(dir in artDirs)for(n in names)for(e in imageExts){
            val f=File(dir,"$n.$e")
            if(f.isFile&&f.canRead())return f
        }
        return null
    }

    private fun consoleGlyph(g:GameEntry)=when(inferredConsole(g)){
        "PSP"->"P";"PS1"->"1";"PS2"->"2";"GBA"->"G";"NES"->"N";"SNES"->"S";"SWITCH"->"▰";"DISC"->"◎";"ARCHIVE"->"◆";else->"•"
    }

    private fun coverView(g:GameEntry,height:Int):View{
        val frame=FrameLayout(this).apply{background=rounded(systemColorFor(g),18);clipToOutline=true}
        val cover=localCoverFile(g)
        if(cover!=null){
            val bmp=runCatching{BitmapFactory.decodeFile(cover.absolutePath)}.getOrNull()
            if(bmp!=null)frame.addView(ImageView(this).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if(frame.childCount==0){
            frame.addView(textView(consoleGlyph(g),34f,0x44FFFFFF,true).apply{gravity=Gravity.TOP or Gravity.END;setPadding(0,dp(8),dp(12),0)},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(textView(systemCodeFor(g),10f,0xFFDCE7F2.toInt(),true).apply{gravity=Gravity.TOP or Gravity.START;setPadding(dp(12),dp(11),0,0);letterSpacing=0.10f},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(textView(g.name.substringBeforeLast('.',g.name).take(42),17f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.BOTTOM or Gravity.START;setPadding(dp(12),0,dp(10),dp(14));maxLines=3},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val engine=textView(engineLabel(g),8.5f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0x99000000.toInt(),10);setPadding(dp(8),dp(4),dp(8),dp(4))}
        frame.addView(engine,FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP or Gravity.END).apply{topMargin=dp(9);rightMargin=dp(9)})
        return frame
    }

    private fun buildGameTile(g:GameEntry):View{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(10));background=rounded(0xFF0D1117.toInt(),20,0xFF202833.toInt());isClickable=true;isFocusable=true
            addView(coverView(g,dp(142)),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(142)))
            addView(textView(g.name.substringBeforeLast('.',g.name),12f,0xFFF5F7FA.toInt(),true).apply{maxLines=2},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(9)})
            val meta=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            meta.addView(textView(systemCodeFor(g),8.5f,0xFF8E9AAA.toInt(),true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            meta.addView(textView("▶",10f,0xFFFFFFFF.toInt(),true))
            addView(meta,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})
            setOnClickListener{openLibraryGame(g)}
        }
    }

    private fun buildRecentCard(g:GameEntry):View{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(7),dp(7),dp(7),dp(9));background=rounded(0xFF0C1118.toInt(),18,0xFF202833.toInt());isClickable=true;isFocusable=true
            addView(coverView(g,dp(118)),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(118)))
            addView(textView(g.name.substringBeforeLast('.',g.name),10.5f,0xFFF4F6F9.toInt(),true).apply{maxLines=2},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(7)})
            setOnClickListener{openLibraryGame(g)}
        }
    }

    private fun engineLabel(g:GameEntry)=when(inferredConsole(g)){
        "PSP"->"PPSSPP";"PS1"->"PCSX";"PS2"->"ARMSX2";"GBA"->"mGBA";"NES"->"FCEUmm";"SNES"->"Snes9x";"SWITCH"->"EDEN";"DISC"->"AUTO";"ARCHIVE"->"AUTO";else->"CORE"
    }

    private fun rememberPlayed(g:GameEntry){
        val recent=loadRecentlyPlayed().toMutableList()
        recent.removeAll{it.uri==g.uri}
        recent.add(0,g)
        val arr=JSONArray()
        recent.take(12).forEach{r->arr.put(JSONObject().put("u",r.uri).put("n",r.name).put("e",r.ext).put("f",r.folder))}
        prefs.edit().putString(KEY_RECENT_PLAYED,arr.toString()).apply()
    }

    private fun loadRecentlyPlayed():List<GameEntry>{
        return runCatching{
            val arr=JSONArray(prefs.getString(KEY_RECENT_PLAYED,"[]"))
            buildList{for(i in 0 until arr.length()){val o=arr.getJSONObject(i);add(GameEntry(o.getString("u"),o.getString("n"),o.getString("e"),o.optString("f")))}}
        }.getOrDefault(emptyList())
    }

    private fun systemCode(e:String)=when(e){"gb","gbc","gba"->"GBA";"nes"->"NES";"sfc","smc"->"SNES";"bin","cue"->"PS1";"chd"->"CHD";"ecm"->"ECM";"iso"->"ISO";"cso"->"PSP";"xci","nsp","nro"->"NSW";in ARCHIVES->e.uppercase().take(4);else->e.uppercase().take(4)}
    private fun systemColor(e:String)=when(e){"xci","nsp","nro"->0xFF243847.toInt();"bin","cue","chd","iso","ecm"->0xFF3A3347.toInt();"cso"->0xFF243C4B.toInt();"gba","gb","gbc"->0xFF2D4138.toInt();"nes"->0xFF493237.toInt();"sfc","smc"->0xFF39364A.toInt();in ARCHIVES->0xFF3A3A3A.toInt();else->0xFF303030.toInt()}

    private fun systemCodeFor(g:GameEntry)=when(inferredConsole(g)){"PSP"->"PSP";"PS1"->"PS1";"PS2"->"PS2";"GBA"->"GBA";"NES"->"NES";"SNES"->"SNES";"SWITCH"->"NSW";"DISC"->"DISC";"ARCHIVE"->g.ext.uppercase().take(4);else->systemCode(g.ext)}
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
        rememberPlayed(g)
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

    private fun openLibraryGame(uri:Uri,name:String,ext:String){when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);else->copyAndLaunchInternal(uri,name,ext,null)}}
    private fun showIsoChooser(uri:Uri,name:String){AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan")){_,which->when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");else->launchPs2OrSetup(uri,name)}}.setNegativeButton("Batal",null).show()}
    private fun showChdChooser(uri:Uri,name:String){AlertDialog.Builder(this).setTitle("Open CHD with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PlayStation 2 • ARMSX2 Vulkan")){_,which->if(which==0)copyAndLaunchInternal(uri,name,"chd","pcsx") else launchPs2OrSetup(uri,name)}.setNegativeButton("Batal",null).show()}

    private fun launchPs2OrSetup(uri:Uri,name:String){
        if(Ps2BiosActivity.selectedBios(this)==null){
            status.text="PS2 BIOS belum siap"
            AlertDialog.Builder(this).setTitle("PS2 BIOS diperlukan").setMessage("Pilih BIOS PS2 dulu. Setelah status BIOS READY, baru pilih ISO/CHD PS2.").setPositiveButton("SETUP BIOS"){_,_->startActivity(Intent(this,Ps2BiosActivity::class.java))}.setNegativeButton("Batal",null).show()
            return
        }
        copyAndLaunchPs2(uri,name)
    }

    private fun openArchive(uri:Uri,name:String){
        pendingArchiveSession?.deleteRecursively(); pendingArchiveSession=null
        ArchiveHelper.cleanupStale(cacheDir)
        status.text="Opening archive • $name"
        scanExecutor.execute{
            try{
                val session=ArchiveHelper.extract(this,uri,name)
                runOnUiThread{
                    status.text="Archive ready • temporary files"
                    val candidates=archiveGameCandidates(session)
                    if(candidates.size==1)launchExtractedRom(session,candidates.first())
                    else {
                        val labels=candidates.map{rom->"${archiveRomSystemLabel(rom)}  •  ${rom.displayName}"}.toTypedArray()
                        AlertDialog.Builder(this).setTitle("Pilih game dari compressed file").setItems(labels){_,which->launchExtractedRom(session,candidates[which])}.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()
                    }
                }
            }catch(e:Exception){runOnUiThread{status.text="Archive gagal dibuka";Toast.makeText(this,"Archive gagal: ${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }

    private fun archiveGameCandidates(session:ArchiveHelper.Session):List<ArchiveHelper.ExtractedRom>{
        val biosLike=Regex("(?i)(^|[^a-z])(scph|ps2[_ -]?bios|bios[_ -]?ps2|rom0|rom1|erom|dvdrom)([^a-z]|$)")
        val filtered=session.roms.filterNot{rom->
            rom.ext=="bin" && biosLike.containsMatchIn(rom.displayName.substringBeforeLast('.',rom.displayName))
        }
        val base=if(filtered.isNotEmpty())filtered else session.roms
        return base.sortedWith(compareBy<ArchiveHelper.ExtractedRom>{rom->
            when(rom.ext){
                "iso","cso","chd","xci","nsp","nro"->0
                "cue","ecm"->1
                "bin"->2
                else->3
            }
        }.thenByDescending{it.file.length()}.thenBy{it.displayName.lowercase()})
    }

    private fun archiveRomSystemLabel(rom:ArchiveHelper.ExtractedRom):String=when(rom.ext){
        "cso"->"PSP"
        "bin","cue","ecm"->"PS1"
        "gb","gbc","gba"->"GBA"
        "nes"->"NES"
        "sfc","smc"->"SNES"
        "xci","nsp","nro"->"SWITCH"
        "iso"->probeIsoTarget(Uri.fromFile(rom.file)) ?: "DISC"
        "chd"->"DISC"
        else->rom.ext.uppercase()
    }

    private fun launchExtractedRom(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        when(rom.ext){
            "iso"->{
                val detected=probeIsoTarget(Uri.fromFile(rom.file))
                when(detected){
                    "PSP"->{writePspResolution(prefs.getString(KEY_PSP_RESOLUTION,"960x544")?:"960x544");launchTempInternalFile(rom.file,"ppsspp",rom.displayName,session.root)}
                    "PS2"->{
                        if(Ps2BiosActivity.selectedBios(this)!=null)launchExtractedPs2OrSetup(session,rom)
                        else showExtractedIsoChooser(session,rom)
                    }
                    "PS1"->launchTempInternalFile(rom.file,"pcsx",rom.displayName,session.root)
                    else->showExtractedIsoChooser(session,rom)
                }
            }
            "chd"->{
                when(folderConsoleHint(GameEntry(Uri.fromFile(rom.file).toString(),rom.displayName,"chd",rom.file.parentFile?.path.orEmpty()))){
                    "PS2"->launchExtractedPs2OrSetup(session,rom)
                    "PS1"->launchTempInternalFile(rom.file,"pcsx",rom.displayName,session.root)
                    else->showExtractedChdChooser(session,rom)
                }
            }
            "cso"->showExtractedPspResolutionChooser(session,rom)
            "ecm"->decodeExtractedEcm(session,rom)
            else->launchTempInternalFile(rom.file,coreIdFor(rom.ext),rom.displayName,session.root)
        }
    }

    private fun showExtractedIsoChooser(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan")){_,which->
            when(which){
                0->launchTempInternalFile(rom.file,"pcsx",rom.displayName,session.root)
                1->showExtractedPspResolutionChooser(session,rom)
                else->launchExtractedPs2OrSetup(session,rom)
            }
        }.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()
    }

    private fun showExtractedChdChooser(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        AlertDialog.Builder(this).setTitle("Open CHD with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PlayStation 2 • ARMSX2 Vulkan")){_,which->
            if(which==0)launchTempInternalFile(rom.file,"pcsx",rom.displayName,session.root) else launchExtractedPs2OrSetup(session,rom)
        }.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()
    }

    private fun launchExtractedPs2OrSetup(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        if(Ps2BiosActivity.selectedBios(this)==null){
            pendingArchiveSession=session.root
            pendingPs2ArchiveRomPath=rom.file.absolutePath
            pendingPs2ArchiveRomName=rom.displayName
            status.text="PS2 BIOS diperlukan • game akan lanjut otomatis setelah BIOS siap"
            AlertDialog.Builder(this)
                .setTitle("PS2 BIOS diperlukan")
                .setMessage("Game PS2 sudah dipilih dari compressed file. Setup BIOS sekali; saat kembali ke Emu Hub game ini akan langsung dilanjutkan, bukan kembali memilih file.")
                .setPositiveButton("SETUP BIOS"){_,_->startActivity(Intent(this,Ps2BiosActivity::class.java))}
                .setNegativeButton("Batal"){_,_->clearPendingCompressedPs2(true)}
                .setOnCancelListener{clearPendingCompressedPs2(true)}
                .show()
            return
        }
        pendingArchiveSession=session.root
        pendingPs2ArchiveRomPath=null
        pendingPs2ArchiveRomName=null
        startActivityForResult(Intent(this,Ps2GameActivity::class.java).putExtra("romPath",rom.file.absolutePath).putExtra("romName",rom.displayName),REQUEST_ARCHIVE_GAME)
    }

    private fun resumePendingCompressedPs2(){
        val path=pendingPs2ArchiveRomPath?:return
        val root=pendingArchiveSession?:return
        val file=File(path)
        if(!root.exists()||!file.isFile){clearPendingCompressedPs2(true);return}
        val name=pendingPs2ArchiveRomName?:file.name
        pendingPs2ArchiveRomPath=null
        pendingPs2ArchiveRomName=null
        status.text="PS2 BIOS ready • launching $name"
        startActivityForResult(Intent(this,Ps2GameActivity::class.java).putExtra("romPath",file.absolutePath).putExtra("romName",name),REQUEST_ARCHIVE_GAME)
    }

    private fun clearPendingCompressedPs2(deleteSession:Boolean){
        pendingPs2ArchiveRomPath=null
        pendingPs2ArchiveRomName=null
        if(deleteSession){pendingArchiveSession?.deleteRecursively();pendingArchiveSession=null}
    }

    private fun showExtractedPspResolutionChooser(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        val current=prefs.getString(KEY_PSP_RESOLUTION,"960x544")?:"960x544";val checked=PSP_RES_VALUES.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("PSP Performance Profile").setSingleChoiceItems(PSP_RES_LABELS,checked){dialog,which->
            val value=PSP_RES_VALUES[which];prefs.edit().putString(KEY_PSP_RESOLUTION,value).apply();writePspResolution(value);dialog.dismiss();launchTempInternalFile(rom.file,"ppsspp",rom.displayName,session.root)
        }.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()
    }

    private fun decodeExtractedEcm(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        status.text="Decompressing ECM • ${rom.displayName}"
        scanExecutor.execute{
            try{
                val decoded=File(rom.file.parentFile,rom.file.nameWithoutExtension+".bin")
                if(!NativeBridge.decodeEcm(rom.file.absolutePath,decoded.absolutePath))error("ECM corrupt / decode gagal")
                rom.file.delete()
                runOnUiThread{launchTempInternalFile(decoded,"pcsx",decoded.name,session.root)}
            }catch(e:Exception){session.root.deleteRecursively();runOnUiThread{status.text="ECM decode failed";Toast.makeText(this,"ECM gagal dibuka: ${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }

    private fun launchTempInternalFile(file:File,core:String,name:String,sessionRoot:File){
        pendingArchiveSession=sessionRoot
        startActivityForResult(Intent(this,GameActivity::class.java).putExtra("romPath",file.absolutePath).putExtra("coreId",core).putExtra("romName",name),REQUEST_ARCHIVE_GAME)
    }

    private fun launchEden(uri:Uri){
        val pkg=EDEN_PACKAGES.firstOrNull{packageManager.getLaunchIntentForPackage(it)!=null}?:run{Toast.makeText(this,"Eden / Eden Optimized tidak terdeteksi.",Toast.LENGTH_LONG).show();return}
        try{startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,"application/octet-stream");setPackage(pkg);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri("Switch ROM",uri)})}catch(_:ActivityNotFoundException){packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)}catch(_:Exception){packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)}
    }

    private fun displayName(uri:Uri):String?{if(uri.scheme=="content")contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst()){val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i)}};return uri.lastPathSegment?.substringAfterLast('/')}
    private fun extension(name:String?)=name.orEmpty().substringAfterLast('.',"").lowercase()
    private fun systemName(e:String)=when(e){"gb","gbc","gba"->"Game Boy • mGBA";"nes"->"Nintendo Entertainment System • FCEUmm";"sfc","smc"->"Super Nintendo • Snes9x";"bin","cue"->"PlayStation • PCSX-ReARMed";"chd"->"PlayStation / PS2 • choose engine";"ecm"->"PlayStation • ECM auto decode";"iso"->"PS1 / PSP / PS2 • choose engine";"cso"->"PSP • PPSSPP";"xci","nsp","nro"->"Nintendo Switch • Eden Optimized";in ARCHIVES->"Compressed ROM • temporary auto extract";else->"ROM"}
    private fun cacheKey(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}.take(24)

    /** Resolve Storage Access Framework URIs to their real storage path.
     * Normal game images are launched in place; only archives/transform formats use cache. */
    private fun directGameFile(uri:Uri):File?{
        if(uri.scheme=="file")return uri.path?.let(::File)?.takeIf{it.isFile&&it.canRead()}
        if(uri.scheme!="content")return null
        return runCatching{
            if(uri.authority=="com.android.externalstorage.documents"&&DocumentsContract.isDocumentUri(this,uri)){
                val id=DocumentsContract.getDocumentId(uri)
                if(id.startsWith("raw:"))return@runCatching File(id.removePrefix("raw:")).takeIf{it.isFile&&it.canRead()}
                val parts=id.split(':',limit=2)
                if(parts.size==2){
                    val base=if(parts[0].equals("primary",true))Environment.getExternalStorageDirectory() else File("/storage/${parts[0]}")
                    return@runCatching File(base,parts[1]).takeIf{it.isFile&&it.canRead()}
                }
            }
            null
        }.getOrNull()
    }

    private fun launchDirectInternal(uri:Uri,name:String,ext:String,forcedCore:String?){
        val file=directGameFile(uri)
        if(file==null){
            status.text="Direct ROM path unavailable"
            Toast.makeText(this,"ROM harus berada di penyimpanan internal/SD yang bisa diakses langsung. File game tidak akan dicopy ke cache.",Toast.LENGTH_LONG).show()
            return
        }
        status.text="Opening directly • $name"
        launchInternalFile(file,forcedCore?:coreIdFor(ext),name)
    }

    private fun launchDirectPs2(uri:Uri,name:String){
        if(Ps2BiosActivity.selectedBios(this)==null){
            status.text="PS2 BIOS belum siap"
            Toast.makeText(this,"Setup BIOS PS2 dulu.",Toast.LENGTH_LONG).show()
            startActivity(Intent(this,Ps2BiosActivity::class.java))
            return
        }
        val file=directGameFile(uri)
        if(file==null){
            status.text="PS2 direct path unavailable"
            Toast.makeText(this,"ISO/CHD PS2 tidak dicopy ke cache. Simpan game di penyimpanan internal/SD lalu tambahkan foldernya ke Emu Hub.",Toast.LENGTH_LONG).show()
            return
        }
        status.text="PS2 • direct storage • ARMSX2 Vulkan"
        startActivity(Intent(this,Ps2GameActivity::class.java).putExtra("romPath",file.absolutePath).putExtra("romName",name))
    }

    private fun decodeAndLaunchEcm(uri:Uri,name:String){status.text="Preparing ECM...";scanExecutor.execute{try{val dir=File(cacheDir,"ecm").apply{mkdirs()};val key=cacheKey(uri.toString());val source=File(dir,"$key.ecm");val decoded=File(dir,"$key.bin");if(!decoded.exists()||decoded.length()==0L){runOnUiThread{status.text="Decompressing ECM • $name"};contentResolver.openInputStream(uri)?.use{input->source.outputStream().use{input.copyTo(it)}}?:error("ECM tidak dapat dibaca");if(!NativeBridge.decodeEcm(source.absolutePath,decoded.absolutePath))error("ECM corrupt / decode gagal")};source.delete();runOnUiThread{status.text="ECM ready • launching PS1";launchInternalFile(decoded,"pcsx",name.removeSuffix(".ecm"))}}catch(e:Exception){runOnUiThread{status.text="ECM decode failed";Toast.makeText(this,"ECM gagal dibuka: ${e.message}",Toast.LENGTH_LONG).show()}}}}
    private fun launchInternalFile(file:File,core:String,name:String){startActivity(Intent(this,GameActivity::class.java).putExtra("romPath",file.absolutePath).putExtra("coreId",core).putExtra("romName",name))}
    private fun copyAndLaunchInternal(uri:Uri,name:String,ext:String,forcedCore:String?){launchDirectInternal(uri,name,ext,forcedCore)}
    private fun copyAndLaunchPs2(uri:Uri,name:String){launchDirectPs2(uri,name)}
    private fun coreIdFor(ext:String)=when(ext){"nes"->"fceumm";"sfc","smc"->"snes9x";"bin","cue","chd"->"pcsx";"cso"->"ppsspp";else->"mgba"}

    private fun saveCache(games:List<GameEntry>){val arr=JSONArray();games.forEach{g->arr.put(JSONObject().put("u",g.uri).put("n",g.name).put("e",g.ext).put("f",g.folder))};prefs.edit().putString(KEY_LIBRARY_CACHE,arr.toString()).apply()}
    private fun loadCache():List<GameEntry>{return runCatching{val arr=JSONArray(prefs.getString(KEY_LIBRARY_CACHE,"[]"));buildList{for(i in 0 until arr.length()){val o=arr.getJSONObject(i);add(GameEntry(o.getString("u"),o.getString("n"),o.getString("e"),o.optString("f")))}}}.getOrDefault(emptyList())}

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==REQUEST_ARCHIVE_GAME){clearPendingCompressedPs2(true);status.text="Temporary archive files deleted";return}
        if(resultCode!=RESULT_OK)return
        if(requestCode==REQUEST_FOLDER){val uri=data?.data?:return;runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};val set=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toMutableSet()?:mutableSetOf();set.add(uri.toString());prefs.edit().putStringSet(KEY_ROM_TREES,set).apply();refreshAllFolders(true);return}
        if(requestCode==REQUEST_ROM){val uri=data?.data?:return;val name=displayName(uri)?:"ROM";val ext=extension(name);when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);ext in INTERNAL->copyAndLaunchInternal(uri,name,ext,null);else->Toast.makeText(this,"Format belum didukung: .$ext",Toast.LENGTH_LONG).show()}}
    }

    override fun onDestroy(){scanExecutor.shutdownNow();super.onDestroy()}
}