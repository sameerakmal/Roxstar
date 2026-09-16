#include <jni.h>
#include <string>

// JNI entry point for com.roxstar.voicedraft.NativeAudioBridge.nativeHello().
//
// NativeAudioBridge is a Kotlin `object` (singleton), so `external fun` compiles
// to an instance method — the native signature takes a `jobject`, not `jclass`.
//
// Phase 1 only: proves the Gradle -> CMake -> NDK -> JNI -> Kotlin chain builds and
// runs. No audio/Oboe code lives here yet.
extern "C" JNIEXPORT jstring JNICALL
Java_com_roxstar_voicedraft_NativeAudioBridge_nativeHello(JNIEnv *env, jobject /* thiz */) {
    std::string message = "hello from native-audio (C++17, NDK)";
    return env->NewStringUTF(message.c_str());
}
