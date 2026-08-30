#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>
#include <algorithm>
#include <iterator>
#include <cstdarg>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmuHost", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmuHost", __VA_ARGS__)

struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };
struct retro_variable { const char* key; const char* value; };
struct retro_log_callback { void (*log)(int, const char*, ...); };
struct retro_message { const char* msg; unsigned frames; };

enum { RETRO_PIXEL_FORMAT_0RGB1555=0, RETRO_PIXEL_FORMAT_XRGB8888=1, RETRO_PIXEL_FORMAT_RGB565=2 };
enum { ENV_SET_ROTATION=1, ENV_GET_OVERSCAN=2, ENV_GET_CAN_DUPE=3, ENV_SET_MESSAGE=6, ENV_SHUTDOWN=7, ENV_SET_PERFORMANCE_LEVEL=8, ENV_GET_SYSTEM_DIRECTORY=9, ENV_SET_PIXEL_FORMAT=10, ENV_SET_INPUT_DESCRIPTORS=11, ENV_GET_VARIABLE=15, ENV_SET_VARIABLES=16, ENV_GET_VARIABLE_UPDATE=17, ENV_SET_SUPPORT_NO_GAME=18, ENV_GET_LOG_INTERFACE=27, ENV_GET_SAVE_DIRECTORY=31, ENV_SET_SYSTEM_AV_INFO=32, ENV_SET_CONTROLLER_INFO=35, ENV_SET_MEMORY_MAPS=36, ENV_GET_LANGUAGE=39, ENV_GET_INPUT_BITMASKS=51 };
enum { RETRO_DEVICE_JOYPAD=1, RETRO_DEVICE_ANALOG=5, RETRO_DEVICE_INDEX_ANALOG_LEFT=0, RETRO_DEVICE_ID_ANALOG_X=0, RETRO_DEVICE_ID_ANALOG_Y=1, RETRO_DEVICE_ID_JOYPAD_MASK=256 };

using retro_environment_t=bool(*)(unsigned,void*);
using retro_video_refresh_t=void(*)(const void*,unsigned,unsigned,size_t);
using retro_audio_sample_t=void(*)(int16_t,int16_t);
using retro_audio_sample_batch_t=size_t(*)(const int16_t*,size_t);
using retro_input_poll_t=void(*)();
using retro_input_state_t=int16_t(*)(unsigned,unsigned,unsigned,unsigned);

static void* core=nullptr;
static std::string systemDir,saveDir;
static int pixelFormat=RETRO_PIXEL_FORMAT_XRGB8888;
static unsigned frameW=240,frameH=160;
static int sampleRate=44100;
static std::vector<uint32_t> frame;
static std::vector<int16_t> audioBuffer;
static bool buttons[16]={};
static int16_t analogX=0,analogY=0;

static void(*p_retro_init)()=nullptr;
static void(*p_retro_deinit)()=nullptr;
static void(*p_retro_set_environment)(retro_environment_t)=nullptr;
static void(*p_retro_set_video_refresh)(retro_video_refresh_t)=nullptr;
static void(*p_retro_set_audio_sample)(retro_audio_sample_t)=nullptr;
static void(*p_retro_set_audio_sample_batch)(retro_audio_sample_batch_t)=nullptr;
static void(*p_retro_set_input_poll)(retro_input_poll_t)=nullptr;
static void(*p_retro_set_input_state)(retro_input_state_t)=nullptr;
static void(*p_retro_set_controller_port_device)(unsigned,unsigned)=nullptr;
static bool(*p_retro_load_game)(const retro_game_info*)=nullptr;
static void(*p_retro_unload_game)()=nullptr;
static void(*p_retro_run)()=nullptr;
static void(*p_retro_reset)()=nullptr;
static void(*p_retro_get_system_av_info)(retro_system_av_info*)=nullptr;
static size_t(*p_retro_serialize_size)()=nullptr;
static bool(*p_retro_serialize)(void*,size_t)=nullptr;
static bool(*p_retro_unserialize)(const void*,size_t)=nullptr;

static void coreLog(int level,const char* fmt,...){
    va_list ap; va_start(ap,fmt);
    __android_log_vprint(level>=3?ANDROID_LOG_ERROR:ANDROID_LOG_INFO,"LibretroCore",fmt,ap);
    va_end(ap);
}

