from pathlib import Path
import re

p=Path('app/src/main/cpp/emuhost.cpp')
s=p.read_text()

# Add second EGL window surface and GPU present resources.
s=s.replace('static ANativeWindow* nativeWindow=nullptr; static bool directWindowSurface=false;\nstatic GLuint directFbo=0,directColor=0,directDepth=0; static int directFboW=1024,directFboH=1024;\nstatic EGLDisplay eglDisplay=EGL_NO_DISPLAY; static EGLContext eglContext=EGL_NO_CONTEXT; static EGLSurface eglSurface=EGL_NO_SURFACE;',
'''static ANativeWindow* nativeWindow=nullptr; static bool directWindowSurface=false;
static EGLSurface windowSurface=EGL_NO_SURFACE;
static GLuint presentTex=0,presentProgram=0,presentVao=0,presentVbo=0;
static EGLDisplay eglDisplay=EGL_NO_DISPLAY; static EGLContext eglContext=EGL_NO_CONTEXT; static EGLSurface eglSurface=EGL_NO_SURFACE;''')

# Frontend framebuffer must remain 0 so Dolphin renders exactly like old working pbuffer path.
s=re.sub(r'static uintptr_t hwFramebuffer\(\)\{return .*?;\}', 'static uintptr_t hwFramebuffer(){return 0;}', s, count=1)

# Replace destroyHw with cleanup including window surface/present objects but no direct FBO.
start=s.index('static void destroyHw(){')
end=s.index('\nstatic bool createHw(', start)
new_destroy='''static void destroyHw(){if(eglDisplay!=EGL_NO_DISPLAY){if(eglContext!=EGL_NO_CONTEXT&&makeHwCurrent()){if(readbackPbo[0]){glDeleteBuffers(2,readbackPbo);readbackPbo[0]=readbackPbo[1]=0;readbackPboBytes=0;}if(presentTex){glDeleteTextures(1,&presentTex);presentTex=0;}if(presentProgram){glDeleteProgram(presentProgram);presentProgram=0;}if(presentVbo){glDeleteBuffers(1,&presentVbo);presentVbo=0;}if(presentVao){glDeleteVertexArrays(1,&presentVao);presentVao=0;}if(hwContextDestroy&&hwResetCalled)hwContextDestroy();}releaseHwCurrent();if(eglContext!=EGL_NO_CONTEXT)eglDestroyContext(eglDisplay,eglContext);if(windowSurface!=EGL_NO_SURFACE)eglDestroySurface(eglDisplay,windowSurface);if(eglSurface!=EGL_NO_SURFACE)eglDestroySurface(eglDisplay,eglSurface);eglTerminate(eglDisplay);}eglDisplay=EGL_NO_DISPLAY;eglContext=EGL_NO_CONTEXT;eglSurface=EGL_NO_SURFACE;windowSurface=EGL_NO_SURFACE;hwEnabled=false;hwResetCalled=false;hwContextReset=nullptr;hwContextDestroy=nullptr;}'''
s=s[:start]+new_destroy+s[end:]

# Replace createHw: always create pbuffer as core render surface, optionally also create Android window surface.
start=s.index('static bool createHw(')
end=s.index('\nstatic const char*dolphinOption', start)
new_create=r'''static GLuint compileShader(GLenum type,const char*src){GLuint sh=glCreateShader(type);glShaderSource(sh,1,&src,nullptr);glCompileShader(sh);GLint ok=0;glGetShaderiv(sh,GL_COMPILE_STATUS,&ok);if(!ok){glDeleteShader(sh);return 0;}return sh;}
static bool createPresentPipeline(){
 const char*vs="#version 300 es\nlayout(location=0) in vec2 aPos;layout(location=1) in vec2 aUv;out vec2 vUv;void main(){gl_Position=vec4(aPos,0.0,1.0);vUv=aUv;}";
 const char*fs="#version 300 es\nprecision mediump float;in vec2 vUv;uniform sampler2D uTex;out vec4 frag;void main(){frag=texture(uTex,vUv);}";
 GLuint v=compileShader(GL_VERTEX_SHADER,vs),f=compileShader(GL_FRAGMENT_SHADER,fs);if(!v||!f)return false;presentProgram=glCreateProgram();glAttachShader(presentProgram,v);glAttachShader(presentProgram,f);glLinkProgram(presentProgram);glDeleteShader(v);glDeleteShader(f);GLint ok=0;glGetProgramiv(presentProgram,GL_LINK_STATUS,&ok);if(!ok)return false;
 const float verts[]={-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1};glGenVertexArrays(1,&presentVao);glBindVertexArray(presentVao);glGenBuffers(1,&presentVbo);glBindBuffer(GL_ARRAY_BUFFER,presentVbo);glBufferData(GL_ARRAY_BUFFER,sizeof(verts),verts,GL_STATIC_DRAW);glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),(void*)0);glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),(void*)(2*sizeof(float)));glBindVertexArray(0);return true;}
static bool createHw(retro_hw_render_callback*cb){if(!cb)return false;{char b[160];std::snprintf(b,sizeof(b),"hw: SET_HW_RENDER type=%d version=%u.%u depth=%d stencil=%d cache=%d",(int)cb->context_type,cb->version_major,cb->version_minor,cb->depth?1:0,cb->stencil?1:0,cb->cache_context?1:0);traceLine(b);}if(cb->context_type!=RETRO_HW_CONTEXT_OPENGLES2&&cb->context_type!=RETRO_HW_CONTEXT_OPENGLES3&&cb->context_type!=RETRO_HW_CONTEXT_OPENGLES_VERSION){traceLine("hw: unsupported context type");return false;}destroyHw();eglDisplay=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(eglDisplay==EGL_NO_DISPLAY||!eglInitialize(eglDisplay,nullptr,nullptr)){traceLine("hw: eglInitialize failed");destroyHw();return false;}EGLint renderable=EGL_OPENGL_ES2_BIT|0x0040;const EGLint ca[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT|EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,renderable,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,cb->depth?24:0,EGL_STENCIL_SIZE,cb->stencil?8:0,EGL_NONE};EGLConfig cfg=nullptr;EGLint count=0;if(!eglChooseConfig(eglDisplay,ca,&cfg,1,&count)||count<1){traceLine("hw: eglChooseConfig failed");destroyHw();return false;}const EGLint pa[]={EGL_WIDTH,1024,EGL_HEIGHT,1024,EGL_NONE};eglSurface=eglCreatePbufferSurface(eglDisplay,cfg,pa);if(eglSurface==EGL_NO_SURFACE){traceLine("hw: pbuffer failed");destroyHw();return false;}if(isDolphin&&nativeWindow){windowSurface=eglCreateWindowSurface(eglDisplay,cfg,nativeWindow,nullptr);if(windowSurface==EGL_NO_SURFACE){traceLine("hw: auxiliary window surface failed");destroyHw();return false;}traceLine("hw: pbuffer + window surfaces ready");}unsigned major=cb->version_major;if(cb->context_type==RETRO_HW_CONTEXT_OPENGLES3&&major<3)major=3;if(major<2)major=2;eglBindAPI(EGL_OPENGL_ES_API);const EGLint xa[]={EGL_CONTEXT_CLIENT_VERSION,(EGLint)major,EGL_NONE};eglContext=eglCreateContext(eglDisplay,cfg,EGL_NO_CONTEXT,xa);if(eglContext==EGL_NO_CONTEXT){traceLine("hw: context create failed");destroyHw();return false;}hwEnabled=true;hwBottomLeft=cb->bottom_left_origin;hwContextReset=cb->context_reset;hwContextDestroy=cb->context_destroy;cb->get_current_framebuffer=hwFramebuffer;cb->get_proc_address=hwProc;if(!makeHwCurrent()){traceLine("hw: make current failed");destroyHw();return false;}glViewport(0,0,1024,1024);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT);glFinish();releaseHwCurrent();traceLine("hw: EGL pbuffer context registered");return true;}'''
s=s[:start]+new_create+s[end:]

