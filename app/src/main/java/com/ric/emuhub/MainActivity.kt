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
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
        private const val PREFS = "emuhub_library"
        private const val KEY_ROM_TREE_LEGACY = "rom_tree"
        private const val KEY_ROM_TREES = "rom_trees"
        private const val KEY_LIBRARY_CACHE = "library_cache_v2"
        private val INTERNAL = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm")
        private val SWITCH = setOf("xci","nsp","nro")
        private val RECOGNIZED = INTERNAL + SWITCH
        private val EDEN_PACKAGES = listOf("com.miHoYo.Yuanshen","com.miHoYo.Yunashen","com.miHoYo.Yuanshen.nightly","dev.eden.eden_emulator","dev.eden.eden_nightly")
        private const val PPSSPP_ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity"
    }

    data class GameEntry(val uri:String,val name:String,val ext:String,val folder:String="")

    private lateinit var library: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val scanExecutor = Executors.newFixedThreadPool(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacyFolder()
        renderHome()
        val cached = loadCache()
        if (cached.isNotEmpty()) renderLibrary(cached, "${cached.size} game • cache")
        else status.text = "Tambah folder ROM untuk membuat library."
        refreshAllFolders(false)
    }

    private fun migrateLegacyFolder(){
        val old=prefs.getString(KEY_ROM_TREE_LEGACY,null)?:return
        val set=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toMutableSet()?:mutableSetOf()
        if(set.add(old))prefs.edit().putStringSet(KEY_ROM_TREES,set).remove(KEY_ROM_TREE_LEGACY).apply()
        else prefs.edit().remove(KEY_ROM_TREE_LEGACY).apply()
    }

    private fun renderHome() {
        val outer = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,22,28,20); setBackgroundColor(0xFF090A0F.toInt()) }
        outer.addView(TextView(this).apply { text="EMU HUB"; textSize=29f; setTypeface(typeface,Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        status = TextView(this).apply { text="Library offline • PPSSPP built-in"; textSize=13f; setTextColor(0xFF9B9EAA.toInt()); setPadding(0,5,0,13) }
        outer.addView(status)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(Button(this).apply{text="+ FOLDER";setOnClickListener{chooseRomFolder()}},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Button(this).apply{text="REFRESH";setOnClickListener{refreshAllFolders(true)}},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Button(this).apply{text="FILE";setOnClickListener{openRomPicker()}},LinearLayout.LayoutParams(0,-2,1f))
        outer.addView(actions)
        library=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,14,0,20)}
        val scroll=ScrollView(this).apply{isFillViewport=true;addView(library,ViewGroup.LayoutParams(-1,-2))}
        outer.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(outer)
    }

    private fun chooseRomFolder(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_FOLDER)}
    private fun openRomPicker(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},REQUEST_ROM)}

    private fun refreshAllFolders(userRequested:Boolean){
        val trees=prefs.getStringSet(KEY_ROM_TREES, emptySet())?.toSet().orEmpty()
        if(trees.isEmpty()) return
        status.text=if(userRequested) "Refreshing ${trees.size} folder..." else "Library siap • scan background"
        val pending=AtomicInteger(trees.size);val merged=java.util.Collections.synchronizedList(mutableListOf<GameEntry>())
        trees.forEach{raw->scanExecutor.execute{
            val root=DocumentFile.fromTreeUri(this,Uri.parse(raw));if(root!=null)collectGames(root,merged,1200,root.name?:"ROM")
            if(pending.decrementAndGet()==0){
                val unique=merged.distinctBy{it.uri}.sortedBy{it.name.lowercase()};saveCache(unique)
                runOnUiThread{renderLibrary(unique,"${unique.size} game • ${trees.size} folder")}
            }
        }}
    }

    private fun collectGames(dir:DocumentFile,out:MutableList<GameEntry>,limit:Int,folder:String){
        if(out.size>=limit)return
        val files=runCatching{dir.listFiles()}.getOrDefault(emptyArray())
        for(f in files){if(out.size>=limit)return;if(f.isDirectory)collectGames(f,out,limit,folder) else {val n=f.name?:continue;val e=extension(n);if(e in RECOGNIZED)out.add(GameEntry(f.uri.toString(),n,e,folder))}}
    }

    private fun renderLibrary(games:List<GameEntry>,label:String){library.removeAllViews();status.text=if(games.isEmpty())"Tidak ada ROM didukung." else "$label • tap untuk main";games.forEach(::addGameCard)}

    private fun addGameCard(g:GameEntry){
        val ext=g.ext;val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(14,12,14,12);background=rounded(0xFF151720.toInt(),18f)}
        val badge=TextView(this).apply{text=systemCode(ext);gravity=Gravity.CENTER;textSize=12f;setTypeface(typeface,Typeface.BOLD);setTextColor(0xFFFFFFFF.toInt());background=rounded(systemColor(ext),16f)}
        row.addView(badge,LinearLayout.LayoutParams(58,58).apply{rightMargin=14})
        val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        info.addView(TextView(this).apply{text=g.name.substringBeforeLast('.',g.name);textSize=16f;maxLines=2;setTypeface(typeface,Typeface.BOLD);setTextColor(0xFFF5F5F7.toInt())})
        info.addView(TextView(this).apply{text="${systemName(ext)}${if(g.folder.isNotBlank())"  •  ${g.folder}" else ""}";textSize=11f;maxLines=1;setTextColor(0xFF969AA8.toInt())})
        row.addView(info,LinearLayout.LayoutParams(0,-2,1f));row.setOnClickListener{openLibraryGame(Uri.parse(g.uri),g.name,ext)}
        library.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=10})
    }

    private fun rounded(color:Int,radius:Float)=GradientDrawable().apply{setColor(color);cornerRadius=radius}
    private fun systemCode(e:String)=when(e){"gb","gbc","gba"->"GBA";"nes"->"NES";"sfc","smc"->"SNES";"bin","cue","chd"->"PS1";"ecm"->"ECM";"iso"->"ISO";"cso"->"PSP";"xci","nsp","nro"->"NSW";else->e.uppercase().take(4)}
    private fun systemColor(e:String)=when(e){"xci","nsp","nro"->0xFF315A75.toInt();"bin","cue","chd","iso","ecm"->0xFF493D68.toInt();"cso"->0xFF314E68.toInt();"gba","gb","gbc"->0xFF355B49.toInt();else->0xFF41444F.toInt()}

    private fun openLibraryGame(uri:Uri,name:String,ext:String){when{ext in SWITCH->launchEden(uri);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="cso"->launchEmbeddedPpsspp(uri,name);else->copyAndLaunchInternal(uri,name,ext,null)}}

    private fun showIsoChooser(uri:Uri,name:String){
        AlertDialog.Builder(this)
            .setTitle("Buka ISO sebagai")
            .setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP built-in")){_,which->
                if(which==0) copyAndLaunchInternal(uri,name,"iso","pcsx") else launchEmbeddedPpsspp(uri,name)
            }
            .setNegativeButton("Batal",null)
            .show()
    }

    private fun launchEmbeddedPpsspp(uri:Uri,name:String){
        try{
            status.text="Membuka $name • PPSSPP built-in"
            startActivity(Intent(Intent.ACTION_VIEW).apply{
                setClassName(packageName,PPSSPP_ACTIVITY)
                data=uri
                type="application/octet-stream"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData=ClipData.newRawUri("PSP game",uri)
            })
        }catch(e:Exception){
            status.text="PPSSPP built-in gagal dibuka"
            Toast.makeText(this,"PPSSPP built-in gagal: ${e.message}",Toast.LENGTH_LONG).show()
        }
    }

    private fun launchEden(uri:Uri){val pkg=EDEN_PACKAGES.firstOrNull{packageManager.getLaunchIntentForPackage(it)!=null}?:run{Toast.makeText(this,"Eden / Eden Optimized tidak terdeteksi.",Toast.LENGTH_LONG).show();return};try{startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,"application/octet-stream");setPackage(pkg);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri("Switch ROM",uri)})}catch(_:ActivityNotFoundException){packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)}catch(_:Exception){packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)}}

    private fun displayName(uri:Uri):String?{if(uri.scheme=="content")contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst()){val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i)}};return uri.lastPathSegment?.substringAfterLast('/')}
    private fun extension(name:String?)=name.orEmpty().substringAfterLast('.',"").lowercase()
    private fun systemName(e:String)=when(e){"gb","gbc","gba"->"Game Boy • mGBA";"nes"->"NES • FCEUmm";"sfc","smc"->"SNES • Snes9x";"bin","cue","chd"->"PlayStation • PCSX-ReARMed";"ecm"->"PlayStation • compressed • auto decode";"iso"->"PS1 / PSP • pilih saat dibuka";"cso"->"PSP • PPSSPP built-in";"xci","nsp","nro"->"Switch • Eden Optimized";else->"ROM"}

    private fun cacheKey(value:String):String=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}.take(24)

    private fun decodeAndLaunchEcm(uri:Uri,name:String){
        status.text="Preparing ECM..."
        scanExecutor.execute{
            try{
                val dir=File(cacheDir,"ecm").apply{mkdirs()}
                val key=cacheKey(uri.toString())
                val source=File(dir,"$key.ecm")
                val decoded=File(dir,"$key.bin")
                if(!decoded.exists()||decoded.length()==0L){
                    runOnUiThread{status.text="Decompressing ECM • $name"}
                    contentResolver.openInputStream(uri)?.use{input->source.outputStream().use{input.copyTo(it)}}?:error("ECM tidak dapat dibaca")
                    if(!NativeBridge.decodeEcm(source.absolutePath,decoded.absolutePath))error("ECM corrupt / decode gagal")
                }
                source.delete()
                runOnUiThread{
                    status.text="ECM siap • membuka PS1"
                    launchInternalFile(decoded,"pcsx",name.removeSuffix(".ecm"))
                }
            }catch(e:Exception){runOnUiThread{status.text="Gagal decode ECM: ${e.message}";Toast.makeText(this,"ECM gagal dibuka: ${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }

    private fun launchInternalFile(file:File,core:String,name:String){startActivity(Intent(this,GameActivity::class.java).putExtra("romPath",file.absolutePath).putExtra("coreId",core).putExtra("romName",name))}

    private fun copyAndLaunchInternal(uri:Uri,name:String,ext:String,forcedCore:String?){try{status.text="Membuka $name...";val dir=File(cacheDir,"roms").apply{mkdirs()};val safe=name.replace(Regex("[^A-Za-z0-9._ -]"),"_");val out=File(dir,safe);contentResolver.openInputStream(uri)?.use{input->out.outputStream().use{input.copyTo(it)}}?:error("ROM tidak dapat dibaca");val core=forcedCore?:coreIdFor(ext);launchInternalFile(out,core,name)}catch(e:Exception){status.text="Gagal membuka ROM: ${e.message}"}}
    private fun coreIdFor(ext:String)=when(ext){"nes"->"fceumm";"sfc","smc"->"snes9x";"bin","cue","chd"->"pcsx";else->"mgba"}

    private fun saveCache(games:List<GameEntry>){val arr=JSONArray();games.forEach{g->arr.put(JSONObject().put("u",g.uri).put("n",g.name).put("e",g.ext).put("f",g.folder))};prefs.edit().putString(KEY_LIBRARY_CACHE,arr.toString()).apply()}
    private fun loadCache():List<GameEntry>{return runCatching{val arr=JSONArray(prefs.getString(KEY_LIBRARY_CACHE,"[]"));buildList{for(i in 0 until arr.length()){val o=arr.getJSONObject(i);add(GameEntry(o.getString("u"),o.getString("n"),o.getString("e"),o.optString("f")))}}}.getOrDefault(emptyList())}

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return;if(requestCode==REQUEST_FOLDER){val uri=data?.data?:return;runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};val set=prefs.getStringSet(KEY_ROM_TREES,emptySet())?.toMutableSet()?:mutableSetOf();set.add(uri.toString());prefs.edit().putStringSet(KEY_ROM_TREES,set).apply();refreshAllFolders(true);return};if(requestCode==REQUEST_ROM){val uri=data?.data?:return;val name=displayName(uri)?:"ROM";val ext=extension(name);when{ext in SWITCH->launchEden(uri);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="cso"->launchEmbeddedPpsspp(uri,name);ext in INTERNAL->copyAndLaunchInternal(uri,name,ext,null);else->Toast.makeText(this,"Format belum didukung: .$ext",Toast.LENGTH_LONG).show()}}}

    override fun onDestroy(){scanExecutor.shutdownNow();super.onDestroy()}
}
