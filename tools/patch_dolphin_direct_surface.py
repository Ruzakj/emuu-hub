from pathlib import Path
import re

cpp = Path('app/src/main/cpp/emuhost.cpp')
kt = Path('app/src/main/java/com/ric/emuhub/GameActivity.kt')
nb = Path('app/src/main/java/com/ric/emuhub/core/NativeBridge.kt')

s = cpp.read_text()
k = kt.read_text()
n = nb.read_text()

# ---- NativeBridge.kt ----
if 'external fun setSurface(surface: android.view.Surface?)' not in n:
    n = n.replace('    external fun setLogPath(path: String)\n',
                  '    external fun setLogPath(path: String)\n    external fun setSurface(surface: android.view.Surface?)\n', 1)
nb.write_text(n)

# ---- emuhost.cpp ----
if '#include <android/native_window_jni.h>' not in s:
    s = s.replace('#include <android/log.h>\n', '#include <android/log.h>\n#include <android/native_window_jni.h>\n', 1)

if 'static ANativeWindow* nativeWindow=nullptr;' not in s:
    s = s.replace('static GLuint readbackPbo[2]={0,0}; static int readbackPboIndex=0; static size_t readbackPboBytes=0;\n',
                  'static GLuint readbackPbo[2]={0,0}; static int readbackPboIndex=0; static size_t readbackPboBytes=0;\nstatic ANativeWindow* nativeWindow=nullptr; static bool directWindowSurface=false;\n', 1)

# Add JNI surface setter before init JNI.
if 'NativeBridge_setSurface' not in s:
    marker = 'extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_setLogPath'
    idx = s.find(marker)
    if idx < 0:
        raise SystemExit('setLogPath JNI marker not found')
    block = '''extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_setSurface(JNIEnv*e,jobject,jobject surface){\n    if(nativeWindow){ANativeWindow_release(nativeWindow);nativeWindow=nullptr;}\n    if(surface)nativeWindow=ANativeWindow_fromSurface(e,surface);\n    directWindowSurface=nativeWindow!=nullptr;\n    traceLine(directWindowSurface?"surface: native window attached":"surface: native window detached");\n}\n'''
    s = s[:idx] + block + s[idx:]

# Make EGL config support both pbuffer and window surfaces.
s = s.replace('EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,', 'EGL_SURFACE_TYPE,EGL_PBUFFER_BIT|EGL_WINDOW_BIT,', 1)

# Replace fixed pbuffer creation with Dolphin window-surface preference.
old = 'const EGLint pa[]={EGL_WIDTH,1024,EGL_HEIGHT,1024,EGL_NONE};eglSurface=eglCreatePbufferSurface(eglDisplay,cfg,pa);if(eglSurface==EGL_NO_SURFACE){traceLine("hw: pbuffer failed");destroyHw();return false;}'
new = 'if(isDolphin&&nativeWindow){eglSurface=eglCreateWindowSurface(eglDisplay,cfg,nativeWindow,nullptr);if(eglSurface==EGL_NO_SURFACE){traceLine("hw: window surface failed");destroyHw();return false;}traceLine("hw: Dolphin window surface created");}else{const EGLint pa[]={EGL_WIDTH,1024,EGL_HEIGHT,1024,EGL_NONE};eglSurface=eglCreatePbufferSurface(eglDisplay,cfg,pa);if(eglSurface==EGL_NO_SURFACE){traceLine("hw: pbuffer failed");destroyHw();return false;}}'
if old in s:
    s = s.replace(old, new, 1)
elif 'Dolphin window surface created' not in s:
    raise SystemExit('EGL pbuffer creation anchor not found')

# Direct-present branch at start of captureHw after first frame trace.
cap_anchor = 'if(firstCapture){traceLine("video: first HW frame callback");firstCapture=false;}\n'
cap_insert = 'if(firstCapture){traceLine("video: first HW frame callback");firstCapture=false;}\n    if(isDolphin&&directWindowSurface){frameW=w;frameH=h;eglSwapBuffers(eglDisplay,eglSurface);return;}\n'
if cap_anchor in s and 'eglSwapBuffers(eglDisplay,eglSurface);return;' not in s:
    s = s.replace(cap_anchor, cap_insert, 1)

