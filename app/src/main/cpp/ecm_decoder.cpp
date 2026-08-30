// ECM 1.0 decoder integration for Emu Hub.
// Decoder algorithm is compatible with Neill Corlett's ECM format.
// Original ECM utility: Copyright (C) 2002 Neill Corlett, GPL-2.0-or-later.

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>

namespace {
using u8 = uint8_t;
using u32 = uint32_t;

u8 eccF[256];
u8 eccB[256];
u32 edcLut[256];
bool tablesReady = false;

void initTables() {
    if (tablesReady) return;
    for (u32 i = 0; i < 256; ++i) {
        u32 j = (i << 1) ^ ((i & 0x80) ? 0x11D : 0);
        eccF[i] = static_cast<u8>(j);
        eccB[i ^ j] = static_cast<u8>(i);
        u32 edc = i;
        for (u32 k = 0; k < 8; ++k) edc = (edc >> 1) ^ ((edc & 1) ? 0xD8018001u : 0u);
        edcLut[i] = edc;
    }
    tablesReady = true;
}

u32 edcPartial(u32 edc, const u8* src, size_t size) {
    while (size--) edc = (edc >> 8) ^ edcLut[(edc ^ *src++) & 0xFF];
    return edc;
}

void writeEdc(const u8* src, size_t size, u8* dest) {
    u32 edc = edcPartial(0, src, size);
    dest[0] = static_cast<u8>(edc);
    dest[1] = static_cast<u8>(edc >> 8);
    dest[2] = static_cast<u8>(edc >> 16);
    dest[3] = static_cast<u8>(edc >> 24);
}

void eccBlock(u8* src, u32 majorCount, u32 minorCount, u32 majorMult, u32 minorInc, u8* dest) {
    const u32 size = majorCount * minorCount;
    for (u32 major = 0; major < majorCount; ++major) {
        u32 index = (major >> 1) * majorMult + (major & 1);
        u8 a = 0, b = 0;
        for (u32 minor = 0; minor < minorCount; ++minor) {
            const u8 t = src[index];
            index += minorInc;
            if (index >= size) index -= size;
            a ^= t;
            b ^= t;
            a = eccF[a];
        }
        a = eccB[eccF[a] ^ b];
        dest[major] = a;
        dest[major + majorCount] = a ^ b;
    }
}

void eccGenerate(u8* sector, bool zeroAddress) {
    u8 address[4]{};
    if (zeroAddress) {
        for (int i = 0; i < 4; ++i) { address[i] = sector[12 + i]; sector[12 + i] = 0; }
    }
    eccBlock(sector + 0x0C, 86, 24, 2, 86, sector + 0x81C);
    eccBlock(sector + 0x0C, 52, 43, 86, 88, sector + 0x8C8);
    if (zeroAddress) for (int i = 0; i < 4; ++i) sector[12 + i] = address[i];
}

void rebuildSector(u8* sector, int type) {
    if (type == 1) {
        writeEdc(sector, 0x810, sector + 0x810);
        std::memset(sector + 0x814, 0, 8);
        eccGenerate(sector, false);
    } else if (type == 2) {
        writeEdc(sector + 0x10, 0x808, sector + 0x818);
        eccGenerate(sector, true);
    } else if (type == 3) {
        writeEdc(sector + 0x10, 0x91C, sector + 0x92C);
    }
}

bool readExact(FILE* f, void* p, size_t n) { return std::fread(p, 1, n, f) == n; }
bool writeExact(FILE* f, const void* p, size_t n) { return std::fwrite(p, 1, n, f) == n; }

bool decodeEcmFile(const char* inputPath, const char* outputPath) {
    initTables();
    FILE* in = std::fopen(inputPath, "rb");
    if (!in) return false;
    FILE* out = std::fopen(outputPath, "wb");
    if (!out) { std::fclose(in); return false; }

    bool ok = false;
    u32 checkedc = 0;
    u8 sector[2352];

    if (std::fgetc(in) != 'E' || std::fgetc(in) != 'C' || std::fgetc(in) != 'M' || std::fgetc(in) != 0) goto done;

    for (;;) {
        int c = std::fgetc(in);
        if (c == EOF) goto done;
        const u32 type = static_cast<u32>(c) & 3u;
        u32 num = (static_cast<u32>(c) >> 2) & 0x1Fu;
        int bits = 5;
        while (c & 0x80) {
            c = std::fgetc(in);
            if (c == EOF || bits >= 32) goto done;
            num |= (static_cast<u32>(c & 0x7F) << bits);
            bits += 7;
        }
        if (num == 0xFFFFFFFFu) break;
        ++num;
        if (num >= 0x80000000u) goto done;

        if (type == 0) {
            while (num) {
                const size_t chunk = num > sizeof(sector) ? sizeof(sector) : num;
                if (!readExact(in, sector, chunk)) goto done;
                checkedc = edcPartial(checkedc, sector, chunk);
                if (!writeExact(out, sector, chunk)) goto done;
                num -= static_cast<u32>(chunk);
            }
        } else {
            while (num--) {
                std::memset(sector, 0, sizeof(sector));
                std::memset(sector + 1, 0xFF, 10);
                if (type == 1) {
                    sector[0x0F] = 0x01;
                    if (!readExact(in, sector + 0x00C, 0x003) || !readExact(in, sector + 0x010, 0x800)) goto done;
                    rebuildSector(sector, 1);
                    checkedc = edcPartial(checkedc, sector, 2352);
                    if (!writeExact(out, sector, 2352)) goto done;
                } else if (type == 2 || type == 3) {
                    sector[0x0F] = 0x02;
                    const size_t payload = type == 2 ? 0x804 : 0x918;
                    if (!readExact(in, sector + 0x014, payload)) goto done;
                    std::memcpy(sector + 0x10, sector + 0x14, 4);
                    rebuildSector(sector, static_cast<int>(type));
                    checkedc = edcPartial(checkedc, sector + 0x10, 2336);
                    if (!writeExact(out, sector + 0x10, 2336)) goto done;
                } else goto done;
            }
        }
    }

    if (!readExact(in, sector, 4)) goto done;
    if (sector[0] != static_cast<u8>(checkedc) || sector[1] != static_cast<u8>(checkedc >> 8) ||
        sector[2] != static_cast<u8>(checkedc >> 16) || sector[3] != static_cast<u8>(checkedc >> 24)) goto done;
    ok = true;

done:
    std::fclose(in);
    std::fclose(out);
    if (!ok) std::remove(outputPath);
    return ok;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ric_emuhub_core_NativeBridge_decodeEcm(JNIEnv* env, jobject, jstring input, jstring output) {
    const char* inPath = env->GetStringUTFChars(input, nullptr);
    const char* outPath = env->GetStringUTFChars(output, nullptr);
    const bool ok = decodeEcmFile(inPath, outPath);
    env->ReleaseStringUTFChars(input, inPath);
    env->ReleaseStringUTFChars(output, outPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}
