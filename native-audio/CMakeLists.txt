cmake_minimum_required(VERSION 3.22.1)
project(native-audio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe is consumed as a prefab package published in the com.google.oboe:oboe AAR.
# AGP exposes it to CMake via android.buildFeatures.prefab = true.
find_package(oboe REQUIRED CONFIG)

# Phase 3 adds the recording pipeline: ring buffer, WAV writer and the writer
# thread that drains it. Effects and playback remain out of scope.
add_library(native-audio SHARED
    jni_bridge.cpp
    AudioEngine.cpp
    RecordingSession.cpp
    WavWriter.cpp
)

target_include_directories(native-audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(native-audio
    oboe::oboe
    ${log-lib}
)
