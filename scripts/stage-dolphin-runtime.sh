#!/usr/bin/env bash
set -euo pipefail

DOLPHIN_REF="${DOLPHIN_REF:-2606a}"
ROOT="${RUNNER_TEMP:-/tmp}/dolphin-upstream"
OUT="app/libs/dolphin-runtime.aar"

rm -rf "$ROOT"
mkdir -p app/libs

git clone --depth 1 --branch "$DOLPHIN_REF" --recurse-submodules --shallow-submodules https://github.com/dolphin-emu/dolphin.git "$ROOT"

python3 - "$ROOT" <<'PY'
from pathlib import Path
import re, sys
root=Path(sys.argv[1])
gradle=root/'Source/Android/app/build.gradle.kts'
s=gradle.read_text()
s=s.replace('alias(libs.plugins.android.application)', 'alias(libs.plugins.android.library)', 1)
s=s.replace('    alias(libs.plugins.androidx.baselineprofile)\n', '')
s=re.sub(r'\n\s*applicationId = "org\.dolphinemu\.dolphinemu"', '', s, count=1)
s=re.sub(r'\n\s*versionCode = getBuildVersionCode\(\)', '', s, count=1)
s=re.sub(r'\n\s*versionName = getGitVersion\(\)', '', s, count=1)
s=re.sub(r'\n\s*testInstrumentationRunner = "androidx\.test\.runner\.AndroidJUnitRunner"', '', s, count=1)
s=re.sub(r'\n\s*signingConfigs \{.*?\n\s*\}\n\n\s*// Define build types', '\n\n    // Define build types', s, flags=re.S, count=1)
s=re.sub(r'\n\s*// Define build types, which are orthogonal to product flavors\.\n\s*buildTypes \{.*?\n\s*\}\n\n\s*externalNativeBuild', '''\n    buildTypes {\n        release {\n            isMinifyEnabled = false\n        }\n        debug {\n            isJniDebuggable = true\n        }\n    }\n\n    externalNativeBuild''', s, flags=re.S, count=1)
s=s.replace('    baselineProfile(project(":benchmark"))\n', '')
gradle.write_text(s)

settings=root/'Source/Android/settings.gradle.kts'
t=settings.read_text().replace('include(":benchmark")\n','')
settings.write_text(t)

app=root/'Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/DolphinApplication.kt'
a=app.read_text().replace('class DolphinApplication : Application() {','open class DolphinApplication : Application() {',1)
app.write_text(a)

manifest=root/'Source/Android/app/src/main/AndroidManifest.xml'
m=manifest.read_text()
# The host app owns the Application object; EmuHubApp subclasses DolphinApplication.
m=m.replace('android:name=".DolphinApplication"','')
# Avoid extra launcher icons from the embedded library. Remove MAIN/LAUNCHER and LEANBACK launcher filters only.
m=re.sub(r'\s*<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*/>\s*<category android:name="android\.intent\.category\.(?:LAUNCHER|LEANBACK_LAUNCHER)"\s*/>\s*</intent-filter>', '', m, flags=re.S)
manifest.write_text(m)
PY

pushd "$ROOT/Source/Android" >/dev/null
./gradlew :app:bundleReleaseAar --no-daemon --stacktrace
popd >/dev/null

AAR=$(find "$ROOT/Source/Android/app/build/outputs/aar" -type f -name '*release*.aar' | head -n1)
test -n "$AAR"
cp "$AAR" "$OUT"
test -s "$OUT"

echo "Dolphin upstream ref: $DOLPHIN_REF"
unzip -l "$OUT" | grep -q 'jni/arm64-v8a/libmain.so'
unzip -l "$OUT" | grep -q 'assets/Sys/'
echo "Official Dolphin runtime staged: $OUT"
