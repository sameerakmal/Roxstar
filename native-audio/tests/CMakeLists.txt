cmake_minimum_required(VERSION 3.22.1)
project(native-audio-tests)

# Host-only test project. Builds RingBuffer/WavWriter/RecordingSession — none
# of which depend on Oboe, JNI or the Android NDK — with the system compiler,
# so these run without a device or emulator. AudioEngine itself (the
# Oboe/AAudio glue) is out of scope here and stays device-only, same as Phase 2.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Threads REQUIRED)

add_executable(native_audio_tests
    test_main.cpp
    test_ring_buffer.cpp
    test_wav_writer.cpp
    test_wav_reader.cpp
    test_playback_buffer.cpp
    test_recording_session.cpp
    test_effect_factory.cpp
    test_echo_effect.cpp
    test_reverb_effect.cpp
    test_pitch_shift_effect.cpp
    test_effects_integration.cpp
    ../WavWriter.cpp
    ../RecordingSession.cpp
    ../src/WavReader.cpp
    ../src/PlaybackBuffer.cpp
    ../src/effects/IEffect.cpp
    ../src/effects/EchoEffect.cpp
    ../src/effects/ReverbEffect.cpp
    ../src/effects/PitchShiftEffect.cpp
)

target_link_libraries(native_audio_tests PRIVATE Threads::Threads)

enable_testing()
add_test(NAME native_audio_tests COMMAND native_audio_tests)
