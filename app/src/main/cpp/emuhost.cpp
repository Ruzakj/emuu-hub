#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <fstream>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EmuHost", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EmuHost", __VA_ARGS__)

struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };

enum { RETRO_PIXEL_FORMAT_0RGB1555 = 0, RETRO_PIXEL_FORMAT_XRGB8888 = 1, RETRO_PIXEL_FORMAT_RGB565 = 2 };
enum { ENV_GET_CAN_DUPE = 3, ENV_GET_SYSTEM_DIRECTORY = 9, ENV_SET_PIXEL_FORMAT = 10, ENV_SET_INPUT_DESCRIPTORS = 11, ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16, ENV_GET_VARIABLE_UPDATE = 17, ENV_SET_SUPPORT_NO_GAME = 18, ENV_GET_SAVE_DIRECTORY = 31, ENV_GET_LANGUAGE = 39 };

using retro_environment_t = bool(*)(unsigned, void*);
using retro_video_refresh_t = void(*)(const void*, unsigned, unsigned, size_t);
using retro_audio_sample_t = void(*)(int16_t, int16_t);
using retro_audio_sample_batch_t = size_t(*)(const int16_t*, size_t);
using retro_input_poll_t = void(*)();
using retro_input_state_t = int16_t(*)(unsigned, unsigned, unsigned, unsigned);

static void* core = nullptr;
static std::string systemDir, saveDir;
static int pixelFormat = RETRO_PIXEL_FORMAT_XRGB8888;
static unsigned frameW = 240, frameH = 160;
static int sampleRate = 44100;
static std::vector<uint32_t> frame;
static std::vector<int16_t> audioBuffer;
static bool buttons[16] = {};

static void (*p_retro_init)() = nullptr;
static void (*p_retro_deinit)() = nullptr;
static void (*p_retro_set_environment)(retro_environment_t) = nullptr;
static void (*p_retro_set_video_refresh)(retro_video_refresh_t) = nullptr;
static void (*p_retro_set_audio_sample)(retro_audio_sample_t) = nullptr;
static void (*p_retro_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
static void (*p_retro_set_input_poll)(retro_input_poll_t) = nullptr;
static void (*p_retro_set_input_state)(retro_input_state_t) = nullptr;
static bool (*p_retro_load_game)(const retro_game_info*) = nullptr;
static void (*p_retro_unload_game)() = nullptr;
static void (*p_retro_run)() = nullptr;
static void (*p_retro_reset)() = nullptr;
static void (*p_retro_get_system_av_info)(retro_system_av_info*) = nullptr;
static size_t (*p_retro_serialize_size)() = nullptr;
static bool (*p_retro_serialize)(void*, size_t) = nullptr;
static bool (*p_retro_unserialize)(const void*, size_t) = nullptr;

static bool envCb(unsigned cmd, void* data) {
    switch (cmd) {
        case ENV_GET_CAN_DUPE: *reinterpret_cast<bool*>(data) = true; return true;
        case ENV_GET_SYSTEM_DIRECTORY: *reinterpret_cast<const char**>(data) = systemDir.c_str(); return true;
        case ENV_GET_SAVE_DIRECTORY: *reinterpret_cast<const char**>(data) = saveDir.c_str(); return true;
        case ENV_SET_PIXEL_FORMAT: pixelFormat = *reinterpret_cast<const int*>(data); return true;
        case ENV_GET_VARIABLE_UPDATE: *reinterpret_cast<bool*>(data) = false; return true;
        case ENV_GET_VARIABLE: return false;
        case ENV_SET_VARIABLES:
        case ENV_SET_INPUT_DESCRIPTORS:
        case ENV_SET_SUPPORT_NO_GAME: return true;
        case ENV_GET_LANGUAGE: *reinterpret_cast<unsigned*>(data) = 0; return true;
        default: return false;
    }
}

static void videoCb(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;
    frameW = width; frameH = height;
    frame.resize(static_cast<size_t>(width) * height);
    for (unsigned y = 0; y < height; ++y) {
        const uint8_t* row = static_cast<const uint8_t*>(data) + y * pitch;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t argb = 0xFF000000u;
            if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p = reinterpret_cast<const uint32_t*>(row)[x];
                argb |= p & 0x00FFFFFFu;
            } else if (pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
                uint16_t p = reinterpret_cast<const uint16_t*>(row)[x];
                uint32_t r = ((p >> 11) & 31) * 255 / 31;
                uint32_t g = ((p >> 5) & 63) * 255 / 63;
                uint32_t b = (p & 31) * 255 / 31;
                argb |= (r << 16) | (g << 8) | b;
            } else {
                uint16_t p = reinterpret_cast<const uint16_t*>(row)[x];
                uint32_t r = ((p >> 10) & 31) * 255 / 31;
                uint32_t g = ((p >> 5) & 31) * 255 / 31;
                uint32_t b = (p & 31) * 255 / 31;
                argb |= (r << 16) | (g << 8) | b;
            }
            frame[static_cast<size_t>(y) * width + x] = argb;
        }
    }
}

