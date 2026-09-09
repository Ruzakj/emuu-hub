#!/usr/bin/env bash
set -euo pipefail

ISH_TAG="v3.6.4-android-r1"
ISH_REPO="https://github.com/MaxLaurence/slippi-android.git"
ISH_APK="https://github.com/MaxLaurence/slippi-android/releases/download/${ISH_TAG}/slippi-android-${ISH_TAG}.apk"
ISH_APK_SHA256="72d3e32f73a5eac38434b4c8c21a48dc46c8b1e69d18f606f466fbdbfc3b2907"
WORK="${PWD}/.embedded-ishiiruka"
SRC="$WORK/source"
APK="$WORK/ishiiruka.apk"
MOD="${PWD}/embedded/ishiiruka"

rm -rf "$WORK" "$MOD"
mkdir -p "$WORK" "$MOD/src/main/java" "$MOD/src/main/res" "$MOD/src/main/assets" "$MOD/src/main/jniLibs/arm64-v8a"

echo "==> Fetching Ishiiruka Android ${ISH_TAG}"
# We only compile the Android Java/resources from upstream. Native/assets come from the
# verified release APK below, so recursively cloning Dolphin's huge submodule graph is
# unnecessary and makes every Emu Hub build take several extra minutes.
git clone --depth 1 --branch "$ISH_TAG" "$ISH_REPO" "$SRC"
find "$SRC" -type f -path '*/gradle/wrapper/gradle-wrapper.jar' -print -delete

echo "==> Fetching verified ARM64 runtime"
curl --retry 3 --retry-delay 2 -fL "$ISH_APK" -o "$APK"
echo "${ISH_APK_SHA256}  ${APK}" | sha256sum -c -

cp -a "$SRC/Source/Android/app/src/main/java/." "$MOD/src/main/java/"
cp -a "$SRC/Source/Android/app/src/main/res/." "$MOD/src/main/res/"

mkdir -p "$WORK/apk"
unzip -q "$APK" -d "$WORK/apk"
if [ -d "$WORK/apk/assets" ]; then cp -a "$WORK/apk/assets/." "$MOD/src/main/assets/"; fi
cp -a "$WORK/apk/lib/arm64-v8a/." "$MOD/src/main/jniLibs/arm64-v8a/"
rm -f "$MOD/src/main/jniLibs/arm64-v8a/libc++_shared.so"

test -s "$MOD/src/main/jniLibs/arm64-v8a/libmain.so"

