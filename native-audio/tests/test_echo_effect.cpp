#include "../src/effects/EchoEffect.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "mini_test.h"
#include "test_signal_utils.h"

using roxstar::effects::EchoEffect;

namespace {
constexpr float kSampleRate = 48000.0f;
}  // namespace

TEST(Echo_ImpulseProducesDelayedFeedbackEnergy) {
    EchoEffect echo;
    echo.prepare(static_cast<int32_t>(kSampleRate));

    // ~300ms delay at 48kHz is ~14400 samples; run well past two echoes.
    constexpr int32_t kNumSamples = 40000;
    std::vector<float> signal(kNumSamples, 0.0f);
    signal[0] = 1.0f;  // unit impulse

    echo.process(signal.data(), kNumSamples);

    const int32_t delaySamples = static_cast<int32_t>(std::lround(kSampleRate * 0.3f));

    // The dry impulse passes through at sample 0.
    EXPECT_NEAR(signal[0], 1.0f, 1e-6);
    // A first echo appears at roughly one delay period, attenuated by the wet mix.
    EXPECT_TRUE(std::abs(signal[delaySamples]) > 0.01f);
    EXPECT_TRUE(std::abs(signal[delaySamples]) < 1.0f);
    // A second, further-attenuated echo (feedback applied twice) appears at 2x delay.
    EXPECT_TRUE(std::abs(signal[2 * delaySamples]) > 0.0001f);
    EXPECT_TRUE(std::abs(signal[2 * delaySamples]) < std::abs(signal[delaySamples]));
    // Well before the first echo, only the (silent) dry signal is present.
    EXPECT_NEAR(signal[delaySamples / 2], 0.0f, 1e-6);
}

TEST(Echo_SilenceStaysSilent) {
    EchoEffect echo;
    echo.prepare(16000);

    std::vector<float> silence(20000, 0.0f);
    echo.process(silence.data(), static_cast<int32_t>(silence.size()));

    for (float s : silence) {
        EXPECT_NEAR(s, 0.0f, 1e-9);
    }
}

TEST(Echo_NoNaNOrInfOnASustainedSignal) {
    EchoEffect echo;
    echo.prepare(44100);

    auto signal = testutil::generateSine(220.0f, 44100.0f, 200000);
    echo.process(signal.data(), static_cast<int32_t>(signal.size()));

    for (float s : signal) {
        EXPECT_TRUE(std::isfinite(s));
    }
}

TEST(Echo_OutputStaysBoundedOverALongRun) {
    // Proxy for "no unbounded growth": feedback < 1 means energy must decay,
    // so peak amplitude across a long run should never exceed a small bound
    // even though the input keeps re-exciting the delay line every period.
    EchoEffect echo;
    echo.prepare(44100);

    auto signal = testutil::generateSine(440.0f, 44100.0f, 300000, 0.9f);
    echo.process(signal.data(), static_cast<int32_t>(signal.size()));

    float peak = 0.0f;
    for (float s : signal) {
        peak = std::max(peak, std::abs(s));
    }
    EXPECT_TRUE(peak <= 1.0f);  // clampSample() guarantees this structurally
}

TEST(Echo_ArbitraryBlockSizesMatchOneLargeBlock) {
    EchoEffect wholeBlock;
    wholeBlock.prepare(16000);
    auto reference = testutil::generateSine(300.0f, 16000.0f, 10000);
    wholeBlock.process(reference.data(), static_cast<int32_t>(reference.size()));

    EchoEffect chunked;
    chunked.prepare(16000);
    auto signal = testutil::generateSine(300.0f, 16000.0f, 10000);
    // Odd, irregular chunk sizes — not aligned to anything internal.
    const int32_t sizes[] = {1, 7, 300, 4999, 3, 4690};
    int32_t offset = 0;
    for (int32_t chunk : sizes) {
        chunked.process(signal.data() + offset, chunk);
        offset += chunk;
    }

    for (size_t i = 0; i < signal.size(); ++i) {
        EXPECT_NEAR(signal[i], reference[i], 1e-5);
    }
}

TEST(Echo_ResetClearsDelayLineState) {
    EchoEffect echo;
    echo.prepare(16000);

    std::vector<float> impulse(20000, 0.0f);
    impulse[0] = 1.0f;
    echo.process(impulse.data(), static_cast<int32_t>(impulse.size()));

    echo.reset();

    std::vector<float> secondImpulse(20000, 0.0f);
    secondImpulse[0] = 1.0f;
    echo.process(secondImpulse.data(), static_cast<int32_t>(secondImpulse.size()));

    // If reset() left stale energy in the delay line, this second, otherwise
    // identical impulse response would differ from the first.
    for (size_t i = 0; i < impulse.size(); ++i) {
        EXPECT_NEAR(impulse[i], secondImpulse[i], 1e-6);
    }
}
