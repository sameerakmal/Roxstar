#ifndef ROXSTAR_EFFECTS_IEFFECT_H
#define ROXSTAR_EFFECTS_IEFFECT_H

#include <cstdint>
#include <memory>

namespace roxstar::effects {

// Mirrored by Effect in AudioEngine.kt — keep in sync.
enum class EffectType : int32_t {
    None       = 0,
    Echo       = 1,
    Reverb     = 2,
    PitchShift = 3,
};

/** True if `value` is a defined EffectType. Used to validate raw values crossing JNI. */
bool isValidEffectType(int32_t value);

/**
 * A mono, real-time audio effect applied to captured samples before they
 * reach the recording ring buffer (see RecordingSession::applyEffect).
 *
 * Lifecycle:
 *  - prepare() is called once, on the JNI thread, before recording starts.
 *    It sizes every buffer the effect will ever need; process() never
 *    allocates.
 *  - process() runs on the real-time audio callback thread, once per
 *    callback, operating in place on the mono float scratch buffer. No
 *    allocation, locking, logging, file I/O or JNI calls.
 *  - reset() clears state back to silence without freeing buffers. Not
 *    currently called by production code — each recording gets a freshly
 *    constructed effect instead (see RecordingSession::start) — but every
 *    effect implements it and it is covered by the native tests, since
 *    "resettable between recordings" is a property of the effect itself.
 */
class IEffect {
public:
    virtual ~IEffect() = default;

    virtual void prepare(int32_t sampleRate) = 0;
    virtual void process(float *samples, int32_t numFrames) = 0;
    virtual void reset() = 0;
};

/**
 * Builds the effect for `type`, or nullptr for None (and for anything else
 * isValidEffectType() rejects). Callers skip calling process() entirely when
 * this returns nullptr, rather than dispatching through a no-op
 * implementation — that's the "minimal overhead" bypass for NONE.
 */
std::unique_ptr<IEffect> createEffect(EffectType type);

/** Every effect clamps its output through this before returning. */
inline float clampSample(float value) {
    if (value > 1.0f) return 1.0f;
    if (value < -1.0f) return -1.0f;
    return value;
}

}  // namespace roxstar::effects

#endif  // ROXSTAR_EFFECTS_IEFFECT_H