# Skip CPU pixel copy completely for direct Dolphin.
run_old = 'if(!frameDirty)return 0;frameDirty=false;jsize n=e->GetArrayLength(p);size_t c=std::min(frame.size(),(size_t)n);if(c)e->SetIntArrayRegion(p,0,c,(const jint*)frame.data());return(jint)c;'
run_new = 'if(isDolphin&&directWindowSurface)return 0;if(!frameDirty)return 0;frameDirty=false;jsize n=e->GetArrayLength(p);size_t c=std::min(frame.size(),(size_t)n);if(c)e->SetIntArrayRegion(p,0,c,(const jint*)frame.data());return(jint)c;'
if run_old in s:
    s = s.replace(run_old, run_new, 1)
elif 'if(isDolphin&&directWindowSurface)return 0;' not in s:
    raise SystemExit('runFrame copy anchor not found')

# Ensure native window ref gets released on unload only after EGL/core are gone.
unload_old = 'p_retro_set_controller_port_device=nullptr;}'
unload_new = 'p_retro_set_controller_port_device=nullptr;if(nativeWindow){ANativeWindow_release(nativeWindow);nativeWindow=nullptr;}directWindowSurface=false;}'
if unload_old in s and 'directWindowSurface=false;}' not in s:
    s = s.replace(unload_old, unload_new, 1)

cpp.write_text(s)

# ---- GameActivity.kt ----
if 'import android.view.SurfaceView' not in k:
    k = k.replace('import android.view.MotionEvent\n', 'import android.view.MotionEvent\nimport android.view.SurfaceHolder\nimport android.view.SurfaceView\n', 1)

# Branch Dolphin before native init.
branch_anchor = '        traceCoreStage("native_init", coreId, romName)\n'
branch_code = '''        if (coreId == "dolphin") {\n            launchDolphinDirectSurface(rom, romName, corePath, coreLabel, systemRoot, saveDir)\n            return\n        }\n        traceCoreStage("native_init", coreId, romName)\n'''
if branch_anchor in k and 'launchDolphinDirectSurface(' not in k:
    k = k.replace(branch_anchor, branch_code, 1)

# Add Dolphin direct surface launcher before showPreparingScreen.
insert_marker = '    private fun showPreparingScreen(message:String){\n'
if 'private fun launchDolphinDirectSurface(' not in k:
    direct_fun = '''    private fun launchDolphinDirectSurface(rom:String,romName:String,corePath:String,coreLabel:String,systemRoot:File,saveDir:File){\n        val root=FrameLayout(this).apply{setBackgroundColor(0xFF000000.toInt())}\n        val surfaceView=SurfaceView(this)\n        root.addView(surfaceView,FrameLayout.LayoutParams(-1,-1))\n        root.addView(buildGamepadOverlay("dolphin"),FrameLayout.LayoutParams(-1,-1))\n        setContentView(root)\n        enableSafeFullscreen()\n        var started=false\n        surfaceView.holder.addCallback(object:SurfaceHolder.Callback{\n            override fun surfaceCreated(holder:SurfaceHolder){\n                if(started||cleanedUp)return\n                started=true\n                NativeBridge.setSurface(holder.surface)\n                traceCoreStage("native_init", "dolphin", romName)\n                if(!NativeBridge.init(corePath,systemRoot.absolutePath,saveDir.absolutePath)){traceCoreStage("native_init_failed","dolphin",romName,false);showLoadError("$coreLabel core gagal inisialisasi. Log: emu-hub/CORE/core-runtime.log");return}\n                traceCoreStage("native_init_ok","dolphin",romName)\n                traceCoreStage("load_game","dolphin",romName)\n                if(!NativeBridge.loadGame(rom)){traceCoreStage("load_game_failed","dolphin",romName,false);showLoadError("$coreLabel gagal memuat ${File(rom).name}. Log: emu-hub/CORE/core-runtime.log");return}\n                traceCoreStage("load_game_ok","dolphin",romName)\n                gameView=GameView("dolphin",gameProfile)\n                gameView?.start()\n            }\n            override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){}\n            override fun surfaceDestroyed(holder:SurfaceHolder){}\n        })\n    }\n\n'''
    if insert_marker not in k:
        raise SystemExit('showPreparingScreen marker not found')
    k = k.replace(insert_marker, direct_fun + insert_marker, 1)

# Remove duplicate runDolphin implementations, keep first.
pattern = re.compile(r'\n        private fun runDolphin\(\)\{var nextFrame=System\.nanoTime\(\);while\(running\.get\(\)\)\{.*?nextFrame=now\}\}', re.S)
matches = list(pattern.finditer(k))
if len(matches) > 1:
    first = matches[0].group(0)
    start = matches[0].start()
    end = matches[-1].end()
    k = k[:start] + first + k[end:]

kt.write_text(k)
print('direct surface patch applied')
