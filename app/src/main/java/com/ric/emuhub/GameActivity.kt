package com.ric.emuhub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ric.emuhub.core.NativeBridge
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class GameActivity : Activity() {
    private var gameView: GameView? = null
    @Volatile private var fastForward = false
    private lateinit var stateFile: File
    private var analogX = 0; private var analogY = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom=intent.getStringExtra("romPath")?:run{finish();return}; val coreId=intent.getStringExtra("coreId")?:"mgba"; val romName=intent.getStringExtra("romName")?:File(rom).name
        val coreFile=when(coreId){"fceumm"->"libfceumm_core.so";"snes9x"->"libsnes9x_core.so";"pcsx"->"libpcsx_rearmed_core.so";"ppsspp"->"libppsspp_core.so";else->"libmgba_core.so"}
        val coreLabel=when(coreId){"fceumm"->"FCEUmm";"snes9x"->"Snes9x";"pcsx"->"PCSX-ReARMed";"ppsspp"->"PPSSPP";else->"mGBA"}
        val systemRoot=File(filesDir,"system").apply{mkdirs()}; if(coreId=="ppsspp")installPpssppAssets(systemRoot)
        val saveDirFile=File(filesDir,"saves").apply{mkdirs()}; stateFile=File(saveDirFile,"${coreId}_${safeStateKey(romName)}_slot0.state")
        if(!NativeBridge.init(applicationInfo.nativeLibraryDir+"/$coreFile",systemRoot.absolutePath,saveDirFile.absolutePath)||!NativeBridge.loadGame(rom)){setContentView(TextView(this).apply{text="$coreLabel core gagal memuat game.";gravity=Gravity.CENTER;textSize=18f});return}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF09090B.toInt())}
        gameView=GameView().also{root.addView(it,LinearLayout.LayoutParams(-1,0,1f))};root.addView(buildUtilityControls(),LinearLayout.LayoutParams(-1,-2));if(coreId=="pcsx"||coreId=="ppsspp")root.addView(buildAnalogControls(),LinearLayout.LayoutParams(-1,-2));root.addView(buildControls(coreId),LinearLayout.LayoutParams(-1,-2));setContentView(root);gameView?.start()
    }
    private fun installPpssppAssets(root:File){val t=File(root,"PPSSPP"),m=File(t,".emuhub_assets_v1");if(m.exists())return;t.mkdirs();copyAssetTree("PPSSPP",t);runCatching{m.writeText("1")}}
    private fun copyAssetTree(p:String,t:File){val e=assets.list(p)?:return;if(e.isEmpty()){t.parentFile?.mkdirs();assets.open(p).use{i->t.outputStream().use{i.copyTo(it)}};return};t.mkdirs();e.forEach{copyAssetTree("$p/$it",File(t,it))}}
    private fun safeStateKey(n:String)=MessageDigest.getInstance("SHA-256").digest(n.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
    private fun buildUtilityControls():View{val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};fun add(s:String,f:()->Unit){r.addView(Button(this).apply{text=s;setOnClickListener{f()}},LinearLayout.LayoutParams(0,-2,1f))};add("SAVE"){Toast.makeText(this,if(NativeBridge.saveState(stateFile.absolutePath))"State tersimpan" else "Save gagal",Toast.LENGTH_SHORT).show()};add("LOAD"){Toast.makeText(this,if(stateFile.exists()&&NativeBridge.loadState(stateFile.absolutePath))"State dimuat" else "Load gagal",Toast.LENGTH_SHORT).show()};r.addView(Button(this).apply{text="FAST 2×";setOnClickListener{fastForward=!fastForward;text=if(fastForward)"FAST ON" else "FAST 2×";gameView?.onFastForwardChanged(fastForward)}},LinearLayout.LayoutParams(0,-2,1f));add("RESET"){NativeBridge.reset()};return r}
    private fun buildAnalogControls():View{val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};listOf("A←" to Pair(-32767,0),"A↑" to Pair(0,-32767),"A↓" to Pair(0,32767),"A→" to Pair(32767,0)).forEach{(l,a)->r.addView(Button(this).apply{text=l;setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{if(a.first!=0)analogX=a.first;if(a.second!=0)analogY=a.second};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(a.first!=0)analogX=0;if(a.second!=0)analogY=0}};NativeBridge.setAnalog(analogX,analogY);true}},LinearLayout.LayoutParams(0,-2,1f))};return r}
    private fun buildControls(c:String):View{val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};val x=mutableListOf("←" to 6,"↑" to 4,"↓" to 5,"→" to 7,"L" to 10,"SELECT" to 2,"START" to 3,"R" to 11,"B" to 0,"A" to 8);if(c in setOf("snes9x","pcsx","ppsspp")){x.add("Y" to 1);x.add("X" to 9)};if(c=="pcsx"){x.add("L2" to 12);x.add("R2" to 13)};x.forEach{(l,id)->r.addView(Button(this).apply{text=l;minWidth=0;setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->NativeBridge.setButton(id,true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->NativeBridge.setButton(id,false)};true}},LinearLayout.LayoutParams(0,-2,if(l.length>2)1.5f else 1f))};return r}
    override fun onDestroy(){gameView?.stop();NativeBridge.setAnalog(0,0);NativeBridge.unload();super.onDestroy()}

    inner class GameView:View(this@GameActivity),Runnable{
        private val running=AtomicBoolean(false); private var frameW=NativeBridge.getWidth().coerceAtLeast(1);private var frameH=NativeBridge.getHeight().coerceAtLeast(1)
        /* PS1 changes between 256/320/368/512/640 widths during boot/gameplay. Allocate for the
           largest software frame instead of freezing the initial geometry. */
        private val pixels=IntArray(2048*2048); @Volatile private var bitmap=Bitmap.createBitmap(frameW,frameH,Bitmap.Config.ARGB_8888);private val paint=Paint(Paint.FILTER_BITMAP_FLAG);private var thread:Thread?=null;private val audioScratch=ShortArray(8192);private var audioTrack:AudioTrack?=null
        fun start(){val rate=NativeBridge.getSampleRate().coerceAtLeast(8000);val min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096);audioTrack=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(maxOf(min*2,rate*4/12)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build().also{it.play()};if(running.compareAndSet(false,true))thread=Thread(this,"EmuFrame").also{it.start()}}
        fun onFastForwardChanged(e:Boolean){try{audioTrack?.pause();audioTrack?.flush();if(!e)audioTrack?.play()}catch(_:Exception){}}
        fun stop(){running.set(false);thread?.interrupt();try{thread?.join(500)}catch(_:Exception){};try{audioTrack?.pause();audioTrack?.flush();audioTrack?.stop()}catch(_:Exception){};audioTrack?.release();audioTrack=null}
        override fun run(){while(running.get()){val n=NativeBridge.runFrame(pixels);if(n>0){val w=NativeBridge.getWidth().coerceIn(1,2048);val h=NativeBridge.getHeight().coerceIn(1,2048);if(w*h<=pixels.size){if(w!=frameW||h!=frameH){frameW=w;frameH=h;bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)};bitmap.setPixels(pixels,0,w,0,0,w,h);postInvalidate()}};if(fastForward){while(NativeBridge.readAudio(audioScratch)>0){};try{Thread.sleep(8)}catch(_:Exception){break}}else{var c=NativeBridge.readAudio(audioScratch);while(c>0&&running.get()){var o=0;while(o<c&&running.get()){val z=audioTrack?.write(audioScratch,o,c-o,AudioTrack.WRITE_BLOCKING)?:-1;if(z>0)o+=z else break};c=NativeBridge.readAudio(audioScratch)}}}}
        override fun onDraw(c:Canvas){super.onDraw(c);val b=bitmap;val w=frameW.coerceAtLeast(1);val h=frameH.coerceAtLeast(1);val s=minOf(width.toFloat()/w,height.toFloat()/h);val dw=w*s;val dh=h*s;c.drawBitmap(b,null,android.graphics.RectF((width-dw)/2f,(height-dh)/2f,(width+dw)/2f,(height+dh)/2f),paint)}
    }
}
