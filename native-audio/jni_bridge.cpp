#include <jni.h>

#include <cstdint>
#include <string>

#include "AudioEngine.h"

// JNI entry points for com.roxstar.voicedraft.NativeAudioBridge.
//
// NativeAudioBridge is a Kotlin `object` (singleton), so `external fun` compiles
// to an instance method — every native signature takes a `jobject`, not `jclass`.
//
// The engine is handed to Kotlin as an opaque jlong handle. Calls are kept
// coarse-grained: the whole stream configuration comes back in one array rather
// than one JNI call per field. Nothing here is ever called from the audio callback.

static_assert(sizeof(jlong) == sizeof(int64_t), "jlong must be 64-bit");

namespace {

roxstar::AudioEngine *asEngine(jlong handle) {
    return reinterpret_cast<roxstar::AudioEngine *>(handle);
}

jint toJint(roxstar::Status status) {
    return static_cast<jint>(status);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeHello(JNIEnv *env, jobject /* thiz */) {
    const std::string message = "hello from native-audio (C++17, NDK)";
    return env->NewStringUTF(message.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeCreate(JNIEnv * /*env*/, jobject /* thiz */) {
    return reinterpret_cast<jlong>(new roxstar::AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeDestroy(JNIEnv * /*env*/,
                                                            jobject /* thiz */,
                                                            jlong handle) {
    // ~AudioEngine closes any open stream before the callback target goes away.
    delete asEngine(handle);
}

JNIEXPORT jint JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeOpen(JNIEnv * /*env*/,
                                                         jobject /* thiz */,
                                                         jlong handle) {
    auto *engine = asEngine(handle);
    return engine == nullptr ? toJint(roxstar::Status::NoEngine) : toJint(engine->open());
}

JNIEXPORT jint JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeStart(JNIEnv * /*env*/,
                                                          jobject /* thiz */,
                                                          jlong handle) {
    auto *engine = asEngine(handle);
    return engine == nullptr ? toJint(roxstar::Status::NoEngine) : toJint(engine->start());
}

JNIEXPORT jint JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeStop(JNIEnv * /*env*/,
                                                         jobject /* thiz */,
                                                         jlong handle) {
    auto *engine = asEngine(handle);
    return engine == nullptr ? toJint(roxstar::Status::NoEngine) : toJint(engine->stop());
}

JNIEXPORT jint JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeClose(JNIEnv * /*env*/,
                                                          jobject /* thiz */,
                                                          jlong handle) {
    auto *engine = asEngine(handle);
    return engine == nullptr ? toJint(roxstar::Status::NoEngine) : toJint(engine->close());
}

JNIEXPORT jlongArray JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeGetConfig(JNIEnv *env,
                                                              jobject /* thiz */,
                                                              jlong handle) {
    int64_t values[roxstar::kIdxCount] = {0};
    values[roxstar::kIdxFormat] = static_cast<int64_t>(oboe::AudioFormat::Invalid);

    if (auto *engine = asEngine(handle); engine != nullptr) {
        engine->snapshot(values, roxstar::kIdxCount);
    }

    jlongArray out = env->NewLongArray(roxstar::kIdxCount);
    if (out == nullptr) {
        return nullptr;  // OutOfMemoryError is already pending.
    }
    env->SetLongArrayRegion(out, 0, roxstar::kIdxCount, reinterpret_cast<const jlong *>(values));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeGetLastResultText(JNIEnv *env,
                                                                      jobject /* thiz */,
                                                                      jlong handle) {
    auto *engine = asEngine(handle);
    return env->NewStringUTF(engine == nullptr ? "NoEngine" : engine->lastResultText());
}

JNIEXPORT jstring JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeGetOboeVersion(JNIEnv *env,
                                                                   jobject /* thiz */) {
    return env->NewStringUTF(OBOE_VERSION_TEXT);
}

}  // extern "C"
