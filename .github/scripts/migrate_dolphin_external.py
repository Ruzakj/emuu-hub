from pathlib import Path
import re


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing patch target: {label}")
    return text.replace(old, new)

p = Path("app/src/main/java/com/ric/emuhub/MainActivity.kt")
s = p.read_text()

patches = [
("core sets",
'''        private val SWITCH = setOf("xci","nsp","nro")
        private val ARCHIVES = ArchiveHelper.ARCHIVE_EXTENSIONS
        private val RECOGNIZED = INTERNAL + SWITCH + ARCHIVES
        private val EDEN_PACKAGES = listOf("com.miHoYo.Yuanshen","com.miHoYo.Yunashen","com.miHoYo.Yuanshen.nightly","dev.eden.eden_emulator","dev.eden.eden_nightly")''',
'''        private val SWITCH = setOf("xci","nsp","nro")
        private val GC_WII = setOf("gcm","gcz","rvz","wbfs","wia","wad","dol","elf")
        private val ARCHIVES = ArchiveHelper.ARCHIVE_EXTENSIONS
        private val RECOGNIZED = INTERNAL + SWITCH + GC_WII + ARCHIVES
        private val EDEN_PACKAGES = listOf("com.miHoYo.Yuanshen","com.miHoYo.Yunashen","com.miHoYo.Yuanshen.nightly","dev.eden.eden_emulator","dev.eden.eden_nightly")
        private val DOLPHIN_PACKAGES = listOf("org.dolphinemu.dolphinemu","org.dolphinemu.dolphinemu.dev","org.dolphinemu.mmjr","org.dolphinemu.mmjr2")'''),
("console strip", 'arrayOf("JAVA","JL-Mod","J"),arrayOf("SWITCH","Eden","▰"))', 'arrayOf("JAVA","JL-Mod","J"),arrayOf("GC/WII","External","◉"),arrayOf("SWITCH","Eden","▰"))'),
("folder hint", '            "ps1" in t || "psx" in t || "psone" in t || h.contains("playstation 1")->"PS1"\n            else->null', '            "ps1" in t || "psx" in t || "psone" in t || h.contains("playstation 1")->"PS1"\n            "gamecube" in t || "ngc" in t || "wii" in t || h.contains("game cube")->"GC/WII"\n            else->null'),
("inferred console", '            "sfc","smc"->"SNES"\n            "xci","nsp","nro"->"SWITCH"', '            "sfc","smc"->"SNES"\n            in GC_WII->"GC/WII"\n            "xci","nsp","nro"->"SWITCH"'),
("rank", 'private fun consoleRank(g:GameEntry)=when(inferredConsole(g)){"PSP"->0;"PS1"->1;"PS2"->2;"GBA"->3;"NES"->4;"SNES"->5;"SWITCH"->6;"DISC"->7;"ARCHIVE"->8;else->99}', 'private fun consoleRank(g:GameEntry)=when(inferredConsole(g)){"PSP"->0;"PS1"->1;"PS2"->2;"GBA"->3;"NES"->4;"SNES"->5;"GC/WII"->6;"SWITCH"->7;"DISC"->8;"ARCHIVE"->9;else->99}'),
("group", '        "SNES"->"SNES • SNES9X"\n        "SWITCH"->"NINTENDO SWITCH • EDEN"', '        "SNES"->"SNES • SNES9X"\n        "GC/WII"->"GAMECUBE / WII • EXTERNAL"\n        "SWITCH"->"NINTENDO SWITCH • EDEN"'),
("filters", 'val filters=listOf("ALL","PSP","PS1","PS2","GBA","NES","SNES","SWITCH","JAVA")', 'val filters=listOf("ALL","PSP","PS1","PS2","GBA","NES","SNES","GC/WII","SWITCH","JAVA")'),
("glyph", '"PSP"->"P";"PS1"->"1";"PS2"->"2";"GBA"->"G";"NES"->"N";"SNES"->"S";"SWITCH"->"▰";', '"PSP"->"P";"PS1"->"1";"PS2"->"2";"GBA"->"G";"NES"->"N";"SNES"->"S";"GC/WII"->"◉";"SWITCH"->"▰";'),
("engine", '"PSP"->"PPSSPP";"PS1"->"PCSX";"PS2"->"ARMSX2";"GBA"->"mGBA";"NES"->"FCEUmm";"SNES"->"Snes9x";"SWITCH"->"EDEN";', '"PSP"->"PPSSPP";"PS1"->"PCSX";"PS2"->"ARMSX2";"GBA"->"mGBA";"NES"->"FCEUmm";"SNES"->"Snes9x";"GC/WII"->"EXTERNAL";"SWITCH"->"EDEN";'),
("code", 'private fun systemCode(e:String)=when(e){"gb","gbc","gba"->"GBA";"nes"->"NES";"sfc","smc"->"SNES";"bin","cue"->"PS1";"chd"->"CHD";"ecm"->"ECM";"iso"->"ISO";"cso"->"PSP";"xci","nsp","nro"->"NSW";', 'private fun systemCode(e:String)=when(e){"gb","gbc","gba"->"GBA";"nes"->"NES";"sfc","smc"->"SNES";"bin","cue"->"PS1";"chd"->"CHD";"ecm"->"ECM";"iso"->"ISO";"cso"->"PSP";in GC_WII->"GCW";"xci","nsp","nro"->"NSW";'),
("color", 'private fun systemColor(e:String)=when(e){"xci","nsp","nro"->0xFF243847.toInt();', 'private fun systemColor(e:String)=when(e){in GC_WII->0xFF31413F.toInt();"xci","nsp","nro"->0xFF243847.toInt();'),
("codeFor", '"GBA"->"GBA";"NES"->"NES";"SNES"->"SNES";"SWITCH"->"NSW";', '"GBA"->"GBA";"NES"->"NES";"SNES"->"SNES";"GC/WII"->"GCW";"SWITCH"->"NSW";'),
("nameFor", '"GBA"->"Game Boy • mGBA";"NES"->"NES • FCEUmm";"SNES"->"SNES • Snes9x";"SWITCH"->"Nintendo Switch • Eden";', '"GBA"->"Game Boy • mGBA";"NES"->"NES • FCEUmm";"SNES"->"SNES • Snes9x";"GC/WII"->"GameCube / Wii • external emulator";"SWITCH"->"Nintendo Switch • Eden";'),
("colorFor", '"GBA"->0xFF2D4138.toInt();"NES"->0xFF493237.toInt();"SNES"->0xFF39364A.toInt();"SWITCH"->0xFF243847.toInt();', '"GBA"->0xFF2D4138.toInt();"NES"->0xFF493237.toInt();"SNES"->0xFF39364A.toInt();"GC/WII"->0xFF31413F.toInt();"SWITCH"->0xFF243847.toInt();'),
("library route", '            "GBA","NES","SNES"->copyAndLaunchInternal(uri,g.name,g.ext,null)\n            "SWITCH"->launchEden(uri)', '            "GBA","NES","SNES"->copyAndLaunchInternal(uri,g.name,g.ext,null)\n            "GC/WII"->launchDolphinExternal(uri,g.name)\n            "SWITCH"->launchEden(uri)'),
("direct route", 'private fun openLibraryGame(uri:Uri,name:String,ext:String){when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);', 'private fun openLibraryGame(uri:Uri,name:String,ext:String){when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext in GC_WII->launchDolphinExternal(uri,name);'),
("iso chooser", 'private fun showIsoChooser(uri:Uri,name:String){AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan")){_,which->when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");else->launchPs2OrSetup(uri,name)}}.setNegativeButton("Batal",null).show()}', 'private fun showIsoChooser(uri:Uri,name:String){AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan","GameCube / Wii • External emulator")){_,which->when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");2->launchPs2OrSetup(uri,name);else->launchDolphinExternal(uri,name)}}.setNegativeButton("Batal",null).show()}'),
("archive label", '        "sfc","smc"->"SNES"\n        "xci","nsp","nro"->"SWITCH"', '        "sfc","smc"->"SNES"\n        in GC_WII->"GC/WII"\n        "xci","nsp","nro"->"SWITCH"'),
("archive route", '            "ecm"->decodeExtractedEcm(session,rom)\n            else->launchTempInternalFile(rom.file,coreIdFor(rom.ext),rom.displayName,session.root)', '            "ecm"->decodeExtractedEcm(session,rom)\n            in GC_WII->{pendingArchiveSession=session.root;launchDolphinExternal(Uri.fromFile(rom.file),rom.displayName)}\n            else->launchTempInternalFile(rom.file,coreIdFor(rom.ext),rom.displayName,session.root)'),
("system name", 'private fun systemName(e:String)=when(e){"gb","gbc","gba"->"Game Boy • mGBA";"nes"->"Nintendo Entertainment System • FCEUmm";"sfc","smc"->"Super Nintendo • Snes9x";"bin","cue"->"PlayStation • PCSX-ReARMed";"chd"->"PlayStation / PS2 • choose engine";"ecm"->"PlayStation • ECM auto decode";"iso"->"PS1 / PSP / PS2 • choose engine";"cso"->"PSP • PPSSPP";"xci","nsp","nro"->"Nintendo Switch • Eden Optimized";', 'private fun systemName(e:String)=when(e){"gb","gbc","gba"->"Game Boy • mGBA";"nes"->"Nintendo Entertainment System • FCEUmm";"sfc","smc"->"Super Nintendo • Snes9x";"bin","cue"->"PlayStation • PCSX-ReARMed";"chd"->"PlayStation / PS2 • choose engine";"ecm"->"PlayStation • ECM auto decode";"iso"->"PS1 / PSP / PS2 / GC-Wii • choose engine";"cso"->"PSP • PPSSPP";in GC_WII->"GameCube / Wii • external emulator";"xci","nsp","nro"->"Nintendo Switch • Eden Optimized";'),
("picker route", 'when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext=="ecm"', 'when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext in GC_WII->launchDolphinExternal(uri,name);ext=="ecm"'),
]
for label, old, new in patches:
    s = must_replace(s, old, new, label)

