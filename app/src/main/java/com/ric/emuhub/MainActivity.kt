package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
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
        window.statusBarColor=0xFF000000.toInt();window.navigationBarColor=0xFF000000.toInt()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF000000.toInt())}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER}
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(28))}

        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        brand.addView(textView("EMU HUB",28f,0xFFFFFFFF.toInt(),true))
        brand.addView(textView("All your consoles. One place.",12f,0xFF858585.toInt()))
        header.addView(brand,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val hubMark=textView("HUB",11f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF0B0B0B.toInt(),14,0xFF292929.toInt());setPadding(dp(13),dp(9),dp(13),dp(9))}
        header.addView(hubMark);content.addView(header)

        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(17),dp(18),dp(16));background=rounded(0xFF080808.toInt(),22,0xFF1C1C1C.toInt())}
        content.addView(hero,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(20)})
        val heroTop=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroTop.addView(textView("GAME LIBRARY",12f,0xFFBDBDBD.toInt(),true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        countBadge=textView("0 GAMES",11f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF121212.toInt(),12,0xFF252525.toInt());setPadding(dp(11),dp(7),dp(11),dp(7))}
        heroTop.addView(countBadge);hero.addView(heroTop)
        hero.addView(textView("Ready to play",23f,0xFFFFFFFF.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(10)})
        status=textView("Offline cores • local ROM library",12f,0xFF8B8B8B.toInt())
        hero.addView(status,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})

        val quickGrid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val quickTop=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        quickTop.addView(actionTile("＋","ADD FOLDER","Scan a ROM folder") { chooseRomFolder() },LinearLayout.LayoutParams(0,dp(104),1f).apply{rightMargin=dp(5)})
        quickTop.addView(actionTile("PS2","SETTINGS","Manual tuning / BIOS") {
            startActivity(Intent(this@MainActivity,Ps2SettingsActivity::class.java))
        },LinearLayout.LayoutParams(0,dp(104),1f).apply{leftMargin=dp(5)})
        /* legacy BIOS entry is now inside PS2 Settings */
        /* 
        quickGrid.addView(quickTop,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(104)))
        val quickBottom=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        quickBottom.addView(actionTile("↻","REFRESH","Rescan library") { refreshAllFolders(true) },LinearLayout.LayoutParams(0,dp(104),1f).apply{rightMargin=dp(5)})
        quickBottom.addView(actionTile("▶","OPEN FILE","Launch one ROM") { openRomPicker() },LinearLayout.LayoutParams(0,dp(104),1f).apply{leftMargin=dp(5)})
        quickGrid.addView(quickBottom,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(104)).apply{topMargin=dp(10)})
        content.addView(quickGrid,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(218)).apply{topMargin=dp(14)})

        content.addView(sectionTitle("CONSOLE HUB"),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(24)})
        content.addView(buildConsoleStrip(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(72)).apply{topMargin=dp(10)})

        val libraryHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        libraryHeader.addView(sectionTitle("YOUR GAMES"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        libraryHeader.addView(textView("BY CONSOLE",10f,0xFF6F6F6F.toInt(),true))
        content.addView(libraryHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(22)})

        library=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,0)}
        content.addView(library)
        scroll.addView(content,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)
    }

    private fun sectionTitle(label:String)=textView(label,12f,0xFF868686.toInt(),true).apply{letterSpacing=0.12f}

    private fun actionTile(icon:String,title:String,subtitle:String,onClick:()->Unit)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(12),dp(12),dp(12));background=rounded(0xFF080808.toInt(),18,0xFF1D1D1D.toInt());isClickable=true;isFocusable=true
        addView(textView(icon,22f,0xFFFFFFFF.toInt(),true));addView(textView(title,11f,0xFFF4F4F4.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(4)});addView(textView(subtitle,9.5f,0xFF6F6F6F.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)});setOnClickListener{onClick()}
    }

    private fun buildConsoleStrip():View{
        val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        listOf(Triple("PSP","PPSSPP","2×"),Triple("PS1","PCSX","ARM"),Triple("PS2","ARMSX2","VK"),Triple("GBA","mGBA","CORE"),Triple("NES","FCEUmm","CORE"),Triple("SNES","Snes9x","CORE"),Triple("SWITCH","Eden","EXT")).forEachIndexed{index,item->
            val chip=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(10),dp(16),dp(10));background=rounded(if(index==0)0xFF101010.toInt() else 0xFF080808.toInt(),16,if(index==0)0xFF333333.toInt() else 0xFF1D1D1D.toInt());addView(textView(item.first,12f,0xFFFFFFFF.toInt(),true));addView(textView("${item.second} • ${item.third}",9.5f,0xFF777777.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(3)})
                if(item.first=="PS2"){
                    isClickable=true;isFocusable=true
                    setOnClickListener{
                        status.text=if(Ps2BiosActivity.selectedBios(this@MainActivity)!=null)"PS2 BIOS ready • tap to change" else "PS2 BIOS setup • pilih BIOS dulu"
                        startActivity(Intent(this@MainActivity,Ps2BiosActivity::class.java))
                    }
                }
            }
            row.addView(chip,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(60)).apply{if(index>0)leftMargin=dp(8)})
        }
        hsv.addView(row,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(60)));return hsv
    }

    private fun showEmptyLibrary(){
        library.removeAllViews();countBadge.text="0 GAMES";status.text="Add a ROM folder to build your hub"
        val empty=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(30),dp(20),dp(30));background=rounded(0xFF070707.toInt(),20,0xFF1B1B1B.toInt())}
        empty.addView(textView("⌁",30f,0xFF777777.toInt(),true));empty.addView(textView("Your library is empty",16f,0xFFF2F2F2.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(7)});empty.addView(textView("Add a folder and Emu Hub will organize supported games automatically.",11f,0xFF777777.toInt()).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)});library.addView(empty,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun chooseRomFolder(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_FOLDER)}
    private fun openRomPicker(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},REQUEST_ROM)}

    private fun refreshAllFolders(userRequested:Boolean){
        val trees=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toSet().orEmpty();if(trees.isEmpty()){if(userRequested)Toast.makeText(this,"Belum ada folder ROM.",Toast.LENGTH_SHORT).show();return}
        status.text=if(userRequested)"Refreshing ${trees.size} folder..." else "Library ready • background scan"
        val pending=AtomicInteger(trees.size);val merged=java.util.Collections.synchronizedList(mutableListOf<GameEntry>())
        trees.forEach{raw->scanExecutor.execute{val root=DocumentFile.fromTreeUri(this,Uri.parse(raw));if(root!=null)collectGames(root,merged,1200,root.name?:"ROM");if(pending.decrementAndGet()==0){val unique=sortGames(merged.distinctBy{it.uri});saveCache(unique);runOnUiThread{if(unique.isEmpty())showEmptyLibrary() else renderLibrary(unique,"${unique.size} game • ${trees.size} folder")}}}}
    }

    private fun collectGames(dir:DocumentFile,out:MutableList<GameEntry>,limit:Int,folder:String){if(out.size>=limit)return;for(f in runCatching{dir.listFiles()}.getOrDefault(emptyArray())){if(out.size>=limit)return;if(f.isDirectory)collectGames(f,out,limit,folder) else{val n=f.name?:continue;val e=extension(n);if(e in RECOGNIZED)out.add(GameEntry(f.uri.toString(),n,e,folder))}}}

    private fun consoleRank(e:String)=when(e){"cso"->0;"bin","cue","ecm"->1;"iso","chd"->2;"gb","gbc","gba"->3;"nes"->4;"sfc","smc"->5;"xci","nsp","nro"->6;in ARCHIVES->7;else->99}
    private fun consoleGroup(e:String)=when(e){"cso"->"PSP • PPSSPP";"bin","cue","ecm"->"PLAYSTATION • PCSX-REARMED";"iso","chd"->"DISC IMAGE • PS1 / PSP / PS2";"gb","gbc","gba"->"GAME BOY • MGBA";"nes"->"NES • FCEUMM";"sfc","smc"->"SNES • SNES9X";"xci","nsp","nro"->"NINTENDO SWITCH • EDEN";in ARCHIVES->"COMPRESSED ROMS • AUTO TEMP";else->"OTHER"}
    private fun sortGames(games:List<GameEntry>)=games.sortedWith(compareBy<GameEntry>{consoleRank(it.ext)}.thenBy{it.name.lowercase()})

    private fun renderLibrary(games:List<GameEntry>,label:String){
        library.removeAllViews();countBadge.text="${games.size} GAMES";status.text="$label • grouped by console"
        val sorted=sortGames(games);var lastGroup=""
        sorted.forEach{g->
            val group=consoleGroup(g.ext)
            if(group!=lastGroup){
                if(lastGroup.isNotEmpty())library.addView(View(this).apply{setBackgroundColor(0xFF151515.toInt())},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)).apply{topMargin=dp(8);bottomMargin=dp(14)})
                val gamesInGroup=sorted.count{consoleGroup(it.ext)==group}
                val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
                header.addView(textView(group,11f,0xFFB8B8B8.toInt(),true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                header.addView(textView("$gamesInGroup",10f,0xFF676767.toInt(),true))
                library.addView(header,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(9)})
                lastGroup=group
            }
            addGameCard(g)
        }
    }

    private fun addGameCard(g:GameEntry){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(13),dp(12),dp(12),dp(12));background=rounded(0xFF080808.toInt(),18,0xFF1C1C1C.toInt());isClickable=true;isFocusable=true}
        val badge=FrameLayout(this).apply{background=rounded(systemColor(g.ext),16)}
        badge.addView(textView(systemCode(g.ext),11f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));row.addView(badge,LinearLayout.LayoutParams(dp(54),dp(54)).apply{rightMargin=dp(13)})
        val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};info.addView(textView(g.name.substringBeforeLast('.',g.name),15f,0xFFF7F7F7.toInt(),true).apply{maxLines=2});info.addView(textView(systemName(g.ext),10.5f,0xFF8B8B8B.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(4)});if(g.folder.isNotBlank())info.addView(textView(g.folder,9.5f,0xFF606060.toInt()).apply{maxLines=1},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)});row.addView(info,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val play=textView("▶",16f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF111111.toInt(),14,0xFF242424.toInt())};row.addView(play,LinearLayout.LayoutParams(dp(42),dp(42)).apply{leftMargin=dp(10)});row.setOnClickListener{openLibraryGame(Uri.parse(g.uri),g.name,g.ext)};library.addView(row,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(9)})
    }

    private fun systemCode(e:String)=when(e){"gb","gbc","gba"->"GBA";"nes"->"NES";"sfc","smc"->"SNES";"bin","cue"->"PS1";"chd"->"CHD";"ecm"->"ECM";"iso"->"ISO";"cso"->"PSP";"xci","nsp","nro"->"NSW";in ARCHIVES->e.uppercase().take(4);else->e.uppercase().take(4)}
    private fun systemColor(e:String)=when(e){"xci","nsp","nro"->0xFF243847.toInt();"bin","cue","chd","iso","ecm"->0xFF3A3347.toInt();"cso"->0xFF243C4B.toInt();"gba","gb","gbc"->0xFF2D4138.toInt();"nes"->0xFF493237.toInt();"sfc","smc"->0xFF39364A.toInt();in ARCHIVES->0xFF3A3A3A.toInt();else->0xFF303030.toInt()}

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
        status.text="Preparing compressed ROM • $name"
        scanExecutor.execute{
            try{
                val session=ArchiveHelper.extract(this,uri,name)
                runOnUiThread{
                    status.text="Archive ready • temporary files"
                    if(session.roms.size==1)launchExtractedRom(session,session.roms.first())
                    else AlertDialog.Builder(this).setTitle("Pilih game dari archive").setItems(session.roms.map{it.displayName}.toTypedArray()){_,which->launchExtractedRom(session,session.roms[which])}.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()
                }
            }catch(e:Exception){runOnUiThread{status.text="Archive gagal dibuka";Toast.makeText(this,"Archive gagal: ${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }

    private fun launchExtractedRom(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){
        when(rom.ext){
            "iso"->showExtractedIsoChooser(session,rom)
            "chd"->showExtractedChdChooser(session,rom)
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
            session.root.deleteRecursively()
            Toast.makeText(this,"Setup BIOS PS2 dulu dari kartu PS2 di Console Hub.",Toast.LENGTH_LONG).show()
            startActivity(Intent(this,Ps2BiosActivity::class.java))
            return
        }
        pendingArchiveSession=session.root
        startActivityForResult(Intent(this,Ps2GameActivity::class.java).putExtra("romPath",rom.file.absolutePath).putExtra("romName",rom.displayName),REQUEST_ARCHIVE_GAME)
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
        if(requestCode==REQUEST_ARCHIVE_GAME){pendingArchiveSession?.deleteRecursively();pendingArchiveSession=null;status.text="Temporary archive files deleted";return}
        if(resultCode!=RESULT_OK)return
        if(requestCode==REQUEST_FOLDER){val uri=data?.data?:return;runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};val set=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toMutableSet()?:mutableSetOf();set.add(uri.toString());prefs.edit().putStringSet(KEY_ROM_TREES,set).apply();refreshAllFolders(true);return}
        if(requestCode==REQUEST_ROM){val uri=data?.data?:return;val name=displayName(uri)?:"ROM";val ext=extension(name);when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);ext in INTERNAL->copyAndLaunchInternal(uri,name,ext,null);else->Toast.makeText(this,"Format belum didukung: .$ext",Toast.LENGTH_LONG).show()}}
    }

    override fun onDestroy(){scanExecutor.shutdownNow();super.onDestroy()}
}