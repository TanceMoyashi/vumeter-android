// AAudio capture for the VU Meter app.
//
// Opens an AAudio input stream (low-latency, shared mode) and delivers
// interleaved float samples (-1..1) through a ring buffer that the Kotlin
// side drains with nativeRead(). The stream format is PCM_FLOAT when the
// device accepts it, with a PCM_I16 fallback converted in the callback.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <vector>

#define LOG_TAG "AAudioCapture"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

AAudioStream* g_stream = nullptr;
std::vector<float> g_ring;  // interleaved float samples
size_t g_ring_capacity_frames = 0;
int g_channels = 2;
size_t g_head = 0;       // write position (frames)
size_t g_tail = 0;       // read position (frames)
size_t g_available = 0;  // frames ready to read
std::mutex g_mutex;
std::condition_variable g_cond;
std::atomic<bool> g_running{false};
std::atomic<int64_t> g_dropped_frames{0};
aaudio_format_t g_format = AAUDIO_FORMAT_PCM_FLOAT;

aaudio_data_callback_result_t dataCallback(AAudioStream* /*stream*/,
                                           void* /*userData*/,
                                           void* audioData, int32_t numFrames) {
    if (!g_running.load()) return AAUDIO_CALLBACK_RESULT_STOP;
    const int32_t ch = g_channels;
    const float* srcF = nullptr;
    std::vector<float> tmp;
    if (g_format == AAUDIO_FORMAT_PCM_FLOAT) {
        srcF = static_cast<const float*>(audioData);
    } else {
        const int16_t* srcI = static_cast<const int16_t*>(audioData);
        tmp.resize(static_cast<size_t>(numFrames) * static_cast<size_t>(ch));
        for (size_t i = 0; i < tmp.size(); ++i) tmp[i] = srcI[i] / 32768.0f;
        srcF = tmp.data();
    }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        for (int32_t f = 0; f < numFrames; ++f) {
            if (g_available >= g_ring_capacity_frames) {
                // Ring full: drop the oldest frame.
                g_tail = (g_tail + 1) % g_ring_capacity_frames;
                --g_available;
                ++g_dropped_frames;
            }
            const size_t idx = g_head * static_cast<size_t>(ch);
            for (int32_t c = 0; c < ch; ++c) g_ring[idx + c] = srcF[f * ch + c];
            g_head = (g_head + 1) % g_ring_capacity_frames;
            ++g_available;
        }
    }
    g_cond.notify_all();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void errorCallback(AAudioStream* /*stream*/, void* /*userData*/,
                   aaudio_result_t error) {
    LOGE("AAudio error callback: %s", AAudio_convertResultToText(error));
    g_running.store(false);
    g_cond.notify_all();
}

bool openStream(int deviceId, int sampleRate, int channels,
                aaudio_input_preset_t preset, int* outRate, int* outChannels) {
    const aaudio_format_t formats[2] = {AAUDIO_FORMAT_PCM_FLOAT,
                                        AAUDIO_FORMAT_PCM_I16};
    for (aaudio_format_t fmt : formats) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setFormat(builder, fmt);
        AAudioStreamBuilder_setChannelCount(builder, channels);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate);
        AAudioStreamBuilder_setPerformanceMode(builder,
                                               AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setInputPreset(builder, preset);
        if (deviceId != 0) AAudioStreamBuilder_setDeviceId(builder, deviceId);
        AAudioStreamBuilder_setDataCallback(builder, dataCallback, nullptr);
        AAudioStreamBuilder_setErrorCallback(builder, errorCallback, nullptr);
        AAudioStream* stream = nullptr;
        const aaudio_result_t res =
            AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);
        if (res == AAUDIO_OK && stream != nullptr) {
            g_stream = stream;
            g_format = fmt;
            *outRate = AAudioStream_getSampleRate(stream);
            *outChannels = AAudioStream_getChannelCount(stream);
            return true;
        }
    }
    return false;
}

// Preset codes from Kotlin: 0 = GENERIC (MIC), 1 = VOICE_RECOGNITION,
// 2 = UNPROCESSED.
aaudio_input_preset_t mapPreset(int code) {
    switch (code) {
        case 1:
            return AAUDIO_INPUT_PRESET_VOICE_RECOGNITION;
        case 2:
            return AAUDIO_INPUT_PRESET_UNPROCESSED;
        default:
            return AAUDIO_INPUT_PRESET_GENERIC;
    }
}

}  // namespace

extern "C" {

JNIEXPORT jintArray JNICALL
Java_jp_himawari_vumeter_AAudioCapture_nativeStart(JNIEnv* env,
                                                   jobject /*thiz*/,
                                                   jint deviceId,
                                                   jint sampleRate,
                                                   jint channels,
                                                   jint presetCode) {
    if (g_stream != nullptr) return nullptr;
    int actualRate = 0;
    int actualChannels = 0;
    if (!openStream(deviceId, sampleRate, channels, mapPreset(presetCode),
                    &actualRate, &actualChannels)) {
        return nullptr;
    }
    g_channels = actualChannels;
    g_ring_capacity_frames = static_cast<size_t>(actualRate);  // ~1 second
    g_ring.assign(g_ring_capacity_frames * static_cast<size_t>(actualChannels),
                  0.0f);
    g_head = g_tail = g_available = 0;
    g_dropped_frames.store(0);
    g_running.store(true);
    if (AAudioStream_requestStart(g_stream) != AAUDIO_OK) {
        g_running.store(false);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return nullptr;
    }
    jint vals[2] = {actualRate, actualChannels};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, 2, vals);
    return result;
}

JNIEXPORT jint JNICALL
Java_jp_himawari_vumeter_AAudioCapture_nativeRead(JNIEnv* env,
                                                  jobject /*thiz*/,
                                                  jfloatArray out,
                                                  jint maxFrames) {
    std::unique_lock<std::mutex> lock(g_mutex);
    g_cond.wait_for(lock, std::chrono::milliseconds(60),
                    [] { return g_available > 0 || !g_running.load(); });
    if (g_available == 0) return g_running.load() ? 0 : -1;
    const size_t frames =
        std::min(static_cast<size_t>(maxFrames), g_available);
    jfloat* dst = env->GetFloatArrayElements(out, nullptr);
    if (dst == nullptr) return -1;
    const int ch = g_channels;
    for (size_t f = 0; f < frames; ++f) {
        const size_t idx = g_tail * static_cast<size_t>(ch);
        for (int32_t c = 0; c < ch; ++c) dst[f * ch + c] = g_ring[idx + c];
        g_tail = (g_tail + 1) % g_ring_capacity_frames;
        --g_available;
    }
    env->ReleaseFloatArrayElements(out, dst, 0);
    return static_cast<jint>(frames);
}

JNIEXPORT void JNICALL Java_jp_himawari_vumeter_AAudioCapture_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    g_running.store(false);
    g_cond.notify_all();
    if (g_stream != nullptr) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
}

JNIEXPORT jint JNICALL Java_jp_himawari_vumeter_AAudioCapture_nativeXRunCount(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_stream == nullptr) return -1;
    return static_cast<jint>(AAudioStream_getXRunCount(g_stream));
}

JNIEXPORT jlong JNICALL
Java_jp_himawari_vumeter_AAudioCapture_nativeDroppedFrames(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(g_dropped_frames.load());
}

}  // extern "C"
