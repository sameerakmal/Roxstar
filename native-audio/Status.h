#ifndef ROXSTAR_STATUS_H
#define ROXSTAR_STATUS_H

#include <cstdint>

namespace roxstar {

// Status codes returned across JNI. Mirrored by AudioStatus in AudioEngine.kt.
// Shared by AudioEngine and RecordingSession so both can report through one
// Kotlin-facing enum without a circular include between their headers.
enum class Status : int32_t {
    Ok               =   0,
    InvalidState     =  -1,
    OpenFailed       =  -2,
    StartFailed      =  -3,
    StopFailed       =  -4,
    CloseFailed      =  -5,
    NoEngine         =  -6,
    Disconnected     =  -7,
    AlreadyRecording =  -8,
    NotRecording     =  -9,
    FileError        = -10,
};

}  // namespace roxstar

#endif  // ROXSTAR_STATUS_H
