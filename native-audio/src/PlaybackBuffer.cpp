#include "PlaybackBuffer.h"

namespace roxstar {

int32_t fillPlaybackBuffer(float *dst, int32_t numFrames, int32_t channels,
                           const float *source, int64_t totalFrames, int64_t *position) {
    int64_t pos = *position;
    if (pos < 0) pos = 0;

    int32_t realFrames = 0;
    while (realFrames < numFrames && pos < totalFrames) {
        const float sample = source[pos];
        const int32_t base = realFrames * channels;
        for (int32_t c = 0; c < channels; ++c) {
            dst[base + c] = sample;
        }
        ++realFrames;
        ++pos;
    }

    for (int32_t f = realFrames; f < numFrames; ++f) {
        const int32_t base = f * channels;
        for (int32_t c = 0; c < channels; ++c) {
            dst[base + c] = 0.0f;
        }
    }

    *position = pos;
    return realFrames;
}

}  // namespace roxstar