s = must_replace(s, '    private fun launchEden(uri:Uri){', '''    private fun launchDolphinExternal(uri:Uri,name:String){
        val pkg=DOLPHIN_PACKAGES.firstOrNull{packageManager.getLaunchIntentForPackage(it)!=null}?:run{Toast.makeText(this,"Emulator GameCube/Wii eksternal tidak terdeteksi. Install Dolphin atau fork yang kompatibel.",Toast.LENGTH_LONG).show();return}
        val directPath=directGameFile(uri)?.absolutePath
        val attempts=listOf(
            Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,"application/octet-stream");setPackage(pkg);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri("GameCube/Wii ROM",uri)},
            packageManager.getLaunchIntentForPackage(pkg)?.apply{putExtra("AutoStartFile",directPath?:uri.toString());putExtra("romPath",directPath?:uri.toString());putExtra("romName",name)}
        ).filterNotNull()
        for(intent in attempts){try{startActivity(intent);status.text="GameCube/Wii • external launcher";return}catch(_:Exception){}}
        Toast.makeText(this,"Emulator terdeteksi tetapi ROM tidak bisa dikirim langsung.",Toast.LENGTH_LONG).show()
        packageManager.getLaunchIntentForPackage(pkg)?.let{runCatching{startActivity(it)}}
    }

    private fun launchEden(uri:Uri){''', 'launcher function')
