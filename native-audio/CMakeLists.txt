cmake_minimum_required(VERSION 3.22.1)
project(native-audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe is consumed as a prefab package published in the com.google.oboe:oboe AAR.
# AGP exposes it to CMake via android.buildFeatures.prefab = true.
find_package(oboe REQUIRED CONFIG)

# Phase 2: the JNI bridge plus the Oboe input-stream engine. Ring buffer, WAV
# writing, effects and playback arrive in later phases.
add_library(native-audio SHARED
    jni_bridge.cpp
    AudioEngine.cpp
)

target_include_directories(native-audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(native-audio
    oboe::oboe
    ${log-lib}
)
