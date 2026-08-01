// JNI bridge: exposes the native search + protocol parsing to Kotlin.
// - NativeLib.search(targetHex, pictureId, paramsDoubleArray, textureIndex)
//   → returns SearchResult or null
// - NativeLib.loadTexture(textureIndex, byteArray) → bool
// - NativeLib.feedTcpPayload(flowId, byteArray) -> DyeParams or null
#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "pb.hpp"
#include "snappy_raw.hpp"
#include "search.hpp"
#include "texture.hpp"

#define LOG_TAG "of-color-picker"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── texture cache ──────────────────────────────────────────────────────────
static std::mutex g_tex_mtx;
static Texture g_tex[5];  // index 1..4
static bool g_tex_ok[5] = {false, false, false, false, false};

static const Texture* cached_texture(uint32_t pid) {
    uint32_t id = pid;
    if (id < 1) id = 1;
    if (id > 4) id = 4;
    std::lock_guard<std::mutex> lk(g_tex_mtx);
    if (!g_tex_ok[id]) return nullptr;
    return &g_tex[id];
}

// ── protocol framer (mirrors sniffer.cpp frame_and_dispatch) ───────────────
// Processes a reassembled TCP stream buffer, returns the first msg_id==1652
// payload found. The Kotlin side feeds per-flow buffers.
static bool frame_and_dispatch(std::vector<uint8_t>& buf,
                                uint32_t& out_pid,
                                std::vector<double>& out_params) {
    const size_t kCap = 2 * 1024 * 1024;
    if (buf.size() > kCap)
        buf.erase(buf.begin(), buf.end() - 64 * 1024);

    const size_t n = buf.size();
    size_t pos = 0;
    while (true) {
        if (n - pos < 2) break;
        uint32_t hl = ((uint32_t)buf[pos] << 8) | buf[pos + 1];
        if (hl > 20 * 1024) {
            pos += 2;
            continue;
        }
        if (n - pos < 2 + (size_t)hl) break;
        uint32_t msg_id, flag, body_len;
        if (!pb::parse_packet_head(&buf[pos + 2], hl, msg_id, flag, body_len)) {
            pos += 2;
            continue;
        }
        size_t need = 2 + (size_t)hl + body_len;
        if (n - pos < need) break;
        const uint8_t* body_ptr = &buf[pos + 2 + hl];
        size_t blen = body_len;
        pos += need;
        const uint8_t* bp = body_ptr;
        size_t bl = blen;
        std::vector<uint8_t> dec;
        if (flag == 1) {
            if (!snap::uncompress(body_ptr, blen, dec)) continue;
            bp = dec.data();
            bl = dec.size();
        }
        if (msg_id != 1652) continue;
        if (!pb::parse_colorant_rsp(bp, bl, out_pid, out_params)) continue;
        if (pos > 0) buf.erase(buf.begin(), buf.begin() + pos);
        return true;
    }
    if (pos > 0) buf.erase(buf.begin(), buf.begin() + pos);
    return false;
}

static std::mutex g_flow_mtx;
static std::unordered_map<uint32_t, std::vector<uint8_t>> g_flows;

// ── JNI implementations ────────────────────────────────────────────────────

