#ifndef ROXSTAR_PLAYBACK_BUFFER_H
#define ROXSTAR_PLAYBACK_BUFFER_H

#include <cstdint>

namespace roxstar {

/**
 * Copies up to `numFrames` frames starting at `*position` from `source`
 * (mono, `totalFrames` long) into `dst`, expanding the mono source to
 * `channels` interleaved output channels, and fills any remainder with
 * silence. `*position` is advanced by the number of real frames copied, and
 * rewound to 0 if playback reaches the end of `source` within this call.
 *
 * Pure and real-time safe — no allocation, no I/O, no Oboe/JNI dependency.
 * This is the actual per-callback logic PlaybackSession::onAudioReady runs;
 * it's a free function (not a stateful class) so the position lives in
 * PlaybackSession's atomic and this stays trivially host-testable.
 *
 * Returns the number of real (non-silence) frames written. Less than
 * numFrames means the end of `source` was reached during this call.
 */
int32_t fillPlaybackBuffer(float *dst, int32_t numFrames, int32_t channels,
                           const float *source, int64_t totalFrames, int64_t *position);

}  // namespace roxstar

#endif  // ROXSTAR_PLAYBACK_BUFFER_H
