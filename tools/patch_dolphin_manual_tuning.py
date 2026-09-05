from pathlib import Path

repo = Path('.')

# NativeBridge
p = repo/'app/src/main/java/com/ric/emuhub/core/NativeBridge.kt'
s = p.read_text()
needle = '    external fun setLogPath(path: String)\n'
if 'configureDolphin' not in s:
    s = s.replace(needle, needle + '    external fun configureDolphin(cpuClockPercent: Int, presentDivisor: Int, controllerDevice: Int)\n')
p.write_text(s)

# emuhost.cpp
p = repo/'app/src/main/cpp/emuhost.cpp'
s = p.read_text()
old = 'static void* core=nullptr; static std::string systemDir,saveDir,runtimeLogPath; static int pixelFormat=RETRO_PIXEL_FORMAT_0RGB1555;'
new = 'static void* core=nullptr; static std::string systemDir,saveDir,runtimeLogPath; static int dolphinCpuClockPercent=100,dolphinPresentDivisor=2; static unsigned dolphinControllerDevice=0x401u; static std::string dolphinCpuClockValue="100%"; static int pixelFormat=RETRO_PIXEL_FORMAT_0RGB1555;'
if old in s:
    s = s.replace(old,new,1)

s = s.replace('if(!strcmp(k,"dolphin_cpu_clock_rate"))return "100%";', 'if(!strcmp(k,"dolphin_cpu_clock_rate"))return dolphinCpuClockValue.c_str();')

marker = 'template<typename T>static bool sym(T&o,const char*n){o=reinterpret_cast<T>(dlsym(core,n));return o!=nullptr;}template<typename T>static void opt(T&o,const char*n){o=reinterpret_cast<T>(dlsym(core,n));}\n'
if 'NativeBridge_configureDolphin' not in s:
    inject = '''extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_configureDolphin(JNIEnv*,jobject,jint cpu,jint present,jint controller){
    dolphinCpuClockPercent=std::max(60,std::min(100,(int)cpu));
    dolphinPresentDivisor=std::max(1,std::min(3,(int)present));
    switch((unsigned)controller){case 0x201u:case 0x301u:case 0x401u:case 0x501u:case 0x601u:dolphinControllerDevice=(unsigned)controller;break;default:dolphinControllerDevice=0x401u;break;}
    dolphinCpuClockValue=std::to_string(dolphinCpuClockPercent)+"%";
    char b[160];std::snprintf(b,sizeof(b),"dolphin config: cpu=%d presentDiv=%d controller=0x%x",dolphinCpuClockPercent,dolphinPresentDivisor,dolphinControllerDevice);traceLine(b);
}
'''
    s = s.replace(marker, marker+inject,1)

# Present divisor: perform readback only every Nth HW callback.
oldcap = '    if(!w||!h||w>1024||h>1024)return;\n    frameW=w;frameH=h;\n    const size_t bytes=(size_t)w*h*4;'
newcap = '    if(!w||!h||w>1024||h>1024)return;\n    frameW=w;frameH=h;\n    if(isDolphin&&dolphinPresentDivisor>1){dolphinPresentDiv=(dolphinPresentDiv+1)%((unsigned)dolphinPresentDivisor);if(dolphinPresentDiv!=0)return;}\n    const size_t bytes=(size_t)w*h*4;'
s = s.replace(oldcap,newcap,1)

s = s.replace('p_retro_set_controller_port_device(0,isDolphin?0x401u:(isPpsspp?RETRO_DEVICE_ANALOG:RETRO_DEVICE_JOYPAD));', 'p_retro_set_controller_port_device(0,isDolphin?dolphinControllerDevice:(isPpsspp?RETRO_DEVICE_ANALOG:RETRO_DEVICE_JOYPAD));')
s = s.replace('traceLine(isDolphin?"loadGame: attach Wii Classic Controller 0x401":"loadGame: before controller device");', 'traceLine(isDolphin?"loadGame: attach configured Dolphin controller":"loadGame: before controller device");')
p.write_text(s)

