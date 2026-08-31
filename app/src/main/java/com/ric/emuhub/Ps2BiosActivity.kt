package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.security.MessageDigest

class Ps2BiosActivity : Activity() {
    companion object {
        private const val REQUEST_BIOS = 2301
        const val PREFS = "ps2"
        const val BIOS_PREF = "ps2_bios_name"
        fun biosDir(activity: Activity): File = File(activity.filesDir,"ps2/bios").apply{mkdirs()}
        fun selectedBios(activity: Activity): File? { val dir=biosDir(activity);val p=activity.getSharedPreferences(PREFS,MODE_PRIVATE).getString(BIOS_PREF,null);if(!p.isNullOrBlank())File(dir,p).takeIf{validBios(it)}?.let{return it};return dir.listFiles()?.firstOrNull{validBios(it)} }
        // Common retail PS2 ROM BIOS dumps are 4 MiB. Keep 2 MiB accepted for known split/older dumps,
        // but flag anything other than 4 MiB in diagnostics rather than pretending all 2-8 MiB files are equal.
        fun validBios(file:File):Boolean=file.isFile && (file.length()==4L*1024*1024 || file.length()==2L*1024*1024)
    }
    private lateinit var state:TextView
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=null)setStroke(dp(1),stroke)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(24),dp(34),dp(24),dp(30));setBackgroundColor(Color.BLACK)}
        root.addView(TextView(this).apply{text="PS2 BIOS DIAGNOSTIC";textSize=25f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD)})
        root.addView(TextView(this).apply{text="Validasi firmware dulu, lalu boot PS2 tanpa ISO. Kalau menu BIOS tampil, runtime + Vulkan + BIOS sudah lolos.";textSize=13f;setTextColor(0xFF8C8C8C.toInt());gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
        state=TextView(this).apply{textSize=13f;gravity=Gravity.CENTER;setPadding(dp(16),dp(16),dp(16),dp(16));background=rounded(0xFF0A0A0A.toInt(),18,0xFF252525.toInt())};root.addView(state,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(24)})
        root.addView(Button(this).apply{text="PILIH / GANTI BIOS";isAllCaps=false;setTextColor(Color.WHITE);background=rounded(0xFF151515.toInt(),16,0xFF303030.toInt());setOnClickListener{chooseBios()}},LinearLayout.LayoutParams(-1,dp(56)).apply{topMargin=dp(18)})
        root.addView(Button(this).apply{text="BOOT BIOS ONLY • TEST FIRST FRAME";isAllCaps=false;setTextColor(Color.WHITE);background=rounded(0xFF202020.toInt(),16,0xFF404040.toInt());setOnClickListener{bootBiosOnly()}},LinearLayout.LayoutParams(-1,dp(56)).apply{topMargin=dp(10)})
        root.addView(Button(this).apply{text="SELESAI";isAllCaps=false;setTextColor(Color.WHITE);background=rounded(0xFF0D0D0D.toInt(),16,0xFF252525.toInt());setOnClickListener{finish()}},LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(10)});setContentView(root);refreshState() }
    private fun refreshState(){val b=selectedBios(this);if(b==null){state.text="BIOS BELUM VALID\nPilih dump BIOS PS2 4 MiB (disarankan).";state.setTextColor(0xFFBDBDBD.toInt());return};val sha=runCatching{sha256(b)}.getOrDefault("?");val size=b.length();val verdict=if(size==4L*1024*1024)"4 MiB • ukuran retail normal" else "2 MiB • diterima untuk tes, 4 MiB lebih disarankan";state.text="BIOS READY\n${b.name}\n$verdict\nSHA-256\n$sha";state.setTextColor(0xFFE8E8E8.toInt())}
    private fun bootBiosOnly(){if(selectedBios(this)==null){Toast.makeText(this,"Pilih BIOS valid dulu.",Toast.LENGTH_LONG).show();return};startActivity(Intent(this,Ps2GameActivity::class.java).putExtra(Ps2GameActivity.EXTRA_BIOS_ONLY,true))}
    private fun chooseBios(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/octet-stream";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},REQUEST_BIOS)}
    @Deprecated("Framework compatibility") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_BIOS||resultCode!=RESULT_OK)return;val uri=data?.data?:return;try{val dir=biosDir(this);val original=queryName(uri)?:"ps2_bios.bin";val safe=original.replace(Regex("[^A-Za-z0-9._-]"),"_");val temp=File(dir,"$safe.tmp");contentResolver.openInputStream(uri)?.use{i->temp.outputStream().use{i.copyTo(it)}}?:error("BIOS tidak dapat dibaca");val bytes=temp.length();if(!validBios(temp)){temp.delete();error("Ukuran BIOS tidak didukung: $bytes byte. Gunakan dump PS2 4 MiB.")};dir.listFiles()?.filter{it!=temp}?.forEach{it.delete()};val out=File(dir,safe);if(out.exists())out.delete();if(!temp.renameTo(out)){temp.copyTo(out,true);temp.delete()};getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(BIOS_PREF,out.name).apply();Toast.makeText(this,"BIOS tersimpan. Jalankan BOOT BIOS ONLY.",Toast.LENGTH_SHORT).show();refreshState()}catch(t:Throwable){Toast.makeText(this,"BIOS gagal: ${t.message}",Toast.LENGTH_LONG).show();refreshState()}}
    private fun sha256(file:File):String{val md=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buf=ByteArray(64*1024);while(true){val n=input.read(buf);if(n<=0)break;md.update(buf,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
    private fun queryName(uri:Uri):String?{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst()){val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i)}};return uri.lastPathSegment?.substringAfterLast('/')}
}