static void audioCb(int16_t left, int16_t right) {
    audioBuffer.push_back(left);
    audioBuffer.push_back(right);
}
static size_t audioBatchCb(const int16_t* data, size_t frames) {
    if (data && frames) audioBuffer.insert(audioBuffer.end(), data, data + frames * 2);
    return frames;
}
static void inputPollCb() {}
static int16_t inputStateCb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != 1 || index != 0 || id >= 16) return 0;
    return buttons[id] ? 1 : 0;
}

template<typename T> static bool sym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(core, name));
    if (!out) { LOGE("Missing symbol %s", name); return false; }
    return true;
}

template<typename T> static bool optionalSym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(core, name));
    return out != nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_init(JNIEnv* env, jobject, jstring corePath, jstring sys, jstring save) {
    const char* cp = env->GetStringUTFChars(corePath, nullptr);
    const char* sp = env->GetStringUTFChars(sys, nullptr);
    const char* sv = env->GetStringUTFChars(save, nullptr);
    systemDir = sp; saveDir = sv;
    core = dlopen(cp, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(corePath, cp); env->ReleaseStringUTFChars(sys, sp); env->ReleaseStringUTFChars(save, sv);
    if (!core) { LOGE("dlopen failed: %s", dlerror()); return JNI_FALSE; }
    if (!sym(p_retro_init,"retro_init") || !sym(p_retro_deinit,"retro_deinit") || !sym(p_retro_set_environment,"retro_set_environment") ||
        !sym(p_retro_set_video_refresh,"retro_set_video_refresh") || !sym(p_retro_set_audio_sample,"retro_set_audio_sample") ||
        !sym(p_retro_set_audio_sample_batch,"retro_set_audio_sample_batch") || !sym(p_retro_set_input_poll,"retro_set_input_poll") ||
        !sym(p_retro_set_input_state,"retro_set_input_state") || !sym(p_retro_load_game,"retro_load_game") ||
        !sym(p_retro_unload_game,"retro_unload_game") || !sym(p_retro_run,"retro_run") || !sym(p_retro_reset,"retro_reset") ||
        !sym(p_retro_get_system_av_info,"retro_get_system_av_info")) return JNI_FALSE;
    optionalSym(p_retro_serialize_size, "retro_serialize_size");
    optionalSym(p_retro_serialize, "retro_serialize");
    optionalSym(p_retro_unserialize, "retro_unserialize");
    p_retro_set_environment(envCb); p_retro_set_video_refresh(videoCb); p_retro_set_audio_sample(audioCb);
    p_retro_set_audio_sample_batch(audioBatchCb); p_retro_set_input_poll(inputPollCb); p_retro_set_input_state(inputStateCb);
    p_retro_init();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_loadGame(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    retro_game_info info{p, nullptr, 0, nullptr};
    bool ok = p_retro_load_game && p_retro_load_game(&info);
    env->ReleaseStringUTFChars(path, p);
    if (ok && p_retro_get_system_av_info) {
        retro_system_av_info av{}; p_retro_get_system_av_info(&av);
        frameW = av.geometry.base_width; frameH = av.geometry.base_height;
        sampleRate = static_cast<int>(av.timing.sample_rate > 0 ? av.timing.sample_rate : 44100.0);
        frame.resize(static_cast<size_t>(frameW) * frameH);
        audioBuffer.clear();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_runFrame(JNIEnv* env, jobject, jintArray pixels) {
    if (!p_retro_run) return 0;
    p_retro_run();
    jsize n = env->GetArrayLength(pixels);
    size_t count = frame.size(); if (count > static_cast<size_t>(n)) count = n;
    if (count) env->SetIntArrayRegion(pixels, 0, static_cast<jsize>(count), reinterpret_cast<const jint*>(frame.data()));
    return static_cast<jint>(count);
}
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_getWidth(JNIEnv*, jobject) { return frameW; }
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_getHeight(JNIEnv*, jobject) { return frameH; }
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_getSampleRate(JNIEnv*, jobject) { return sampleRate; }
extern "C" JNIEXPORT jint JNICALL Java_com_ric_emuhub_core_NativeBridge_readAudio(JNIEnv* env, jobject, jshortArray out) {
    if (!out || audioBuffer.empty()) return 0;
    jsize capacity = env->GetArrayLength(out);
    size_t count = std::min(static_cast<size_t>(capacity), audioBuffer.size());
    env->SetShortArrayRegion(out, 0, static_cast<jsize>(count), reinterpret_cast<const jshort*>(audioBuffer.data()));
    audioBuffer.erase(audioBuffer.begin(), audioBuffer.begin() + static_cast<long>(count));
    return static_cast<jint>(count);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_saveState(JNIEnv* env, jobject, jstring path) {
    if (!p_retro_serialize_size || !p_retro_serialize) return JNI_FALSE;
    const size_t size = p_retro_serialize_size();
    if (!size) return JNI_FALSE;
    std::vector<uint8_t> state(size);
    if (!p_retro_serialize(state.data(), state.size())) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::ofstream out(p, std::ios::binary | std::ios::trunc);
    env->ReleaseStringUTFChars(path, p);
    if (!out) return JNI_FALSE;
    out.write(reinterpret_cast<const char*>(state.data()), static_cast<std::streamsize>(state.size()));
    return out.good() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_ric_emuhub_core_NativeBridge_loadState(JNIEnv* env, jobject, jstring path) {
    if (!p_retro_unserialize) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::ifstream in(p, std::ios::binary | std::ios::ate);
    env->ReleaseStringUTFChars(path, p);
    if (!in) return JNI_FALSE;
    const std::streamsize size = in.tellg();
    if (size <= 0) return JNI_FALSE;
    in.seekg(0, std::ios::beg);
    std::vector<uint8_t> state(static_cast<size_t>(size));
    if (!in.read(reinterpret_cast<char*>(state.data()), size)) return JNI_FALSE;
    audioBuffer.clear();
    return p_retro_unserialize(state.data(), state.size()) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_setButton(JNIEnv*, jobject, jint id, jboolean pressed) { if (id >= 0 && id < 16) buttons[id] = pressed; }
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_reset(JNIEnv*, jobject) { if (p_retro_reset) p_retro_reset(); }
extern "C" JNIEXPORT void JNICALL Java_com_ric_emuhub_core_NativeBridge_unload(JNIEnv*, jobject) {
    if (p_retro_unload_game) p_retro_unload_game();
    if (p_retro_deinit) p_retro_deinit();
    if (core) dlclose(core); core = nullptr; frame.clear(); audioBuffer.clear();
}