static bool envCb(unsigned cmd,void* data){
    switch(cmd){
        case ENV_GET_CAN_DUPE: *reinterpret_cast<bool*>(data)=true; return true;
        case ENV_GET_OVERSCAN: *reinterpret_cast<bool*>(data)=false; return true;
        case ENV_GET_SYSTEM_DIRECTORY: *reinterpret_cast<const char**>(data)=systemDir.c_str(); return true;
        case ENV_GET_SAVE_DIRECTORY: *reinterpret_cast<const char**>(data)=saveDir.c_str(); return true;
        case ENV_SET_PIXEL_FORMAT: pixelFormat=*reinterpret_cast<const int*>(data); return true;
        case ENV_GET_VARIABLE_UPDATE: *reinterpret_cast<bool*>(data)=false; return true;
        case ENV_GET_VARIABLE: { auto* v=reinterpret_cast<retro_variable*>(data); if(v) v->value=nullptr; return false; }
        case ENV_GET_LOG_INTERFACE: { auto* l=reinterpret_cast<retro_log_callback*>(data); l->log=coreLog; return true; }
        case ENV_GET_LANGUAGE: *reinterpret_cast<unsigned*>(data)=0; return true;
        case ENV_GET_INPUT_BITMASKS: return true;
        case ENV_SET_SYSTEM_AV_INFO: {
            auto* av=reinterpret_cast<retro_system_av_info*>(data);
            if(av){frameW=av->geometry.base_width;frameH=av->geometry.base_height;sampleRate=(int)av->timing.sample_rate;}
            return true;
        }
        case ENV_SET_VARIABLES:
        case ENV_SET_INPUT_DESCRIPTORS:
        case ENV_SET_SUPPORT_NO_GAME:
        case ENV_SET_ROTATION:
        case ENV_SET_MESSAGE:
        case ENV_SET_PERFORMANCE_LEVEL:
        case ENV_SET_CONTROLLER_INFO:
        case ENV_SET_MEMORY_MAPS: return true;
        default: return false;
    }
}

static void videoCb(const void* data,unsigned width,unsigned height,size_t pitch){
    if(!data)return;
    frameW=width; frameH=height; frame.resize((size_t)width*height);
    for(unsigned y=0;y<height;y++){
        const uint8_t* row=(const uint8_t*)data+y*pitch;
        for(unsigned x=0;x<width;x++){
            uint32_t argb=0xFF000000u;
            if(pixelFormat==RETRO_PIXEL_FORMAT_XRGB8888){
                uint32_t p=((const uint32_t*)row)[x]; argb|=p&0x00FFFFFFu;
            } else if(pixelFormat==RETRO_PIXEL_FORMAT_RGB565){
                uint16_t p=((const uint16_t*)row)[x];
                argb|=(((p>>11)&31)*255/31<<16)|(((p>>5)&63)*255/63<<8)|((p&31)*255/31);
            } else {
                uint16_t p=((const uint16_t*)row)[x];
                argb|=(((p>>10)&31)*255/31<<16)|(((p>>5)&31)*255/31<<8)|((p&31)*255/31);
            }
            frame[(size_t)y*width+x]=argb;
        }
    }
}

static void audioCb(int16_t l,int16_t r){ audioBuffer.push_back(l); audioBuffer.push_back(r); }
static size_t audioBatchCb(const int16_t*d,size_t n){ if(d&&n)audioBuffer.insert(audioBuffer.end(),d,d+n*2); return n; }
static void inputPollCb(){}
static int16_t inputStateCb(unsigned port,unsigned device,unsigned index,unsigned id){
    if(port!=0) return 0;
    if(device==RETRO_DEVICE_JOYPAD && index==0){
        if(id==RETRO_DEVICE_ID_JOYPAD_MASK){
            uint16_t mask=0;
            for(unsigned i=0;i<16;i++) if(buttons[i]) mask|=(uint16_t)(1u<<i);
            return static_cast<int16_t>(mask);
        }
        if(id<16) return buttons[id]?1:0;
    }
    if(device==RETRO_DEVICE_ANALOG && index==RETRO_DEVICE_INDEX_ANALOG_LEFT){
        if(id==RETRO_DEVICE_ID_ANALOG_X) return analogX;
        if(id==RETRO_DEVICE_ID_ANALOG_Y) return analogY;
    }
    return 0;
}

template<typename T>static bool sym(T&out,const char*n){ out=reinterpret_cast<T>(dlsym(core,n)); if(!out){LOGE("Missing symbol %s",n);return false;} return true; }
template<typename T>static void opt(T&out,const char*n){ out=reinterpret_cast<T>(dlsym(core,n)); }

extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_init(JNIEnv*e,jobject,jstring c,jstring s,jstring v){
    const char*cp=e->GetStringUTFChars(c,nullptr),*sp=e->GetStringUTFChars(s,nullptr),*sv=e->GetStringUTFChars(v,nullptr);
    const bool wantsAnalog = std::strstr(cp,"pcsx") != nullptr || std::strstr(cp,"ppsspp") != nullptr;
    systemDir=sp; saveDir=sv; core=dlopen(cp,RTLD_NOW|RTLD_LOCAL);
    e->ReleaseStringUTFChars(c,cp); e->ReleaseStringUTFChars(s,sp); e->ReleaseStringUTFChars(v,sv);
    if(!core){LOGE("dlopen: %s",dlerror());return JNI_FALSE;}
    if(!sym(p_retro_init,"retro_init")||!sym(p_retro_deinit,"retro_deinit")||!sym(p_retro_set_environment,"retro_set_environment")||
       !sym(p_retro_set_video_refresh,"retro_set_video_refresh")||!sym(p_retro_set_audio_sample,"retro_set_audio_sample")||
       !sym(p_retro_set_audio_sample_batch,"retro_set_audio_sample_batch")||!sym(p_retro_set_input_poll,"retro_set_input_poll")||
       !sym(p_retro_set_input_state,"retro_set_input_state")||!sym(p_retro_load_game,"retro_load_game")||
       !sym(p_retro_unload_game,"retro_unload_game")||!sym(p_retro_run,"retro_run")||!sym(p_retro_reset,"retro_reset")||
       !sym(p_retro_get_system_av_info,"retro_get_system_av_info")) return JNI_FALSE;
    opt(p_retro_set_controller_port_device,"retro_set_controller_port_device");
    opt(p_retro_serialize_size,"retro_serialize_size"); opt(p_retro_serialize,"retro_serialize"); opt(p_retro_unserialize,"retro_unserialize");
    p_retro_set_environment(envCb); p_retro_set_video_refresh(videoCb); p_retro_set_audio_sample(audioCb);
    p_retro_set_audio_sample_batch(audioBatchCb); p_retro_set_input_poll(inputPollCb); p_retro_set_input_state(inputStateCb);
    p_retro_init();
    if(p_retro_set_controller_port_device) p_retro_set_controller_port_device(0,wantsAnalog?RETRO_DEVICE_ANALOG:RETRO_DEVICE_JOYPAD);
    LOGI("Controller mode: %s",wantsAnalog?"analog":"joypad");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_setControllerDevice(JNIEnv*,jobject,jint device){
    if(p_retro_set_controller_port_device){
        unsigned d=(device==RETRO_DEVICE_ANALOG)?RETRO_DEVICE_ANALOG:RETRO_DEVICE_JOYPAD;
        p_retro_set_controller_port_device(0,d);
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_loadGame(JNIEnv*e,jobject,jstring path){
    const char*p=e->GetStringUTFChars(path,nullptr);
    retro_game_info info{p,nullptr,0,nullptr};
    bool ok=p_retro_load_game&&p_retro_load_game(&info);
    e->ReleaseStringUTFChars(path,p);
    if(ok&&p_retro_get_system_av_info){retro_system_av_info av{};p_retro_get_system_av_info(&av);frameW=av.geometry.base_width;frameH=av.geometry.base_height;sampleRate=(int)(av.timing.sample_rate>0?av.timing.sample_rate:44100);frame.resize((size_t)frameW*frameH);audioBuffer.clear();}
    return ok?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_runFrame(JNIEnv*e,jobject,jintArray p){if(!p_retro_run)return 0;p_retro_run();jsize n=e->GetArrayLength(p);size_t c=std::min(frame.size(),(size_t)n);if(c)e->SetIntArrayRegion(p,0,(jsize)c,(const jint*)frame.data());return(jint)c;}
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_getWidth(JNIEnv*,jobject){return frameW;}
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_getHeight(JNIEnv*,jobject){return frameH;}
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_getSampleRate(JNIEnv*,jobject){return sampleRate;}
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_readAudio(JNIEnv*e,jobject,jshortArray out){if(!out||audioBuffer.empty())return 0;size_t c=std::min((size_t)e->GetArrayLength(out),audioBuffer.size());e->SetShortArrayRegion(out,0,(jsize)c,(const jshort*)audioBuffer.data());audioBuffer.erase(audioBuffer.begin(),audioBuffer.begin()+(long)c);return(jint)c;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_saveState(JNIEnv*e,jobject,jstring path){if(!p_retro_serialize_size||!p_retro_serialize)return JNI_FALSE;size_t z=p_retro_serialize_size();if(!z)return JNI_FALSE;std::vector<uint8_t>s(z);if(!p_retro_serialize(s.data(),z))return JNI_FALSE;const char*p=e->GetStringUTFChars(path,nullptr);std::ofstream o(p,std::ios::binary|std::ios::trunc);e->ReleaseStringUTFChars(path,p);if(!o)return JNI_FALSE;o.write((const char*)s.data(),s.size());return o.good()?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_loadState(JNIEnv*e,jobject,jstring path){if(!p_retro_unserialize)return JNI_FALSE;const char*p=e->GetStringUTFChars(path,nullptr);std::ifstream in(p,std::ios::binary|std::ios::ate);e->ReleaseStringUTFChars(path,p);if(!in)return JNI_FALSE;auto z=in.tellg();if(z<=0)return JNI_FALSE;in.seekg(0);std::vector<uint8_t>s((size_t)z);if(!in.read((char*)s.data(),z))return JNI_FALSE;audioBuffer.clear();return p_retro_unserialize(s.data(),s.size())?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_setButton(JNIEnv*,jobject,jint id,jboolean p){if(id>=0&&id<16)buttons[id]=p;}
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_setAnalog(JNIEnv*,jobject,jint x,jint y){analogX=(int16_t)std::max(-32767,std::min(32767,(int)x));analogY=(int16_t)std::max(-32767,std::min(32767,(int)y));}
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_reset(JNIEnv*,jobject){if(p_retro_reset)p_retro_reset();}
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_unload(JNIEnv*,jobject){if(p_retro_unload_game)p_retro_unload_game();if(p_retro_deinit)p_retro_deinit();if(core)dlclose(core);core=nullptr;frame.clear();audioBuffer.clear();std::fill(std::begin(buttons),std::end(buttons),false);analogX=analogY=0;p_retro_set_controller_port_device=nullptr;}