# Replace captureHw direct path. Copy pbuffer framebuffer into texture on GPU, then draw on Android window surface.
start=s.index('static void captureHw(unsigned w,unsigned h){')
end=s.index('\nstatic void videoCb(', start)
old=s[start:end]
# Preserve fallback PBO body after first direct branch by extracting from old.
fallback_idx=old.find('    if(!w||!h||w>1024||h>1024)return;')
if fallback_idx<0: raise SystemExit('fallback capture body not found')
fallback=old[fallback_idx:]
new_capture=r'''static void captureHw(unsigned w,unsigned h){
    if(firstCapture){traceLine("video: first HW frame callback");firstCapture=false;}
    if(isDolphin&&directWindowSurface&&windowSurface!=EGL_NO_SURFACE){
        if(!w||!h||w>1024||h>1024)return;frameW=w;frameH=h;
        if(!presentTex){glGenTextures(1,&presentTex);glBindTexture(GL_TEXTURE_2D,presentTex);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,(GLsizei)w,(GLsizei)h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);if(!createPresentPipeline()){traceLine("video: present pipeline create failed");return;}traceLine("video: hybrid GPU presentation ready");}
        static unsigned texW=0,texH=0;if(texW!=w||texH!=h){glBindTexture(GL_TEXTURE_2D,presentTex);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,(GLsizei)w,(GLsizei)h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);texW=w;texH=h;}
        glBindFramebuffer(GL_FRAMEBUFFER,0);glBindTexture(GL_TEXTURE_2D,presentTex);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,(GLsizei)w,(GLsizei)h);
        if(eglMakeCurrent(eglDisplay,windowSurface,windowSurface,eglContext)!=EGL_TRUE){traceLine("video: window make current failed");eglMakeCurrent(eglDisplay,eglSurface,eglSurface,eglContext);return;}
        EGLint sw=0,sh=0;eglQuerySurface(eglDisplay,windowSurface,EGL_WIDTH,&sw);eglQuerySurface(eglDisplay,windowSurface,EGL_HEIGHT,&sh);glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(0,0,sw,sh);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);glUseProgram(presentProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,presentTex);GLint loc=glGetUniformLocation(presentProgram,"uTex");if(loc>=0)glUniform1i(loc,0);glBindVertexArray(presentVao);glDrawArrays(GL_TRIANGLE_STRIP,0,4);glBindVertexArray(0);if(eglSwapBuffers(eglDisplay,windowSurface)!=EGL_TRUE)traceLine("video: hybrid swap failed");
        eglMakeCurrent(eglDisplay,eglSurface,eglSurface,eglContext);glBindFramebuffer(GL_FRAMEBUFFER,0);return;
    }
'''+fallback+'\n}'
s=s[:start]+new_capture+s[end:]

# Remove stale direct FBO symbols if any remained.
s=s.replace('directFbo','legacyDirectFboRemoved').replace('directColor','legacyDirectColorRemoved').replace('directDepth','legacyDirectDepthRemoved')
# But ensure there are no declarations/references accidentally left.
for token in ('legacyDirectFboRemoved','legacyDirectColorRemoved','legacyDirectDepthRemoved'):
    if token in s:
        raise SystemExit('stale direct FBO token remains: '+token)

p.write_text(s)
print('patched hybrid pbuffer->GPU texture->window surface')
