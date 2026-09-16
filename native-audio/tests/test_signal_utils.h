#ifndef ROXSTAR_TEST_SIGNAL_UTILS_H
#define ROXSTAR_TEST_SIGNAL_UTILS_H

#include <cstdint>
#include <cmath>
#include <vector>

// Small deterministic-signal helpers shared by the effect tests. Test-only —
// not part of the production build.
namespace testutil {

inline std::vector<float> generateSine(float frequencyHz, float sampleRate, int32_t numSamples,
                                        float amplitude = 0.8f) {
    std::vector<float> out(static_cast<size_t>(numSamples));
    constexpr float kTwoPi = 6.283185307f;
    for (int32_t i = 0; i < numSamples; ++i) {
        out[static_cast<size_t>(i)] =
            amplitude * std::sin(kTwoPi * frequencyHz * static_cast<float>(i) / sampleRate);
    }
    return out;
}

/**
 * Autocorrelation-based fundamental frequency estimate for a roughly
 * periodic signal: searches lags in [minLag, maxLag] (samples) and returns
 * sampleRate / (lag with peak correlation). Robust to the amplitude/phase
 * changes a granular pitch shifter introduces, which a naive zero-crossing
 * count is not.
 */
inline float estimateFrequency(const float *samples, int32_t numSamples, float sampleRate,
                                int32_t minLag, int32_t maxLag) {
    double bestCorrelation = -1.0;
    int32_t bestLag = minLag;
    for (int32_t lag = minLag; lag <= maxLag; ++lag) {
        const int32_t count = numSamples - lag;
        if (count <= 0) break;
        double sum = 0.0;
        for (int32_t i = 0; i < count; ++i) {
            sum += static_cast<double>(samples[i]) * static_cast<double>(samples[i + lag]);
        }
        sum /= count;
        if (sum > bestCorrelation) {
            bestCorrelation = sum;
            bestLag = lag;
        }
    }
    return sampleRate / static_cast<float>(bestLag);
}

}  // namespace testutil

#endif  // ROXSTAR_TEST_SIGNAL_UTILS_H
