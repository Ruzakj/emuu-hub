from pathlib import Path

p = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = p.read_text()

old_packages = '        private val DOLPHIN_PACKAGES = listOf("org.dolphinemu.dolphinemu","org.dolphinemu.dolphinemu.dev","org.dolphinemu.mmjr","org.dolphinemu.mmjr2")'
new_packages = '        private val DOLPHIN_PACKAGES = listOf("org.dolphinemu.dolphinemu","org.dolphinemu.dolphinemu.dev","org.dolphinemu.mmjr","org.dolphinemu.mmjr2","org.dolphinemu.mmjr3","org.mm.jr","org.mm.j","org.dolphinemu.handheld","org.dolphin.ishiirukadark")'
if old_packages not in s:
    raise SystemExit('Dolphin package list target not found')
s = s.replace(old_packages, new_packages, 1)

start = s.index('    private fun launchDolphinExternal(uri:Uri,name:String){')
end = s.index('\n    private fun launchEden(uri:Uri){', start)
new_func = r'''    private fun launchDolphinExternal(uri:Uri,name:String){
        val pkg=DOLPHIN_PACKAGES.firstOrNull{packageManager.getLaunchIntentForPackage(it)!=null}?:run{
            Toast.makeText(this,"Emulator GameCube/Wii eksternal tidak terdeteksi. Install Dolphin atau fork yang kompatibel.",Toast.LENGTH_LONG).show();return
        }
        val directPath=directGameFile(uri)?.absolutePath
        val uriValue=uri.toString()
        val pathValue=directPath?:uriValue
        val activity="org.dolphinemu.dolphinemu.ui.main.MainActivity"

        // Dolphin official uses ACTION_MAIN + AutoStartFile URI. Several forks use ACTION_VIEW;
        // path-based forks require a filesystem path instead of content:// URI.
        val official = pkg=="org.dolphinemu.dolphinemu" || pkg=="org.dolphinemu.dolphinemu.dev"
        val pathFork = pkg in setOf("org.mm.jr","org.mm.j","org.dolphin.ishiirukadark")
        val primary=Intent(if(official) Intent.ACTION_MAIN else Intent.ACTION_VIEW).apply{
            setClassName(pkg,activity)
            putExtra("AutoStartFile",if(pathFork) pathValue else uriValue)
            putExtra("romPath",pathValue)
            putExtra("romName",name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            clipData=ClipData.newRawUri("GameCube/Wii ROM",uri)
        }
        if(official) primary.addCategory(Intent.CATEGORY_LAUNCHER)

        try{
            startActivity(primary)
            status.text="GameCube/Wii • direct boot • ${pkg.substringAfterLast('.')}"
            return
        }catch(_:Exception){}

        // Compatibility fallback: same explicit MainActivity, alternate action/value form.
        val fallback=Intent(if(official) Intent.ACTION_VIEW else Intent.ACTION_MAIN).apply{
            setClassName(pkg,activity)
            putExtra("AutoStartFile",pathValue)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            clipData=ClipData.newRawUri("GameCube/Wii ROM",uri)
        }
        try{
            startActivity(fallback)
            status.text="GameCube/Wii • compatibility direct boot"
            return
        }catch(_:Exception){}

        Toast.makeText(this,"Dolphin terdeteksi, tapi direct boot ROM ditolak oleh versi ini.",Toast.LENGTH_LONG).show()
        packageManager.getLaunchIntentForPackage(pkg)?.let{runCatching{startActivity(it)}}
    }
'''
s = s[:start] + new_func + s[end:]
p.write_text(s)

m = Path('app/src/main/AndroidManifest.xml')
ms = m.read_text()
needle = '        <package android:name="dev.eden.eden_emulator" />'
insert = '''        <package android:name="org.dolphinemu.dolphinemu" />
        <package android:name="org.dolphinemu.dolphinemu.dev" />
        <package android:name="org.dolphinemu.mmjr" />
        <package android:name="org.dolphinemu.mmjr2" />
        <package android:name="org.dolphinemu.mmjr3" />
        <package android:name="org.mm.jr" />
        <package android:name="org.mm.j" />
        <package android:name="org.dolphinemu.handheld" />
        <package android:name="org.dolphin.ishiirukadark" />
'''
if 'org.dolphinemu.dolphinemu' not in ms:
    if needle not in ms:
        raise SystemExit('manifest queries insertion point not found')
    ms = ms.replace(needle, insert + needle, 1)
    m.write_text(ms)
