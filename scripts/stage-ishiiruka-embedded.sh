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
git clone --depth 1 --branch "$ISH_TAG" --recurse-submodules "$ISH_REPO" "$SRC"

# setup-gradle validates every wrapper JAR below the workspace, including wrappers
# from vendored upstream examples/submodules that are never executed by Emu Hub.
# Remove those binary wrappers after cloning so validation only covers our build.
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

test -s "$MOD/src/main/jniLibs/arm64-v8a/libmain.so"

cat > "$MOD/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.library") version "8.7.3"
}

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

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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
        <activity
            android:name="org.dolphinemu.dolphinemu.activities.EmulationActivity"
            android:exported="false"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|screenSize|keyboardHidden|keyboard"
            android:theme="@style/Theme.SlippiDolphin.Emulation" />
        <activity
            android:name="org.dolphinemu.dolphinemu.activities.SettingsActivity"
            android:exported="false"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|screenSize|keyboardHidden|keyboard"
            android:theme="@style/Theme.SlippiDolphin" />
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
    if idx < 0:
        raise SystemExit('app/build.gradle.kts malformed')
    s = s[:idx] + '    implementation(project(":ishiiruka"))\n' + s[idx:]
    app_gradle.write_text(s)

app = Path('app/src/main/java/com/ric/emuhub/EmuHubApp.kt')
s = app.read_text()
if 'org.dolphinemu.dolphinemu.DolphinApplication' not in s:
    s = s.replace('import android.app.Application\n', 'import android.app.Application\nimport org.dolphinemu.dolphinemu.DolphinApplication\n')
    s = s.replace('class EmuHubApp : Application() {', 'class EmuHubApp : DolphinApplication() {')
    app.write_text(s)

main = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = main.read_text()
repls = [
    ('setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm")',
     'setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","gcm","rvz","wbfs","wia","wad","dol")'),
    ('arrayOf("PS2","ARMSX2","2"),arrayOf("GBA"',
     'arrayOf("PS2","ARMSX2","2"),arrayOf("GC/WII","Ishiiruka","◎"),arrayOf("GBA"'),
    ('"xci","nsp","nro"->"SWITCH"\n            "iso"->',
     '"xci","nsp","nro"->"SWITCH"\n            "gcm","rvz","wbfs","wia","wad","dol"->"GC/WII"\n            "iso"->'),
    ('"PSP"->0;"PS1"->1;"PS2"->2;"GBA"->3;',
     '"PSP"->0;"PS1"->1;"PS2"->2;"GC/WII"->3;"GBA"->4;'),
    ('"GBA"->"GAME BOY • MGBA"',
     '"GC/WII"->"GAMECUBE / WII • ISHIIRUKA"\n        "GBA"->"GAME BOY • MGBA"'),
    ('val filters=listOf("ALL","PSP","PS1","PS2","GBA"',
     'val filters=listOf("ALL","PSP","PS1","PS2","GC/WII","GBA"'),
    ('"PSP"->"P";"PS1"->"1";"PS2"->"2";"GBA"',
     '"PSP"->"P";"PS1"->"1";"PS2"->"2";"GC/WII"->"◎";"GBA"'),
    ('"PS2"->launchPs2OrSetup(uri,g.name)\n            "GBA"',
     '"PS2"->launchPs2OrSetup(uri,g.name)\n            "GC/WII"->launchIshiirukaDirect(uri,g.name)\n            "GBA"'),
    ('arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan")',
     'arrayOf("PlayStation 1 • PCSX-ReARMed","PSP • PPSSPP","PlayStation 2 • ARMSX2 Vulkan","GameCube / Wii • Ishiiruka Embedded")'),
    ('when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");else->launchPs2OrSetup(uri,name)}',
     'when(which){0->copyAndLaunchInternal(uri,name,"iso","pcsx");1->showPspResolutionChooser(uri,name,"iso");2->launchPs2OrSetup(uri,name);else->launchIshiirukaDirect(uri,name)}'),
    ('"iso"->"PS1 / PSP / PS2 • choose engine"',
     '"iso"->"PS1 / PSP / PS2 / GC / Wii • choose engine"'),
]
for old,new in repls:
    if old in s:
        s = s.replace(old,new)

if 'private fun launchIshiirukaDirect(' not in s:
    anchor = '    private fun launchEden(uri:Uri){'
    method = '''    private fun launchIshiirukaDirect(uri:Uri,name:String){\n        val file=directGameFile(uri)\n        if(file==null){\n            status.text="Ishiiruka direct path unavailable"\n            Toast.makeText(this,"GameCube/Wii harus berada di penyimpanan internal/SD yang bisa diakses langsung.",Toast.LENGTH_LONG).show()\n            return\n        }\n        try{\n            val clazz=Class.forName("org.dolphinemu.dolphinemu.activities.EmulationActivity")\n            status.text="GameCube / Wii • Ishiiruka Embedded"\n            startActivity(Intent(this,clazz)\n                .putExtra("iso_path",file.absolutePath)\n                .putExtra("launch_mode","local_play")\n                .putExtra("use_gc_adapter",false))\n        }catch(e:Throwable){\n            status.text="Ishiiruka embedded runtime gagal dimuat"\n            Toast.makeText(this,"Ishiiruka embedded gagal: ${e.message}",Toast.LENGTH_LONG).show()\n        }\n    }\n\n'''
    if anchor not in s:
        raise SystemExit('MainActivity anchor not found')
    s = s.replace(anchor, method + anchor)
main.write_text(s)
PY

echo "==> Embedded Ishiiruka staged"
grep -q 'project(":ishiiruka")' settings.gradle.kts
grep -q 'implementation(project(":ishiiruka"))' app/build.gradle.kts
grep -q 'DolphinApplication' app/src/main/java/com/ric/emuhub/EmuHubApp.kt
grep -q 'launchIshiirukaDirect' app/src/main/java/com/ric/emuhub/MainActivity.kt
find "$MOD/src/main/jniLibs/arm64-v8a" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | sort
