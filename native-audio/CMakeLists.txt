cmake_minimum_required(VERSION 3.22.1)
project(native-audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Phase 1: just the JNI bridge, to prove Gradle -> CMake -> NDK -> JNI works end to end.
# Oboe and the effects/capture/playback sources are added in later phases.
add_library(native-audio SHARED
    jni_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(native-audio
    ${log-lib}
)
