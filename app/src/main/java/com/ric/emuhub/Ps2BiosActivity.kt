package com.ric.emuhub

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Ps2BiosActivity : Activity() {
    companion object {
        private const val REQUEST_BIOS = 2301
        const val PREFS = "ps2"
        const val BIOS_PREF = "ps2_bios_name"
        fun biosDir(activity: Activity): File = File(activity.filesDir,"ps2/bios").apply{mkdirs()}
        fun selectedBios(activity: Activity): File? { val dir=biosDir(activity);val p=activity.getSharedPreferences(PREFS,MODE_PRIVATE).getString(BIOS_PREF,null);if(!p.isNullOrBlank())File(dir,p).takeIf{validBios(it)}?.let{return it};return dir.listFiles()?.firstOrNull{validBios(it)} }
        fun validBios(file:File):Boolean=file.isFile && (file.length()==4L*1024*1024 || file.length()==2L*1024*1024)
    }
    private lateinit var state:TextView
    private lateinit var crashState:TextView
    private lateinit var nativeState:TextView
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=null)setStroke(dp(1),stroke)}

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(24),dp(24),dp(24),dp(24));setBackgroundColor(Color.BLACK)}
        root.addView(TextView(this).apply{text="PS2 BIOS DIAGNOSTIC";textSize=25f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD)})
        root.addView(TextView(this).apply{text="Tidak tebak BIOS lagi. Halaman ini baca page-size, core yang dipilih, dan tombstone native proses :ps2.";textSize=13f;setTextColor(0xFF8C8C8C.toInt());gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})

        state=TextView(this).apply{textSize=13f;gravity=Gravity.CENTER;setPadding(dp(16),dp(14),dp(16),dp(14));background=rounded(0xFF0A0A0A.toInt(),18,0xFF252525.toInt())}
        root.addView(state,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(18)})

        nativeState=TextView(this).apply{textSize=12f;gravity=Gravity.CENTER;setPadding(dp(16),dp(14),dp(16),dp(14));background=rounded(0xFF071019.toInt(),18,0xFF17344A.toInt())}
        root.addView(nativeState,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})

        crashState=TextView(this).apply{textSize=11f;gravity=Gravity.CENTER;setPadding(dp(14),dp(14),dp(14),dp(14));background=rounded(0xFF120808.toInt(),18,0xFF402020.toInt())}
        root.addView(crashState,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})

        root.addView(Button(this).apply{text="PILIH / GANTI BIOS";isAllCaps=false;setTextColor(Color.WHITE);background=rounded(0xFF151515.toInt(),16,0xFF303030.toInt());setOnClickListener{chooseBios()}},LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(14)})
        root.addView(Button(this).apply{text="BOOT BIOS ONLY • ONE TEST";isAllCaps=false;setTextColor(Color.WHITE);background=rounded(0xFF202020.toInt(),16,0xFF404040.toInt());setOnClickListener{bootBiosOnly()}},LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(8)})
        root.addView(Button(this).apply{text="SELESAI";isAllCaps=false;setTextColor(Color.WHITE);background=rounded(0xFF0D0D0D.toInt(),16,0xFF252525.toInt());setOnClickListener{finish()}},LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})
        setContentView(root);refreshState()
    }

    override fun onResume(){super.onResume();if(::state.isInitialized)refreshState()}

    private fun refreshState(){
        val b=selectedBios(this)
        if(b==null){state.text="BIOS BELUM VALID\nPilih dump BIOS PS2 4 MiB (disarankan).";state.setTextColor(0xFFBDBDBD.toInt())}
        else {val sha=runCatching{sha256(b)}.getOrDefault("?");val size=b.length();val verdict=if(size==4L*1024*1024)"4 MiB • ukuran retail normal" else "2 MiB • diterima untuk tes";state.text="BIOS READY\n${b.name}\n$verdict\nSHA-256\n$sha";state.setTextColor(0xFFE8E8E8.toInt())}

        val pageSize=runCatching{Os.sysconf(OsConstants._SC_PAGESIZE)}.getOrDefault(4096L).let{if(it>0)it else 4096L}
        val coreName=if(pageSize>=16384L)"libemucore_16k.so" else "libemucore_4k.so"
        val nativeDir=applicationInfo.nativeLibraryDir?.let{File(it)}
        val core=if(nativeDir!=null)File(nativeDir,coreName) else null
        val fourK=if(nativeDir!=null)File(nativeDir,"libemucore_4k.so") else null
        val sixteenK=if(nativeDir!=null)File(nativeDir,"libemucore_16k.so") else null
        nativeState.text="NATIVE LOADER\nPAGE SIZE: $pageSize bytes\nSELECTED: $coreName\nSELECTED EXISTS: ${core?.isFile==true} • ${if(core?.isFile==true) core.length()/1024/1024 else 0} MiB\n4K CORE: ${fourK?.isFile==true} • 16K CORE: ${sixteenK?.isFile==true}"
        nativeState.setTextColor(0xFFB9E2FF.toInt())

        var stage:String?=null
        var time=0L
        val traceFile=File(filesDir,"ps2/last_native_stage.txt")
        if(traceFile.isFile){val lines=runCatching{traceFile.readLines()}.getOrDefault(emptyList());stage=lines.getOrNull(0)?.takeIf{it.isNotBlank()};time=lines.getOrNull(1)?.toLongOrNull()?:0L}
        if(stage.isNullOrBlank()){val trace=getSharedPreferences("ps2_runtime_trace",MODE_PRIVATE);stage=trace.getString("last_crash_stage",null);time=trace.getLong("last_crash_time",0L)}

        val exit=latestPs2Exit()
        val tombstone=exit?.let{readNativeTombstone(it)}
        val exitText=if(exit==null)"ANDROID EXIT: belum ada data" else {
            val whenText=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(exit.timestamp))
            buildString{
                append("ANDROID EXIT: ${reasonName(exit.reason)} (${exit.reason})\n")
                append("STATUS: ${exit.status} • $whenText\n")
                append("DESC: ${exit.description ?: "-"}")
                if(!tombstone.isNullOrBlank())append("\n\nNATIVE TOMBSTONE\n$tombstone")
                else append("\n\nNATIVE TOMBSTONE\ntrace unavailable")
            }
        }
        if(stage.isNullOrBlank()){crashState.text="LAST NATIVE STAGE\nBelum ada data tersimpan.\n\n$exitText";crashState.setTextColor(0xFF9A9A9A.toInt())}
        else {val whenText=if(time>0L)SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(time)) else "-";crashState.text="LAST NATIVE STAGE\n$stage\n$whenText\n\n$exitText";crashState.setTextColor(0xFFFFB4B4.toInt())}
    }

    private fun latestPs2Exit():ApplicationExitInfo?{
        if(Build.VERSION.SDK_INT<30)return null
        return runCatching{
            val am=getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName,0,12).firstOrNull{it.processName=="$packageName:ps2"}
        }.getOrNull()
    }

    private fun readNativeTombstone(exit:ApplicationExitInfo):String?{
        if(Build.VERSION.SDK_INT<31)return null
        return runCatching{
            exit.traceInputStream?.use{stream->
                BufferedReader(InputStreamReader(stream)).use{reader->
                    val lines=reader.readLines()
                    val interesting=lines.filter{line->
                        val s=line.trim()
                        s.contains("signal ",true)||s.contains("Abort message",true)||s.contains("backtrace",true)||
                        s.matches(Regex("#\\d{2}.*"))||s.contains("libemucore",true)||s.contains("Fatal signal",true)
                    }
                    (if(interesting.isNotEmpty())interesting else lines).take(18).joinToString("\n").take(4200)
                }
            }
        }.getOrNull()?.takeIf{it.isNotBlank()}
    }

    private fun reasonName(reason:Int)=when(reason){
        ApplicationExitInfo.REASON_CRASH_NATIVE->"CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH->"CRASH_JAVA"
        ApplicationExitInfo.REASON_ANR->"ANR"
        ApplicationExitInfo.REASON_SIGNALED->"SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY->"LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE->"RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED->"USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED->"USER_STOPPED"
        else->"REASON_$reason"
    }

    private fun bootBiosOnly(){if(selectedBios(this)==null){Toast.makeText(this,"Pilih BIOS valid dulu.",Toast.LENGTH_LONG).show();return};startActivity(Intent(this,Ps2GameActivity::class.java).putExtra(Ps2GameActivity.EXTRA_BIOS_ONLY,true))}
    private fun chooseBios(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/octet-stream";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},REQUEST_BIOS)}
    @Deprecated("Framework compatibility") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_BIOS||resultCode!=RESULT_OK)return;val uri=data?.data?:return;try{val dir=biosDir(this);val original=queryName(uri)?:"ps2_bios.bin";val safe=original.replace(Regex("[^A-Za-z0-9._-]"),"_");val temp=File(dir,"$safe.tmp");contentResolver.openInputStream(uri)?.use{i->temp.outputStream().use{i.copyTo(it)}}?:error("BIOS tidak dapat dibaca");val bytes=temp.length();if(!validBios(temp)){temp.delete();error("Ukuran BIOS tidak didukung: $bytes byte. Gunakan dump PS2 4 MiB.")};dir.listFiles()?.filter{it!=temp}?.forEach{it.delete()};val out=File(dir,safe);if(out.exists())out.delete();if(!temp.renameTo(out)){temp.copyTo(out,true);temp.delete()};getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(BIOS_PREF,out.name).apply();Toast.makeText(this,"BIOS tersimpan.",Toast.LENGTH_SHORT).show();refreshState()}catch(t:Throwable){Toast.makeText(this,"BIOS gagal: ${t.message}",Toast.LENGTH_LONG).show();refreshState()}}
    private fun sha256(file:File):String{val md=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buf=ByteArray(64*1024);while(true){val n=input.read(buf);if(n<=0)break;md.update(buf,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
    private fun queryName(uri:Uri):String?{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst()){val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i)}};return uri.lastPathSegment?.substringAfterLast('/')}
}