cat > "$MOD/build.gradle.kts" <<'EOF'
plugins { id("com.android.library") }
android {
    namespace = "org.dolphinemu.dolphinemu"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "FORCE_TOUCH_CONTROLS", "false")
        buildConfigField("String", "ANDROID_ABI", "\"arm64-v8a\"")
        buildConfigField("String", "VERSION_NAME", "\"3.6.4-android-r1\"")
        buildConfigField("int", "VERSION_CODE", "3060401")
    }
    buildFeatures { buildConfig = true; viewBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.oboe:oboe:1.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
EOF

cat > "$MOD/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.hardware.gamepad" android:required="false" />
    <uses-feature android:glEsVersion="0x00030000" android:required="false" />
    <uses-feature android:name="android.hardware.usb.host" android:required="false" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WRITE_SETTINGS" />
    <application>
        <activity android:name="org.dolphinemu.dolphinemu.activities.EmulationActivity" android:exported="false" android:screenOrientation="sensorLandscape" android:configChanges="orientation|screenSize|keyboardHidden|keyboard" android:theme="@style/Theme.SlippiDolphin.Emulation" />
        <activity android:name="org.dolphinemu.dolphinemu.activities.SettingsActivity" android:exported="false" android:screenOrientation="sensorLandscape" android:configChanges="orientation|screenSize|keyboardHidden|keyboard" android:theme="@style/Theme.SlippiDolphin" />
    </application>
</manifest>
EOF

python3 <<'PY'
from pathlib import Path

settings = Path('settings.gradle.kts')
s = settings.read_text()
block = '''\nif (file("embedded/ishiiruka").exists()) {\n    include(":ishiiruka")\n    project(":ishiiruka").projectDir = file("embedded/ishiiruka")\n}\n'''
if 'project(":ishiiruka")' not in s:
    settings.write_text(s.rstrip() + '\n' + block)

app_gradle = Path('app/build.gradle.kts')
s = app_gradle.read_text()
needle = 'implementation(project(":ishiiruka"))'
if needle not in s:
    idx = s.rfind('}')
    if idx < 0: raise SystemExit('app/build.gradle.kts malformed')
    s = s[:idx] + '    implementation(project(":ishiiruka"))\n' + s[idx:]
    app_gradle.write_text(s)

app = Path('app/src/main/java/com/ric/emuhub/EmuHubApp.kt')
s = app.read_text()
if 'org.dolphinemu.dolphinemu.DolphinApplication' not in s:
    s = s.replace('import android.app.Application\n', 'import android.app.Application\nimport org.dolphinemu.dolphinemu.DolphinApplication\n')
    s = s.replace('class EmuHubApp : Application() {', 'class EmuHubApp : DolphinApplication() {')
    app.write_text(s)

# The official 3.6.4-r1 APK's native core may not expose the optional raw-evdev JNI
# methods declared by the matching Java tree. That probe runs during Activity.onCreate
# and previously killed Emu Hub with UnsatisfiedLinkError. Raw evdev is optional on
# stock Android, so treat missing JNI exactly like an unavailable provider and fall
# back to Android MotionEvent/controller input.
evdev = Path('embedded/ishiiruka/src/main/java/org/dolphinemu/dolphinemu/utils/NativeEvdevStickInputProvider.java')
if evdev.exists():
    es = evdev.read_text()
    old_snapshot = '''    @Override\n    public RawStickState snapshot() {\n        return RawStickState.fromAxes(NativeLibrary.PollRawGamepadAxes());\n    }'''
    new_snapshot = '''    @Override\n    public RawStickState snapshot() {\n        try {\n            return RawStickState.fromAxes(NativeLibrary.PollRawGamepadAxes());\n        } catch (UnsatisfiedLinkError error) {\n            android.util.Log.w("SlippiEmu", "Raw evdev JNI unavailable; using Android input fallback", error);\n            return null;\n        }\n    }'''
    old_wait = '''    @Override\n    public RawStickState waitForSnapshot(int timeoutMs) {\n        return RawStickState.fromAxes(NativeLibrary.WaitRawGamepadAxes(timeoutMs));\n    }'''
    new_wait = '''    @Override\n    public RawStickState waitForSnapshot(int timeoutMs) {\n        try {\n            return RawStickState.fromAxes(NativeLibrary.WaitRawGamepadAxes(timeoutMs));\n        } catch (UnsatisfiedLinkError error) {\n            android.util.Log.w("SlippiEmu", "Raw evdev wait JNI unavailable; using Android input fallback", error);\n            return null;\n        }\n    }'''
    if old_snapshot in es:
        es = es.replace(old_snapshot, new_snapshot)
    if old_wait in es:
        es = es.replace(old_wait, new_wait)
    evdev.write_text(es)

# Mark that the embedded activity was actually entered. If native code kills the process
# after this point, EmuHubApp will recover this stage on the next startup.
emu = Path('embedded/ishiiruka/src/main/java/org/dolphinemu/dolphinemu/activities/EmulationActivity.java')
if emu.exists():
    es = emu.read_text()
    marker = 'getSharedPreferences("ishiiruka_runtime_trace", MODE_PRIVATE).edit().putString("stage", "activity_onCreate").putBoolean("active", true).commit();'
    if marker not in es and 'super.onCreate(savedInstanceState);' in es:
        es = es.replace('super.onCreate(savedInstanceState);', 'super.onCreate(savedInstanceState);\n        ' + marker, 1)
        emu.write_text(es)

main = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = main.read_text()
repls = [
    ('setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm")', 'setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","gcm","rvz","wbfs","wia","wad","dol")'),
    ('arrayOf("PS2","ARMSX2","2"),arrayOf("GBA"', 'arrayOf("PS2","ARMSX2","2"),arrayOf("GC/WII","Ishiiruka","◎"),arrayOf("GBA"'),
    ('"xci","nsp","nro"->"SWITCH"\n            "iso"->', '"xci","nsp","nro"->"SWITCH"\n            "gcm","rvz","wbfs","wia","wad","dol"->"GC/WII"\n            "iso"->'),
    ('"PSP"->0;"PS1"->1;"PS2"->2;"GBA"->3;', '"PSP"->0;"PS1"->1;"PS2"->2;"GC/WII"->3;"GBA"->4;'),
    ('"GBA"->"GAME BOY • MGBA"', '"GC/WII"->"GAMECUBE / WII • ISHIIRUKA"\n        "GBA"->"GAME BOY • MGBA"'),
    ('val filters=listOf("ALL","PSP","PS1","PS2","GBA"', 'val filters=listOf("ALL","PSP","PS1","PS2","GC/WII","GBA"'),
    ('"PSP"->"P";"PS1"->"1";"PS2"->"2";"GBA"', '"PSP"->"P";"PS1"->"1";"PS2"->"2";"GC/WII"->"◎";"GBA"'),
    ('"PS2"->launchPs2OrSetup(uri,g.name)\n            "GBA"', '"PS2"->launchPs2OrSetup(uri,g.name)\n            "GC/WII"->launchIshiirukaDirect(uri,g.name)\n            "GBA"'),
    ('arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan")', 'arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan","GameCube / Wii • Ishiiruka Embedded")'),
    ('when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");else->launchPs2OrSetup(uri,name)}', 'when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");2->launchPs2OrSetup(uri,name);else->launchIshiirukaDirect(uri,name)}'),
    ('"iso"->"PS1 / PSP / PS2 • choose engine"', '"iso"->"PS1 / PSP / PS2 / GC / Wii • choose engine"'),
]
for old,new in repls:
    if old in s: s = s.replace(old,new)

nav_old = 'nav.addView(consoleNav("⇩","UPDATE","System") { startActivity(Intent(this@MainActivity,UpdateActivity::class.java)) },LinearLayout.LayoutParams(0,dp(64),1f))'
nav_new = nav_old + '\n        nav.addView(consoleNav("≡","LOG","Crash") { showIshiirukaLog() },LinearLayout.LayoutParams(0,dp(64),1f))'
if 'showIshiirukaLog()' not in s and nav_old in s:
    s = s.replace(nav_old, nav_new)

if 'private fun showIshiirukaLog(' not in s:
    anchor = '    private fun launchEden(uri:Uri){'
    method = '''    private fun showIshiirukaLog(){\n        val file=File(StoragePaths.root(this),"ISHIIRUKA/crash.txt")\n        val text=if(file.exists()) file.readText().takeLast(24000) else "Belum ada crash log Ishiiruka. Jalankan game GC/Wii lalu buka LOG lagi jika terjadi FC."\n        AlertDialog.Builder(this)\n            .setTitle("Ishiiruka Crash Log")\n            .setMessage(text)\n            .setPositiveButton("COPY"){_,_->\n                val cm=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager\n                cm.setPrimaryClip(android.content.ClipData.newPlainText("Ishiiruka crash log",text))\n                Toast.makeText(this,"Log disalin",Toast.LENGTH_SHORT).show()\n            }\n            .setNeutralButton("CLEAR"){_,_->runCatching{file.delete()};Toast.makeText(this,"Log dihapus",Toast.LENGTH_SHORT).show()}\n            .setNegativeButton("CLOSE",null).show()\n    }\n\n'''
    if anchor not in s: raise SystemExit('MainActivity log anchor not found')
    s = s.replace(anchor, method + anchor)

if 'private fun launchIshiirukaDirect(' not in s:
    anchor = '    private fun launchEden(uri:Uri){'
    method = '''    private fun launchIshiirukaDirect(uri:Uri,name:String){\n        val file=directGameFile(uri)\n        val logFile=File(StoragePaths.root(this),"ISHIIRUKA/crash.txt").apply{parentFile?.mkdirs()}\n        if(file==null){\n            logFile.appendText("time=${System.currentTimeMillis()} stage=path_resolution_failed game=$name uri=$uri\\n")\n            status.text="Ishiiruka direct path unavailable"\n            Toast.makeText(this,"GameCube/Wii harus berada di penyimpanan internal/SD yang bisa diakses langsung.",Toast.LENGTH_LONG).show()\n            return\n        }\n        val trace=getSharedPreferences("ishiiruka_runtime_trace",MODE_PRIVATE)\n        trace.edit().putBoolean("active",true).putString("stage","launcher_startActivity").putString("game",name).putString("path",file.absolutePath).putLong("started_at",System.currentTimeMillis()).commit()\n        logFile.appendText("time=${System.currentTimeMillis()} stage=launcher_startActivity game=$name path=${file.absolutePath}\\n")\n        try{\n            trace.edit().putString("stage","native_load").commit()\n            System.loadLibrary("main")\n            trace.edit().putString("stage","native_load_ok").commit()\n            val clazz=Class.forName("org.dolphinemu.dolphinemu.activities.EmulationActivity")\n            trace.edit().putString("stage","activity_intent_dispatched").commit()\n            status.text="GameCube / Wii • Ishiiruka Embedded"\n            startActivity(Intent(this,clazz).putExtra("iso_path",file.absolutePath).putExtra("launch_mode","local_play").putExtra("use_gc_adapter",false))\n        }catch(e:Throwable){\n            trace.edit().putBoolean("active",false).putString("last_crash_stage","launcher_exception").commit()\n            logFile.appendText("time=${System.currentTimeMillis()} stage=launcher_exception exception=${e.javaClass.name}: ${e.message}\\n${android.util.Log.getStackTraceString(e)}\\n")\n            status.text="Ishiiruka embedded runtime gagal dimuat"\n            Toast.makeText(this,"Ishiiruka embedded gagal: ${e.message}",Toast.LENGTH_LONG).show()\n        }\n    }\n\n'''
    if anchor not in s: raise SystemExit('MainActivity launch anchor not found')
    s = s.replace(anchor, method + anchor)
main.write_text(s)
PY

echo "==> Embedded Ishiiruka staged"
grep -q 'project(":ishiiruka")' settings.gradle.kts
grep -q 'implementation(project(":ishiiruka"))' app/build.gradle.kts
grep -q 'DolphinApplication' app/src/main/java/com/ric/emuhub/EmuHubApp.kt
grep -q 'launchIshiirukaDirect' app/src/main/java/com/ric/emuhub/MainActivity.kt
grep -q 'showIshiirukaLog' app/src/main/java/com/ric/emuhub/MainActivity.kt
grep -q 'Raw evdev JNI unavailable' "$MOD/src/main/java/org/dolphinemu/dolphinemu/utils/NativeEvdevStickInputProvider.java"
find "$MOD/src/main/jniLibs/arm64-v8a" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | sort