# GameActivity
p = repo/'app/src/main/java/com/ric/emuhub/GameActivity.kt'
s = p.read_text()
if 'import android.app.AlertDialog' not in s:
    s = s.replace('import android.app.Activity\n','import android.app.Activity\nimport android.app.AlertDialog\n')
if 'private lateinit var currentRomPath' not in s:
    s = s.replace('    private var cleanedUp = false\n', '    private var cleanedUp = false\n    private lateinit var currentRomPath: String\n    private var dolphinTuning = DolphinPerGameProfile()\n')
s = s.replace('        val coreId = intent.getStringExtra("coreId") ?: "mgba"\n', '        val coreId = intent.getStringExtra("coreId") ?: "mgba"\n        currentRomPath = rom\n        if(coreId=="dolphin") dolphinTuning = DolphinPerGameSettings.load(this, rom)\n')
oldinit = '        traceCoreStage("native_init", coreId, romName)\n        if (!NativeBridge.init(corePath, systemRoot.absolutePath, saveDir.absolutePath))'
newinit = '        traceCoreStage("native_init", coreId, romName)\n        if(coreId=="dolphin"){ dolphinTuning=DolphinPerGameSettings.load(this,rom); gameProfile=gameProfile.copy(audioBufferScale=dolphinTuning.audioBufferScale); NativeBridge.configureDolphin(dolphinTuning.cpuClockPercent,dolphinTuning.presentDivisor,dolphinTuning.controllerDevice) }\n        if (!NativeBridge.init(corePath, systemRoot.absolutePath, saveDir.absolutePath))'
s = s.replace(oldinit,newinit,1)

# add tuning helpers before buildGamepadOverlay
marker2 = '    private fun buildGamepadOverlay(coreId:String):View{\n'
if 'showDolphinTuningMenu' not in s:
    helper = '''    private fun showDolphinTuningMenu(){
        dolphinTuning=DolphinPerGameSettings.load(this,currentRomPath)
        val controllerName=when(dolphinTuning.controllerDevice){0x201->"Wiimote Sideways";0x301->"Wiimote + Nunchuk";0x501->"Classic Pro";0x601->"GameCube Pad";else->"Classic Controller"}
        val items=arrayOf(
            "CPU Clock: ${dolphinTuning.cpuClockPercent}%",
            "Presentasi: ${when(dolphinTuning.presentDivisor){1->"60 fps";2->"30 fps";else->"20 fps"}}",
            "Audio Buffer: ${dolphinTuning.audioBufferScale}x",
            "Controller: $controllerName",
            "Reset ke default"
        )
        AlertDialog.Builder(this).setTitle("Dolphin Manual Tuning").setItems(items){_,which->when(which){
            0->chooseDolphinCpu();1->chooseDolphinPresentation();2->chooseDolphinAudio();3->chooseDolphinController();4->{DolphinPerGameSettings.reset(this,currentRomPath);Toast.makeText(this,"Dolphin tuning direset. Restart game.",Toast.LENGTH_LONG).show()}
        }}.setNegativeButton("Tutup",null).show()
    }
    private fun chooseDolphinCpu(){val values=intArrayOf(100,90,80,70,60);val labels=values.map{"$it%"}.toTypedArray();val checked=values.indexOf(dolphinTuning.cpuClockPercent).coerceAtLeast(0);AlertDialog.Builder(this).setTitle("CPU Clock").setSingleChoiceItems(labels,checked){d,w->dolphinTuning=dolphinTuning.copy(cpuClockPercent=values[w]);DolphinPerGameSettings.save(this,currentRomPath,dolphinTuning);d.dismiss();Toast.makeText(this,"CPU ${values[w]}% tersimpan. Restart game.",Toast.LENGTH_LONG).show()}.show()}
    private fun chooseDolphinPresentation(){val values=intArrayOf(1,2,3);val labels=arrayOf("60 fps - paling halus, paling berat","30 fps - rekomendasi","20 fps - paling ringan");val checked=values.indexOf(dolphinTuning.presentDivisor).coerceAtLeast(0);AlertDialog.Builder(this).setTitle("Presentation / Readback").setSingleChoiceItems(labels,checked){d,w->dolphinTuning=dolphinTuning.copy(presentDivisor=values[w]);DolphinPerGameSettings.save(this,currentRomPath,dolphinTuning);d.dismiss();Toast.makeText(this,"Presentation tersimpan. Restart game.",Toast.LENGTH_LONG).show()}.show()}
    private fun chooseDolphinAudio(){val values=intArrayOf(1,2,3);val labels=arrayOf("1x - latency rendah","2x - stabil","3x - anti putus");val checked=values.indexOf(dolphinTuning.audioBufferScale).coerceAtLeast(0);AlertDialog.Builder(this).setTitle("Audio Buffer").setSingleChoiceItems(labels,checked){d,w->dolphinTuning=dolphinTuning.copy(audioBufferScale=values[w]);DolphinPerGameSettings.save(this,currentRomPath,dolphinTuning);d.dismiss();Toast.makeText(this,"Audio buffer tersimpan. Restart game.",Toast.LENGTH_LONG).show()}.show()}
    private fun chooseDolphinController(){val values=intArrayOf(0x401,0x301,0x201,0x501,0x601);val labels=arrayOf("Classic Controller","Wiimote + Nunchuk","Wiimote Sideways","Classic Controller Pro","GameCube Pad");val checked=values.indexOf(dolphinTuning.controllerDevice).coerceAtLeast(0);AlertDialog.Builder(this).setTitle("Wii Controller").setSingleChoiceItems(labels,checked){d,w->dolphinTuning=dolphinTuning.copy(controllerDevice=values[w]);DolphinPerGameSettings.save(this,currentRomPath,dolphinTuning);d.dismiss();Toast.makeText(this,"Controller tersimpan. Restart game.",Toast.LENGTH_LONG).show()}.show()}

'''
    s = s.replace(marker2, helper+marker2,1)

