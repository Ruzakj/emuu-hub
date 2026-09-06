from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/com/ric/emuhub/MainActivity.kt")
s = main_path.read_text()

s = replace_once(
    s,
    'private val INTERNAL = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","gcm","rvz","wbfs","wia","wad","dol")',
    'private val INTERNAL = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","gcm","rvz","wbfs","wia","wad","dol","elf")\n        private val DOLPHIN = setOf("gcm","rvz","wbfs","wia","wad","dol","elf","ciso")',
    "Dolphin extension set",
)

s = replace_once(
    s,
    '"GAMECUBE","WII"->copyAndLaunchInternal(uri,g.name,g.ext,"dolphin")',
    '"GAMECUBE","WII"->launchDolphinNative(uri,g.name)',
    "library GC/Wii route",
)

s = replace_once(
    s,
    'private fun openLibraryGame(uri:Uri,name:String,ext:String){when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext in setOf("gcm","rvz","wbfs","wia","wad","dol")->copyAndLaunchInternal(uri,name,ext,"dolphin");ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);else->copyAndLaunchInternal(uri,name,ext,null)}}',
    'private fun openLibraryGame(uri:Uri,name:String,ext:String){when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext in DOLPHIN->launchDolphinNative(uri,name);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);else->copyAndLaunchInternal(uri,name,ext,null)}}',
    "direct GC/Wii route",
)

s = replace_once(
    s,
    'private fun showIsoChooser(uri:Uri,name:String){AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan","GameCube / Wii • Dolphin Core")){_,which->when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");2->launchPs2OrSetup(uri,name);else->copyAndLaunchInternal(uri,name,"iso","dolphin")}}.setNegativeButton("Batal",null).show()}',
    'private fun showIsoChooser(uri:Uri,name:String){AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan","GameCube / Wii • Dolphin Native 2606a")){_,which->when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");2->launchPs2OrSetup(uri,name);else->launchDolphinNative(uri,name)}}.setNegativeButton("Batal",null).show()}',
    "ISO chooser",
)

anchor = '''    private fun launchPs2OrSetup(uri:Uri,name:String){\n'''
insert = '''    private fun launchDolphinNative(uri:Uri,name:String){\n        val file=directGameFile(uri)\n        if(file==null){\n            status.text="Dolphin • direct ROM path unavailable"\n            Toast.makeText(this,"GameCube/Wii ROM harus berada di penyimpanan internal/SD yang bisa diakses langsung.",Toast.LENGTH_LONG).show()\n            return\n        }\n        status.text="GameCube/Wii • Dolphin Native 2606a"\n        android.util.Log.i("EMU_ROUTER","GC/Wii -> Dolphin Native 2606a • ${file.absolutePath}")\n        DolphinNativeLauncher.launch(this,file).onFailure{e->\n            status.text="Dolphin gagal dijalankan"\n            Toast.makeText(this,"Dolphin Native gagal: ${e.message}",Toast.LENGTH_LONG).show()\n        }\n    }\n\n    private fun launchExtractedDolphin(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){\n        pendingArchiveSession=session.root\n        status.text="GameCube/Wii • Dolphin Native 2606a"\n        android.util.Log.i("EMU_ROUTER","Extracted GC/Wii -> Dolphin Native 2606a • ${rom.file.absolutePath}")\n        DolphinNativeLauncher.launch(this,rom.file,REQUEST_ARCHIVE_GAME).onFailure{e->\n            pendingArchiveSession?.deleteRecursively();pendingArchiveSession=null\n            status.text="Dolphin gagal dijalankan"\n            Toast.makeText(this,"Dolphin Native gagal: ${e.message}",Toast.LENGTH_LONG).show()\n        }\n    }\n\n'''
if insert not in s:
    if anchor not in s:
        raise SystemExit("Dolphin launcher insertion anchor missing")
    s = s.replace(anchor, insert + anchor, 1)

s = replace_once(
    s,
    '            "cso"->showExtractedPspResolutionChooser(session,rom)\n            "ecm"->decodeExtractedEcm(session,rom)\n            else->launchTempInternalFile(rom.file,coreIdFor(rom.ext),rom.displayName,session.root)',
    '            "cso"->showExtractedPspResolutionChooser(session,rom)\n            "ecm"->decodeExtractedEcm(session,rom)\n            in DOLPHIN->launchExtractedDolphin(session,rom)\n            else->launchTempInternalFile(rom.file,coreIdFor(rom.ext),rom.displayName,session.root)',
    "archive Dolphin direct formats",
)

s = replace_once(
    s,
    'AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan")){_,which->\n            when(which){\n                0->launchTempInternalFile(rom.file,"pcsx",rom.displayName,session.root)\n                1->showExtractedPspResolutionChooser(session,rom)\n                else->launchExtractedPs2OrSetup(session,rom)\n            }',
    'AlertDialog.Builder(this).setTitle("Open ISO with").setItems(arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan","GameCube / Wii • Dolphin Native 2606a")){_,which->\n            when(which){\n                0->launchTempInternalFile(rom.file,"pcsx",rom.displayName,session.root)\n                1->showExtractedPspResolutionChooser(session,rom)\n                2->launchExtractedPs2OrSetup(session,rom)\n                else->launchExtractedDolphin(session,rom)\n            }',
    "archive ISO chooser",
)

s = replace_once(
    s,
    'if(requestCode==REQUEST_ROM){val uri=data?.data?:return;val name=displayName(uri)?:"ROM";val ext=extension(name);when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);ext in INTERNAL->copyAndLaunchInternal(uri,name,ext,null);else->Toast.makeText(this,"Format belum didukung: .$ext",Toast.LENGTH_LONG).show()}}',
    'if(requestCode==REQUEST_ROM){val uri=data?.data?:return;val name=displayName(uri)?:"ROM";val ext=extension(name);when{ext in ARCHIVES->openArchive(uri,name);ext in SWITCH->launchEden(uri);ext in DOLPHIN->launchDolphinNative(uri,name);ext=="ecm"->decodeAndLaunchEcm(uri,name);ext=="iso"->showIsoChooser(uri,name);ext=="chd"->showChdChooser(uri,name);ext=="cso"->showPspResolutionChooser(uri,name,ext);ext in INTERNAL->copyAndLaunchInternal(uri,name,ext,null);else->Toast.makeText(this,"Format belum didukung: .$ext",Toast.LENGTH_LONG).show()}}',
    "file picker Dolphin route",
)

main_path.write_text(s)

archive_path = Path("app/src/main/java/com/ric/emuhub/ArchiveHelper.kt")
a = archive_path.read_text()
a = replace_once(
    a,
    'private val ROM_EXTENSIONS = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","xci","nsp","nro")',
    'private val ROM_EXTENSIONS = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","gcm","rvz","wbfs","wia","wad","dol","elf","ciso","xci","nsp","nro")',
    "archive GC/Wii extensions",
)
archive_path.write_text(a)

print("Native Dolphin routing patch applied")
