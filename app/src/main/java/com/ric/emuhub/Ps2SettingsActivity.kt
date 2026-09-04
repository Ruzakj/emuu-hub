package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class Ps2SettingsActivity : Activity() {
    private var profile = Ps2Profile("Auto Z9x", 2f, -2, 0, true, 0)
    private lateinit var summary: TextView
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); profile=Ps2Settings.load(this); render()
    }

    private fun render(){
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK)}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(22),dp(20),dp(30))}
        root.addView(TextView(this).apply{text="PS2 SETTINGS";textSize=26f;setTextColor(Color.WHITE);setTypeface(typeface,Typeface.BOLD)})
        root.addView(TextView(this).apply{text="iQOO Z9x • Snapdragon 6 Gen 1 • ARMSX2";textSize=12f;setTextColor(0xFF888888.toInt())})
        summary=TextView(this).apply{textSize=13f;setTextColor(0xFFCCCCCC.toInt());setPadding(0,dp(18),0,dp(12))};root.addView(summary)

        addButton(root,"SCREEN SIZE / ASPECT") {
            val labels=Ps2DisplaySettings.MODES.keys.toTypedArray()
            choose("PS2 Screen Size",labels){ label ->
                Ps2DisplaySettings.save(this, Ps2DisplaySettings.MODES.getValue(label))
                updateSummary()
            }
        }
        addButton(root,"PRESET") { choose("Preset", arrayOf("Auto Z9x","Balanced","Performance","Max Performance")){ profile=Ps2Settings.preset(it);save() } }
        addButton(root,"INTERNAL RESOLUTION") { choose("Resolution", arrayOf("1×","1.5×","2×","2.5×","3×")){ profile=profile.copy(preset="Custom",upscale=it.removeSuffix("×").toFloat());save() } }
        addButton(root,"EE CYCLE RATE") { choose("EE Cycle Rate", arrayOf("100% (0)","75% (-1)","60% (-2)","50% (-3)")){ val v=when{it.contains("-3")->-3;it.contains("-2")->-2;it.contains("-1")->-1;else->0};profile=profile.copy(preset="Custom",eeRate=v);save() } }
        addButton(root,"EE CYCLE SKIP") { choose("EE Cycle Skip", arrayOf("Off (0)","Mild (1)","Medium (2)")){ val v=when{it.contains("2")->2;it.contains("1")->1;else->0};profile=profile.copy(preset="Custom",eeSkip=v);save() } }
        addButton(root,"CPU AFFINITY") { choose("CPU Affinity", arrayOf("Auto / No Pinning","Performance Cores")){profile=profile.copy(preset="Custom",affinity=if(it.startsWith("Performance"))7 else 0);save()} }
        root.addView(Switch(this).apply{text="MTVU / VU Thread";textSize=15f;setTextColor(Color.WHITE);isChecked=profile.mtvu;setPadding(0,dp(12),0,dp(12));setOnCheckedChangeListener{_,v->profile=profile.copy(preset="Custom",mtvu=v);save()}},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        addButton(root,"RESET RECOMMENDED Z9X") { profile=Ps2Settings.preset("Auto Z9x");Ps2DisplaySettings.save(this,"Stretch");save() }
        addButton(root,"PS2 BIOS / DIAGNOSTIC") { startActivity(Intent(this,Ps2BiosActivity::class.java)) }
        root.addView(TextView(this).apply{text="Screen Size/Aspect diterapkan saat game PS2 berikutnya dibuka. Full Screen / Stretch mengisi seluruh layar; 16:9 dan 4:3 mempertahankan rasio pilihan.";textSize=11f;setTextColor(0xFF777777.toInt());setPadding(0,dp(16),0,0)})
        scroll.addView(root);setContentView(scroll);updateSummary()
    }

    private fun addButton(root:LinearLayout,label:String,action:()->Unit){root.addView(Button(this).apply{text=label;isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(0xFF151515.toInt());gravity=Gravity.START or Gravity.CENTER_VERTICAL;setOnClickListener{action()}},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)).apply{topMargin=dp(8)})}
    private fun choose(title:String,items:Array<String>,done:(String)->Unit){AlertDialog.Builder(this).setTitle(title).setItems(items){d,w->done(items[w]);d.dismiss()}.show()}
    private fun save(){Ps2Settings.save(this,profile);updateSummary()}
    private fun updateSummary(){if(::summary.isInitialized)summary.text="${profile.preset} • ${profile.upscale}× • ${Ps2DisplaySettings.label(this)} • EE ${profile.eeRate} • Skip ${profile.eeSkip} • MTVU ${if(profile.mtvu)"ON" else "OFF"} • ${if(profile.affinity==7)"Perf Cores" else "Auto CPU"}"}
}