oldtools = '        val overlay=FrameLayout(this);val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}\n        listOf("SAVE","LOAD","FAST","RESET").forEach{label->val b=smallOverlayButton(label){button->when(label){"SAVE"->Toast.makeText(this,if(NativeBridge.saveState(stateFile.absolutePath))"State tersimpan" else "Save gagal",Toast.LENGTH_SHORT).show();"LOAD"->Toast.makeText(this,if(stateFile.exists()&&NativeBridge.loadState(stateFile.absolutePath))"State dimuat" else "Load gagal",Toast.LENGTH_SHORT).show();"FAST"->{fastForward=!fastForward;button.text=if(fastForward)"FAST ON" else "FAST";gameView?.onFastForwardChanged(fastForward)};"RESET"->NativeBridge.reset()}};tools.addView(b,LinearLayout.LayoutParams(dp(62),dp(34)).apply{marginEnd=dp(5)})}'
newtools = '        val overlay=FrameLayout(this);val tools=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}\n        val toolLabels=if(coreId=="dolphin") listOf("SAVE","LOAD","FAST","RESET","TUNE") else listOf("SAVE","LOAD","FAST","RESET")\n        toolLabels.forEach{label->val b=smallOverlayButton(label){button->when(label){"SAVE"->Toast.makeText(this,if(NativeBridge.saveState(stateFile.absolutePath))"State tersimpan" else "Save gagal",Toast.LENGTH_SHORT).show();"LOAD"->Toast.makeText(this,if(stateFile.exists()&&NativeBridge.loadState(stateFile.absolutePath))"State dimuat" else "Load gagal",Toast.LENGTH_SHORT).show();"FAST"->{fastForward=!fastForward;button.text=if(fastForward)"FAST ON" else "FAST";gameView?.onFastForwardChanged(fastForward)};"RESET"->NativeBridge.reset();"TUNE"->showDolphinTuningMenu()}};tools.addView(b,LinearLayout.LayoutParams(dp(62),dp(34)).apply{marginEnd=dp(5)})}'
if oldtools in s:
    s = s.replace(oldtools,newtools,1)
else:
    raise SystemExit('tools block not found')
p.write_text(s)

print('manual Dolphin tuning patch applied')
