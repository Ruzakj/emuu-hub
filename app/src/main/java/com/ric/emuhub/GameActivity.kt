package com.ric.emuhub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ric.emuhub.core.NativeBridge
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipInputStream
import kotlin.math.min
import kotlin.math.sqrt

class GameActivity : Activity() {
    private var gameView: GameView? = null
    @Volatile private var fastForward = false
    private lateinit var stateFile: File
    private lateinit var gameProfile: GameProfile
    private var cleanedUp = false
    private lateinit var coreTraceFile: File
    private val coreTracePrefs by lazy { getSharedPreferences("core_runtime_trace", MODE_PRIVATE) }

    data class GameProfile(val id:String,val label:String,val audioBufferScale:Int,val priority:Int,val videoFilter:Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra("romPath") ?: run { finish(); return }
        val coreId = intent.getStringExtra("coreId") ?: "mgba"
        val romName = intent.getStringExtra("romName") ?: File(rom).name
        gameProfile = resolveGameProfile(coreId, romName)
        val coreFile = when (coreId) {
            "fceumm" -> "libfceumm_core.so"; "snes9x" -> "libsnes9x_core.so"; "pcsx" -> "libpcsx_rearmed_core.so"; "ppsspp" -> "libppsspp_core.so"; else -> "libmgba_core.so"
        }
        val coreLabel = when (coreId) { "fceumm" -> "FCEUmm"; "snes9x" -> "Snes9x"; "pcsx" -> "PCSX-ReARMed"; "ppsspp" -> "PPSSPP"; else -> "mGBA" }
        val systemRoot = StoragePaths.systemDir(this)
        coreTraceFile = File(StoragePaths.root(this), "CORE/core-runtime.log").apply { parentFile?.mkdirs() }
        NativeBridge.setLogPath(coreTraceFile.absolutePath)
        traceCoreStage("prepare", coreId, romName)
        if (coreId == "ppsspp") installPpssppAssets(systemRoot)
        continueCoreLaunch(rom, coreId, romName, coreFile, coreLabel, systemRoot)
    }