p.write_text(s)

p = Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
marker = '        <package android:name="dev.eden.eden_nightly" />\n'
if 'org.dolphinemu.dolphinemu' not in s:
    s = must_replace(s, marker, marker + '        <package android:name="org.dolphinemu.dolphinemu" />\n        <package android:name="org.dolphinemu.dolphinemu.dev" />\n        <package android:name="org.dolphinemu.mmjr" />\n        <package android:name="org.dolphinemu.mmjr2" />\n', 'manifest')
p.write_text(s)

p = Path(".github/workflows/build-apk.yml")
s = p.read_text()
s = re.sub(r'\n      - name: Stage embedded Ishiiruka 3\.6\.4-r1\n        run: bash scripts/stage-ishiiruka-embedded\.sh\n', '\n', s)
s = s.replace('          test -s embedded/ishiiruka/src/main/jniLibs/arm64-v8a/libmain.so\n', '')
s = s.replace('          unzip -l "$APK" | grep \'lib/arm64-v8a/libmain.so\'\n', '')
s = s.replace('name: EmuHub-Ishiiruka-Embedded-debug', 'name: EmuHub-External-GCWii-debug')
p.write_text(s)

p = Path(".github/workflows/release-apk.yml")
s = p.read_text()
s = s.replace('      - name: Download internal libretro cores including Dolphin', '      - name: Download internal libretro cores')
s = s.replace('          fetch_core dolphin libdolphin_core.so\n          test -s app/src/main/jniLibs/arm64-v8a/libdolphin_core.so\n', '')
s = re.sub(r'\n      - name: Stage Dolphin Sys runtime zip\n.*?(?=\n      - name: Stage PPSSPP runtime assets)', '\n', s, flags=re.S)
s = s.replace('      - name: Prepare thin app shell but keep Dolphin internal', '      - name: Prepare thin app shell')
s = s.replace('          test -s app/src/main/jniLibs/arm64-v8a/libdolphin_core.so\n          test -s app/src/main/assets/Dolphin/Sys.zip\n', '')
s = s.replace('      - name: Verify signed APK contains Dolphin core and runtime zip', '      - name: Verify signed APK')
for line in [
'          unzip -l "$APK" | grep -F "lib/arm64-v8a/libdolphin_core.so"\n',
'          unzip -l "$APK" | grep -F "assets/Dolphin/Sys.zip"\n',
'          unzip -p "$APK" assets/Dolphin/Sys.zip > /tmp/DolphinSys.zip\n',
'          test -s /tmp/DolphinSys.zip\n',
"          unzip -l /tmp/DolphinSys.zip | grep -F 'GC/font_western.bin'\n",
"          unzip -l /tmp/DolphinSys.zip | grep -F 'GC/dsp_rom.bin'\n",
"          unzip -l /tmp/DolphinSys.zip | grep -F 'GC/dsp_coef.bin'\n",
]: s = s.replace(line, '')
s = s.replace('          echo "Release $TAG contains internal Dolphin and is ready for in-app update."', '          echo "Release $TAG uses external GameCube/Wii routing and is ready for in-app update."')
p.write_text(s)
