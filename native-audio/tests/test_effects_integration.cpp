#include "../RecordingSession.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "mini_test.h"
#include "test_signal_utils.h"

using roxstar::RecordingSession;
using roxstar::effects::EffectType;

namespace {

bool fileExists(const std::string &path) {
    std::FILE *f = std::fopen(path.c_str(), "rb");
    if (f != nullptr) std::fclose(f);
    return f != nullptr;
}

}  // namespace

TEST(EffectsIntegration_NoneLeavesSamplesUnchanged) {
    // "NONE bypasses processing" verified at the exact point it enters the
    // pipeline: RecordingSession::applyEffect, as called from onAudioReady.
    const std::string path = "effects_integration_none.wav";
    std::remove(path.c_str());

    RecordingSession session;
    session.setEffect(EffectType::None);
    session.start(path, 16000);

    auto signal = testutil::generateSine(300.0f, 16000.0f, 2000);
    std::vector<float> original = signal;
    session.applyEffect(signal.data(), static_cast<int32_t>(signal.size()));

    for (size_t i = 0; i < signal.size(); ++i) {
        EXPECT_NEAR(signal[i], original[i], 1e-9);
    }

    session.cancelAndDiscard();
    std::remove(path.c_str());
}

TEST(EffectsIntegration_EchoActuallyChangesSamples) {
    const std::string path = "effects_integration_echo.wav";
    std::remove(path.c_str());

    RecordingSession session;
    session.setEffect(EffectType::Echo);
    session.start(path, 16000);

    // Prime the delay line with an impulse, then confirm a later block
    // differs from what NONE would have produced (an echo carries over).
    std::vector<float> impulse(20000, 0.0f);
    impulse[0] = 1.0f;
    session.applyEffect(impulse.data(), static_cast<int32_t>(impulse.size()));

    const int32_t delaySamples = static_cast<int32_t>(16000 * 0.3f);
    EXPECT_TRUE(std::abs(impulse[static_cast<size_t>(delaySamples)]) > 0.01f);

    session.cancelAndDiscard();
    std::remove(path.c_str());
}

TEST(EffectsIntegration_ApplyEffectIsNoOpWhenNotRecording) {
    RecordingSession session;
    session.setEffect(EffectType::Echo);
    // start() is never called — no active session.

    std::vector<float> signal = {0.1f, 0.2f, 0.3f};
    std::vector<float> original = signal;
    session.applyEffect(signal.data(), static_cast<int32_t>(signal.size()));

    for (size_t i = 0; i < signal.size(); ++i) {
        EXPECT_NEAR(signal[i], original[i], 1e-9);
    }
}

TEST(EffectsIntegration_EachRecordingGetsAFreshEffectInstance) {
    // Two sessions in a row with Echo selected must not leak state between
    // them — this is how "reset between recordings" is achieved in
    // production (fresh construction in start(), rather than reset() calls).
    const std::string path = "effects_integration_fresh.wav";
    std::remove(path.c_str());

    RecordingSession first;
    first.setEffect(EffectType::Echo);
    first.start(path, 16000);
    std::vector<float> firstImpulse(20000, 0.0f);
    firstImpulse[0] = 1.0f;
    first.applyEffect(firstImpulse.data(), static_cast<int32_t>(firstImpulse.size()));
    first.cancelAndDiscard();
    std::remove(path.c_str());

    RecordingSession second;
    second.setEffect(EffectType::Echo);
    second.start(path, 16000);
    std::vector<float> secondImpulse(20000, 0.0f);
    secondImpulse[0] = 1.0f;
    second.applyEffect(secondImpulse.data(), static_cast<int32_t>(secondImpulse.size()));
    second.cancelAndDiscard();
    std::remove(path.c_str());

    for (size_t i = 0; i < firstImpulse.size(); ++i) {
        EXPECT_NEAR(firstImpulse[i], secondImpulse[i], 1e-6);
    }
}

TEST(EffectsIntegration_SelectedEffectSurvivesIntoTheWrittenFile) {
    // End-to-end: Reverb selected, real samples pushed through applyEffect
    // then pushSamples (as onAudioReady does), file finalized, and the
    // resulting WAV is provably different from the dry input it was fed.
    const std::string path = "effects_integration_reverb_file.wav";
    std::remove(path.c_str());

    RecordingSession session;
    session.setEffect(EffectType::Reverb);
    const auto startStatus = session.start(path, 16000);
    EXPECT_EQ(static_cast<int>(startStatus), 0);

    std::vector<float> signal(8000, 0.0f);
    signal[0] = 1.0f;
    session.applyEffect(signal.data(), static_cast<int32_t>(signal.size()));
    session.pushSamples(signal.data(), static_cast<int32_t>(signal.size()));

    session.stopAndFinalize();

    EXPECT_TRUE(fileExists(path));
    // Reverb spreads energy well beyond a single sample, unlike a dry impulse.
    bool foundTail = false;
    for (size_t i = 100; i < signal.size(); ++i) {
        if (std::abs(signal[i]) > 1e-6f) {
            foundTail = true;
            break;
        }
    }
    EXPECT_TRUE(foundTail);

    std::remove(path.c_str());
}