extern "C" {

// Load a PNG texture from a byte array (read from Android assets)
JNIEXPORT jboolean JNICALL
Java_com_of_colorpicker_NativeLib_loadTexture(JNIEnv* env, jobject thiz,
                                               jint texture_index,
                                               jbyteArray data) {
    uint32_t id = (uint32_t)texture_index;
    if (id < 1) id = 1;
    if (id > 4) id = 4;

    jsize len = env->GetArrayLength(data);
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    if (!buf) return JNI_FALSE;

    Texture tex;
    bool ok = load_texture_mem((const uint8_t*)buf, (size_t)len, tex);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);

    if (ok) {
        std::lock_guard<std::mutex> lk(g_tex_mtx);
        g_tex[id] = std::move(tex);
        g_tex_ok[id] = true;
        LOGI("Loaded texture %d (%dx%d)", id, g_tex[id].w, g_tex[id].h);
    } else {
        LOGE("Failed to load texture %d", id);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Run the uvy scan search
JNIEXPORT jobject JNICALL
Java_com_of_colorpicker_NativeLib_search(JNIEnv* env, jobject thiz,
                                          jstring target_hex,
                                          jint picture_id,
                                          jdoubleArray params,
                                          jint texture_index) {
    const char* hex_c = env->GetStringUTFChars(target_hex, nullptr);
    if (!hex_c) return nullptr;
    std::string target(hex_c);
    env->ReleaseStringUTFChars(target_hex, hex_c);

    // Strip leading '#'
    if (!target.empty() && target[0] == '#') target = target.substr(1);

    uint32_t pid = (uint32_t)picture_id;
    uint32_t tid = pid;
    if (tid < 1) tid = 1;
    if (tid > 4) tid = 4;

    // Get params
    jsize plen = env->GetArrayLength(params);
    std::vector<double> pvec(plen);
    env->GetDoubleArrayRegion(params, 0, plen, pvec.data());

    const Texture* tex = cached_texture(tid);
    if (!tex) {
        LOGE("Texture %d not loaded", tid);
        return nullptr;
    }

    Result r;
    if (!search(target, pid, pvec, tex, r)) {
        return nullptr;
    }

    // Construct SearchResult Kotlin object
    // We'll return a simple object via a helper method on NativeLib
    // Instead, return a flat array: [sim, uvy, slot, r0,g0,b0,a0, r1,g1,b1,a1, ...]
    // Kotlin will unpack it. This avoids complex JNI object construction.
    jdouble result[23];  // sim, uvy, slot, then 5 * (r,g,b,a) = 3 + 20 = 23
    result[0] = r.sim;
    result[1] = r.uvy;
    result[2] = (double)r.slot;
    for (int i = 0; i < 5; ++i) {
        result[3 + i * 4 + 0] = (double)r.colors[i].r;
        result[3 + i * 4 + 1] = (double)r.colors[i].g;
        result[3 + i * 4 + 2] = (double)r.colors[i].b;
        result[3 + i * 4 + 3] = (double)r.colors[i].a;
    }

    // Also need to return the matched hex string. We'll use a String array of 2:
    // [0] = matched hex, [1] = target hex
    // Actually, let's just return the double array + use separate method for hex.
    // Simpler: return double array, Kotlin calls getMatchedHex() separately.

    jdoubleArray out = env->NewDoubleArray(23);
    env->SetDoubleArrayRegion(out, 0, 23, result);

    // Store the hex strings in a static for retrieval
    // (This is a bit hacky but avoids complex JNI object construction)
    // Better approach: store in the NativeLib instance via field access.
    // Simplest: use SetObjectField on the NativeLib object.

    // Find the NativeLib class and set fields
    jclass cls = env->GetObjectClass(thiz);
    jfieldID fidHex = env->GetFieldID(cls, "lastMatchedHex", "Ljava/lang/String;");
    jfieldID fidTargetHex = env->GetFieldID(cls, "lastTargetHex", "Ljava/lang/String;");
    if (fidHex && fidTargetHex) {
        jstring jhex = env->NewStringUTF(r.hex.c_str());
        jstring jtarget = env->NewStringUTF(r.target_hex.c_str());
        env->SetObjectField(thiz, fidHex, jhex);
        env->SetObjectField(thiz, fidTargetHex, jtarget);
    }

    return out;
}

// Feed downstream bytes for one SOCKS TCP connection. Returns
// [picture_id, param0, param1, ...] when a complete response is found.
JNIEXPORT jdoubleArray JNICALL
Java_com_of_colorpicker_NativeLib_feedTcpPayload(JNIEnv* env, jobject thiz,
                                                  jint flow_id,
                                                  jbyteArray payload) {
    jsize plen = env->GetArrayLength(payload);
    jbyte* data = env->GetByteArrayElements(payload, nullptr);
    if (!data) return nullptr;

    std::lock_guard<std::mutex> lk(g_flow_mtx);
    std::vector<uint8_t>& buf = g_flows[(uint32_t)flow_id];
    buf.insert(buf.end(), (const uint8_t*)data,
               (const uint8_t*)data + (size_t)plen);
    env->ReleaseByteArrayElements(payload, data, JNI_ABORT);

    uint32_t out_pid;
    std::vector<double> out_params;
    jdoubleArray result = nullptr;

    if (frame_and_dispatch(buf, out_pid, out_params)) {
        // Return [picture_id, param0, param1, ...]
        jsize rlen = 1 + (jsize)out_params.size();
        result = env->NewDoubleArray(rlen);
        std::vector<jdouble> rdata(rlen);
        rdata[0] = (jdouble)out_pid;
        for (size_t i = 0; i < out_params.size(); ++i)
            rdata[1 + i] = out_params[i];
        env->SetDoubleArrayRegion(result, 0, rlen, rdata.data());
    }

    return result;
}

JNIEXPORT void JNICALL
Java_com_of_colorpicker_NativeLib_closeTcpFlow(JNIEnv* env, jobject thiz,
                                                jint flow_id) {
    std::lock_guard<std::mutex> lk(g_flow_mtx);
    g_flows.erase((uint32_t)flow_id);
}

}  // extern "C"