    private fun continueCoreLaunch(rom:String,coreId:String,romName:String,coreFile:String,coreLabel:String,systemRoot:File){
        val saveDir = StoragePaths.savesDir(this)
        stateFile = File(StoragePaths.statesDir(this), "${coreId}_${safeStateKey(romName)}_slot0.state")
        traceCoreStage("resolve_core", coreId, romName)
        val corePath = EnginePackManager.corePath(this, coreFile) ?: (applicationInfo.nativeLibraryDir + "/$coreFile")
        traceCoreStage("resolve_core_done", coreId, romName)
        if (!File(corePath).isFile) {
            EnginePackManager.bootstrapAsync(this)
            showLoadError("$coreLabel engine belum siap. Engine Pack sedang disiapkan; coba lagi setelah selesai.")
            return
        }
        traceCoreStage("native_init", coreId, romName)
        if (!NativeBridge.init(corePath, systemRoot.absolutePath, saveDir.absolutePath)) { traceCoreStage("native_init_failed", coreId, romName, false); showLoadError("$coreLabel core gagal inisialisasi. Log: emu-hub/CORE/core-runtime.log"); return }
        traceCoreStage("native_init_ok", coreId, romName)
        if (coreId == "ppsspp") NativeBridge.setControllerDevice(1)
        traceCoreStage("load_game", coreId, romName)
        if (!NativeBridge.loadGame(rom)) { traceCoreStage("load_game_failed", coreId, romName, false); showLoadError("$coreLabel gagal memuat ${File(rom).name}. Log: emu-hub/CORE/core-runtime.log"); return }
        traceCoreStage("load_game_ok", coreId, romName)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF050507.toInt()) }
        gameView = GameView(coreId, gameProfile).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(buildGamepadOverlay(coreId), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        enableSafeFullscreen()
        gameView?.start()
    }

    private fun showPreparingScreen(message:String){
        setContentView(TextView(this).apply {
            text=message
            gravity=Gravity.CENTER
            textSize=18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF050507.toInt())
            setPadding(dp(24),dp(24),dp(24),dp(24))
        })
    }

    private fun traceCoreStage(stage:String,coreId:String,romName:String,active:Boolean=true){
        coreTracePrefs.edit().putBoolean("active",active).putString("stage",stage).putString("core",coreId).putString("game",romName).putLong("time",System.currentTimeMillis()).commit()
        runCatching { coreTraceFile.parentFile?.mkdirs(); coreTraceFile.appendText("JAVA ${System.currentTimeMillis()} stage=$stage core=$coreId game=$romName\n") }
    }

    private fun resolveGameProfile(coreId:String,romName:String):GameProfile{
        val n=romName.lowercase()
        return when{
            coreId=="ppsspp" && ("god of war" in n || "gow" in n) -> GameProfile("psp-heavy","Z9x Heavy PSP",2,Process.THREAD_PRIORITY_URGENT_DISPLAY,true)
            coreId=="pcsx" && ("final fantasy ix" in n || "final fantasy 9" in n || "ff9" in n) -> GameProfile("ps1-rpg","Z9x PS1 RPG",2,Process.THREAD_PRIORITY_URGENT_DISPLAY,true)
            coreId=="ppsspp" -> GameProfile("psp-balanced","Z9x PSP Balanced",2,Process.THREAD_PRIORITY_URGENT_DISPLAY,true)
            coreId=="pcsx" -> GameProfile("ps1-balanced","Z9x PS1 Balanced",2,Process.THREAD_PRIORITY_URGENT_DISPLAY,true)
            else -> GameProfile("classic","Classic",3,Process.THREAD_PRIORITY_DISPLAY,true)
        }
    }

    private fun enableSafeFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enableSafeFullscreen() }
    @Deprecated("Framework compatibility") override fun onBackPressed() { shutdownCore(); finish() }
    private fun shutdownCore() { if (cleanedUp) return; cleanedUp=true; gameView?.stop(); NativeBridge.setAnalog(0,0); NativeBridge.unload(); if(::coreTraceFile.isInitialized) coreTracePrefs.edit().putBoolean("active",false).putString("stage","clean_exit").apply() }
    private fun showLoadError(message:String){setContentView(TextView(this).apply{text=message;gravity=Gravity.CENTER;textSize=18f;setTextColor(0xFFFFFFFF.toInt());setBackgroundColor(0xFF050507.toInt());setPadding(dp(24),dp(24),dp(24),dp(24))})}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun padShape(round:Boolean=false, pressed:Boolean=false, alpha:Int=154)=GradientDrawable().apply{
        shape=if(round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        cornerRadius=if(round) 999f else dp(18).toFloat()
        if(pressed){
            setColor(Color.argb((alpha+50).coerceAtMost(235),46,92,128))
            setStroke(dp(2),Color.argb(225,184,226,255))
        }else{
            setColor(Color.argb(alpha,19,22,27))
            setStroke(dp(1),Color.argb(118,255,255,255))
        }
    }
    private fun padState(round:Boolean=false,alpha:Int=154)=StateListDrawable().apply{
        addState(intArrayOf(android.R.attr.state_pressed),padShape(round,true,alpha))
        addState(intArrayOf(),padShape(round,false,alpha))
    }
    private fun controllerShell()=View(this).apply{
        background=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=dp(44).toFloat();setColor(Color.argb(58,13,15,19));setStroke(dp(1),Color.argb(54,255,255,255))}
    }
    private fun translucentBackground(alpha:Int=120,stroke:Boolean=true,radiusDp:Int=18)=padShape(false,false,alpha)
    private fun roundBackground(alpha:Int=105)=padShape(true,false,alpha)
    private fun gameButton(label:String,id:Int,sizeDp:Int=58)=Button(this).apply{
        val round=label !in setOf("L","R","L1","R1","L2","R2","SELECT","START")
        text=label;textSize=if(label.length>3)9f else if(label.length>2)10f else 17f
        typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
        minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0;includeFontPadding=false;setPadding(0,0,0,0)
        background=padState(round,if(round)145 else 132);alpha=.92f;setTextColor(Color.WHITE);stateListAnimator=null
        setOnTouchListener{v,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{NativeBridge.setButton(id,true);v.alpha=1f};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{NativeBridge.setButton(id,false);v.alpha=.92f}};true}
    }
    private fun smallOverlayButton(label:String,onClick:(Button)->Unit)=Button(this).apply{
        text=label;textSize=9f;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);minWidth=0;minHeight=0;minimumWidth=0;minimumHeight=0
        setPadding(dp(8),0,dp(8),0);setTextColor(Color.WHITE);background=padState(false,105);alpha=.86f;stateListAnimator=null;setOnClickListener{onClick(this)}
    }

    private fun buildGamepadOverlay(coreId:String):View{
        val overlay=FrameLayout(this)
        val dualStick=coreId=="pcsx"||coreId=="ppsspp"
        overlay.addView(controllerShell(),FrameLayout.LayoutParams(dp(if(dualStick)350 else 210),dp(194),Gravity.BOTTOM or Gravity.START).apply{leftMargin=dp(8);bottomMargin=dp(5)})
        overlay.addView(controllerShell(),FrameLayout.LayoutParams(dp(220),dp(194),Gravity.BOTTOM or Gravity.END).apply{rightMargin=dp(8);bottomMargin=dp(5)})
        val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        listOf("SAVE","LOAD","FAST","RESET").forEach{label->val b=smallOverlayButton(label){button->when(label){"SAVE"->Toast.makeText(this,if(NativeBridge.saveState(stateFile.absolutePath))"State tersimpan" else "Save gagal",Toast.LENGTH_SHORT).show();"LOAD"->Toast.makeText(this,if(stateFile.exists()&&NativeBridge.loadState(stateFile.absolutePath))"State dimuat" else "Load gagal",Toast.LENGTH_SHORT).show();"FAST"->{fastForward=!fastForward;button.text=if(fastForward)"FAST ON" else "FAST";gameView?.onFastForwardChanged(fastForward)};"RESET"->NativeBridge.reset()}};tools.addView(b,LinearLayout.LayoutParams(dp(62),dp(34)).apply{marginEnd=dp(5)})}
        overlay.addView(tools,FrameLayout.LayoutParams(-2,dp(38),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(8)})
        overlay.addView(gameButton("L",10,64),FrameLayout.LayoutParams(dp(64),dp(42),Gravity.TOP or Gravity.START).apply{leftMargin=dp(28);topMargin=dp(58)})
        overlay.addView(gameButton("R",11,64),FrameLayout.LayoutParams(dp(64),dp(42),Gravity.TOP or Gravity.END).apply{rightMargin=dp(28);topMargin=dp(58)})
        if(coreId=="pcsx"){overlay.addView(gameButton("L2",12,52),FrameLayout.LayoutParams(dp(52),dp(38),Gravity.TOP or Gravity.START).apply{leftMargin=dp(106);topMargin=dp(60)});overlay.addView(gameButton("R2",13,52),FrameLayout.LayoutParams(dp(52),dp(38),Gravity.TOP or Gravity.END).apply{rightMargin=dp(106);topMargin=dp(60)})}
        if(coreId=="pcsx"||coreId=="ppsspp")overlay.addView(AnalogStickView(),FrameLayout.LayoutParams(dp(150),dp(150),Gravity.BOTTOM or Gravity.START).apply{leftMargin=dp(30);bottomMargin=dp(20)})
        overlay.addView(DPadView(),FrameLayout.LayoutParams(dp(138),dp(138),Gravity.BOTTOM or Gravity.START).apply{leftMargin=if(coreId=="pcsx"||coreId=="ppsspp")dp(188) else dp(42);bottomMargin=dp(26)})
        val face=FrameLayout(this);fun addFace(label:String,id:Int,x:Int,y:Int){face.addView(gameButton(label,id,58),FrameLayout.LayoutParams(dp(58),dp(58),Gravity.TOP or Gravity.START).apply{leftMargin=dp(x);topMargin=dp(y)})}
        when(coreId){"pcsx","ppsspp"->{addFace("△",9,60,0);addFace("○",8,120,60);addFace("×",0,60,120);addFace("□",1,0,60)};"snes9x"->{addFace("X",9,60,0);addFace("A",8,120,60);addFace("B",0,60,120);addFace("Y",1,0,60)};else->{addFace("A",8,105,45);addFace("B",0,35,90)}}
        overlay.addView(face,FrameLayout.LayoutParams(dp(178),dp(178),Gravity.BOTTOM or Gravity.END).apply{rightMargin=dp(28);bottomMargin=dp(18)})
        val center=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER};center.addView(gameButton("SELECT",2,64),LinearLayout.LayoutParams(dp(72),dp(38)).apply{marginEnd=dp(10)});center.addView(gameButton("START",3,64),LinearLayout.LayoutParams(dp(72),dp(38)));overlay.addView(center,FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(20)});return overlay
    }

    inner class AnalogStickView:View(this@GameActivity){
        private val basePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(94,17,20,25)}
        private val ringPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(132,255,255,255);style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat()}
        private val guidePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(48,255,255,255);style=Paint.Style.STROKE;strokeWidth=dp(1).toFloat()}
        private val knobPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(215,76,81,90)}
        private val knobStroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(170,255,255,255);style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat()}
        private val shinePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(46,255,255,255)}
        private var knobX=0f;private var knobY=0f;private var active=false
        override fun onDraw(canvas:Canvas){
            val cx=width/2f;val cy=height/2f;val outer=min(width,height)*.45f;val knob=outer*.43f
            canvas.drawCircle(cx,cy,outer,basePaint);canvas.drawCircle(cx,cy,outer,ringPaint);canvas.drawCircle(cx,cy,outer*.70f,guidePaint);canvas.drawCircle(cx,cy,outer*.36f,guidePaint)
            knobPaint.color=if(active)Color.rgb(57,105,142) else Color.rgb(76,81,90)
            canvas.drawCircle(cx+knobX,cy+knobY,knob,knobPaint);canvas.drawCircle(cx+knobX,cy+knobY,knob,knobStroke)
            canvas.drawCircle(cx+knobX-knob*.22f,cy+knobY-knob*.24f,knob*.30f,shinePaint)
        }
        override fun onTouchEvent(e:MotionEvent):Boolean{
            val cx=width/2f;val cy=height/2f;val max=min(width,height)*.34f
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{active=true;var dx=e.x-cx;var dy=e.y-cy;val d=sqrt(dx*dx+dy*dy);if(d>max&&d>0f){dx=dx/d*max;dy=dy/d*max};knobX=dx;knobY=dy;NativeBridge.setAnalog(((dx/max)*32767).toInt(),((dy/max)*32767).toInt());invalidate()}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{active=false;knobX=0f;knobY=0f;NativeBridge.setAnalog(0,0);invalidate()}
            };return true
        }
    }
    inner class DPadView:View(this@GameActivity){
        private val base=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(160,31,34,40)}
        private val pressed=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(225,49,94,130)}
        private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(135,255,255,255);style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat()}
        private val arrow=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(225,255,255,255);textAlign=Paint.Align.CENTER;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD)}
        private var activeId=-1
        override fun onDraw(c:Canvas){
            val size=min(width,height).toFloat();val cx=width/2f;val cy=height/2f;val arm=size*.34f;val half=size*.48f;val r=arm*.24f
            val v=RectF(cx-arm/2,cy-half,cx+arm/2,cy+half);val h=RectF(cx-half,cy-arm/2,cx+half,cy+arm/2)
            c.drawRoundRect(v,r,r,base);c.drawRoundRect(h,r,r,base);c.drawRoundRect(v,r,r,outline);c.drawRoundRect(h,r,r,outline)
            when(activeId){4->c.drawRoundRect(RectF(cx-arm/2,cy-half,cx+arm/2,cy),r,r,pressed);5->c.drawRoundRect(RectF(cx-arm/2,cy,cx+arm/2,cy+half),r,r,pressed);6->c.drawRoundRect(RectF(cx-half,cy-arm/2,cx,cy+arm/2),r,r,pressed);7->c.drawRoundRect(RectF(cx,cy-arm/2,cx+half,cy+arm/2),r,r,pressed)}
            arrow.textSize=size*.15f;val fm=arrow.fontMetrics;val adj=-(fm.ascent+fm.descent)/2
            c.drawText("▲",cx,cy-size*.29f+adj,arrow);c.drawText("▼",cx,cy+size*.29f+adj,arrow);c.drawText("◀",cx-size*.29f,cy+adj,arrow);c.drawText("▶",cx+size*.29f,cy+adj,arrow)
        }
        private fun idAt(x:Float,y:Float):Int{val dx=x-width/2f;val dy=y-height/2f;return if(kotlin.math.abs(dx)>kotlin.math.abs(dy)){if(dx<0)6 else 7}else{if(dy<0)4 else 5}}
        override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{val id=idAt(e.x,e.y);if(id!=activeId){if(activeId>=0)NativeBridge.setButton(activeId,false);activeId=id;NativeBridge.setButton(id,true);invalidate()}};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(activeId>=0)NativeBridge.setButton(activeId,false);activeId=-1;invalidate()}};return true}
    }


    private fun installPpssppAssets(root:File){
        val target=File(root,"PPSSPP")
        val marker=File(target,".emuhub_assets_v1")
        if(marker.exists())return
        target.mkdirs()
        val packed=EnginePackManager.ppssppAssetsDir(this)
        if(packed!=null) copyFileTree(packed,target) else copyAssetTree("PPSSPP",target)
        if(target.listFiles()?.isNotEmpty()==true) runCatching{marker.writeText("1")}
    }
    private fun copyFileTree(source:File,target:File){
        if(source.isDirectory){target.mkdirs();source.listFiles()?.forEach{copyFileTree(it,File(target,it.name))}}
        else{target.parentFile?.mkdirs();source.inputStream().buffered(1024*1024).use{i->target.outputStream().buffered(1024*1024).use{o->i.copyTo(o,1024*1024)}}}
    }
    private fun copyAssetTree(path:String,target:File){val entries=assets.list(path)?:return;if(entries.isEmpty()){target.parentFile?.mkdirs();assets.open(path).use{i->target.outputStream().use{i.copyTo(it)}};return};target.mkdirs();entries.forEach{copyAssetTree("$path/$it",File(target,it))}}
    private fun safeStateKey(name:String)=MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
    override fun onDestroy(){shutdownCore();super.onDestroy()}

    inner class GameView(private val coreId:String,private val profile:GameProfile):View(this@GameActivity),Runnable{
        private val running=AtomicBoolean(false)
        private var frameW=NativeBridge.getWidth().coerceAtLeast(1);private var frameH=NativeBridge.getHeight().coerceAtLeast(1)
        private val pixels=IntArray(1024*1024)
        @Volatile private var bitmap=Bitmap.createBitmap(frameW,frameH,Bitmap.Config.ARGB_8888)
        private val paint=Paint(if(profile.videoFilter) Paint.FILTER_BITMAP_FLAG else 0)
        private var thread:Thread?=null
        private val audioScratch=ShortArray(16384);private var audioTrack:AudioTrack?=null
        private val framePeriodNs:Long=when(coreId){"mgba"->16_742_706L;"fceumm","snes9x"->16_639_267L;"pcsx"->16_683_350L;else->16_666_667L}

        fun start(){val rate=NativeBridge.getSampleRate().coerceAtLeast(8000);val minBuffer=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096);val latencySensitive=coreId=="ppsspp"||coreId=="pcsx";val bufferBytes=if(latencySensitive)maxOf(minBuffer*profile.audioBufferScale,rate*4/12)else maxOf(minBuffer*3,rate*4/8);val builder=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM);if(latencySensitive&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);audioTrack=builder.build().also{it.play()};if(running.compareAndSet(false,true))thread=Thread(this,"EmuFrame-Z9x-${profile.id}").also{it.start()}}
        fun onFastForwardChanged(enabled:Boolean){try{audioTrack?.pause();audioTrack?.flush();if(!enabled)audioTrack?.play()}catch(_:Exception){}}
        fun stop(){if(!running.getAndSet(false))return;try{audioTrack?.pause();audioTrack?.flush()}catch(_:Exception){};thread?.interrupt();try{thread?.join(1500)}catch(_:Exception){};try{audioTrack?.stop()}catch(_:Exception){};audioTrack?.release();audioTrack=null;thread=null}
        private fun updateVideo(){val w=NativeBridge.getWidth().coerceIn(1,1024);val h=NativeBridge.getHeight().coerceIn(1,1024);if(w*h<=pixels.size){if(w!=frameW||h!=frameH){frameW=w;frameH=h;bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)};bitmap.setPixels(pixels,0,w,0,0,w,h);postInvalidate()}}
        private fun drainAudioNonBlocking(){var count=NativeBridge.readAudio(audioScratch);while(count>0&&running.get()){var off=0;while(off<count&&running.get()){val z=audioTrack?.write(audioScratch,off,count-off,AudioTrack.WRITE_NON_BLOCKING)?:-1;if(z>0)off+=z else break};if(off<count)break;count=NativeBridge.readAudio(audioScratch)}}
        private fun runPpsspp(){while(running.get()){val n=NativeBridge.runFrame(pixels);if(n<0){running.set(false);break};if(n>0)updateVideo();if(fastForward){while(NativeBridge.readAudio(audioScratch)>0){};try{Thread.sleep(8)}catch(_:Exception){break}}else{var count=NativeBridge.readAudio(audioScratch);while(count>0&&running.get()){var off=0;while(off<count&&running.get()){val z=audioTrack?.write(audioScratch,off,count-off,AudioTrack.WRITE_BLOCKING)?:-1;if(z>0)off+=z else break};count=NativeBridge.readAudio(audioScratch)}}}}
        private fun runClassicCore(){var nextFrame=System.nanoTime();while(running.get()){val n=NativeBridge.runFrame(pixels);if(n<0){running.set(false);break};if(n>0)updateVideo();if(fastForward){while(NativeBridge.readAudio(audioScratch)>0){};nextFrame=System.nanoTime();continue};drainAudioNonBlocking();nextFrame+=framePeriodNs;val now=System.nanoTime();val waitNs=nextFrame-now;if(waitNs>0)LockSupport.parkNanos(waitNs)else if(waitNs < -framePeriodNs*3)nextFrame=now}}
        override fun run(){runCatching{Process.setThreadPriority(profile.priority)};if(coreId=="ppsspp")runPpsspp()else runClassicCore()}
        override fun onDraw(c:Canvas){super.onDraw(c);val w=frameW.coerceAtLeast(1);val h=frameH.coerceAtLeast(1);val scale=minOf(width.toFloat()/w,height.toFloat()/h);val dw=w*scale;val dh=h*scale;c.drawBitmap(bitmap,null,android.graphics.RectF((width-dw)/2f,(height-dh)/2f,(width+dw)/2f,(height+dh)/2f),paint)}
    }
}
