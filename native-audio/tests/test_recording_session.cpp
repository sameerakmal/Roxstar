#include "../RecordingSession.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include "mini_test.h"

using roxstar::RecordingSession;
using roxstar::RecordingState;
using roxstar::Status;

namespace {

std::vector<uint8_t> readWholeFile(const std::string &path) {
    std::FILE *f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return {};
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> data(static_cast<size_t>(size));
    std::fread(data.data(), 1, data.size(), f);
    std::fclose(f);
    return data;
}

uint32_t readU32LE(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

bool fileExists(const std::string &path) {
    std::FILE *f = std::fopen(path.c_str(), "rb");
    if (f != nullptr) std::fclose(f);
    return f != nullptr;
}

/** Pushes `count` synthetic frames in kChunk-sized bursts, as the audio
 *  callback would, with a short sleep between bursts so the writer thread
 *  gets scheduled — this is an integration test of the real threaded
 *  pipeline, not a single-threaded simulation. */
void feedFrames(RecordingSession &session, int totalFrames) {
    constexpr int kChunk = 256;
    int done = 0;
    while (done < totalFrames) {
        const int n = std::min(kChunk, totalFrames - done);
        float buf[kChunk];
        for (int i = 0; i < n; ++i) {
            buf[i] = 0.25f;  // fixed value -> exact expected int16 after conversion
        }
        session.pushSamples(buf, n);
        done += n;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

}  // namespace

TEST(RecordingSession_StartTwiceReturnsAlreadyRecording) {
    const std::string path = "recording_session_test_twice.wav";
    std::remove(path.c_str());

    RecordingSession session;
    EXPECT_EQ(static_cast<int>(session.start(path, 16000)), static_cast<int>(Status::Ok));
    EXPECT_EQ(static_cast<int>(session.state()), static_cast<int>(RecordingState::Recording));

    EXPECT_EQ(static_cast<int>(session.start(path, 16000)),
              static_cast<int>(Status::AlreadyRecording));

    EXPECT_EQ(static_cast<int>(session.stopAndFinalize()), static_cast<int>(Status::Ok));
    EXPECT_EQ(static_cast<int>(session.state()), static_cast<int>(RecordingState::Idle));
    std::remove(path.c_str());
}

TEST(RecordingSession_StopWithoutStartReturnsNotRecording) {
    RecordingSession session;
    EXPECT_EQ(static_cast<int>(session.stopAndFinalize()), static_cast<int>(Status::NotRecording));
}

TEST(RecordingSession_StartFailsOnUnwritablePathAndLeavesNoPartialFile) {
    // A path inside a directory that does not exist cannot be opened.
    const std::string path = "no_such_directory_xyz/recording.wav";
    RecordingSession session;
    const Status result = session.start(path, 16000);
    EXPECT_EQ(static_cast<int>(result), static_cast<int>(Status::FileError));
    EXPECT_FALSE(session.isRecording());
    EXPECT_FALSE(fileExists(path));
}

TEST(RecordingSession_EndToEndProducesValidWavWithExpectedSampleCount) {
    const std::string path = "recording_session_test_e2e.wav";
    std::remove(path.c_str());

    RecordingSession session;
    const int sampleRate = 16000;
    const Status startStatus = session.start(path, sampleRate);
    EXPECT_EQ(static_cast<int>(startStatus), static_cast<int>(Status::Ok));

    constexpr int kTotalFrames = 4000;  // 250 ms at 16 kHz — several writer-thread cycles
    feedFrames(session, kTotalFrames);

    EXPECT_EQ(static_cast<int>(session.stopAndFinalize()), static_cast<int>(Status::Ok));

    const auto bytes = readWholeFile(path);
    EXPECT_TRUE(bytes.size() >= 44);
    if (bytes.size() >= 44) {
        const uint32_t dataBytes = readU32LE(&bytes[40]);
        const uint32_t riffSize = readU32LE(&bytes[4]);

        // Every frame handed to pushSamples() must have survived: the ring
        // buffer was sized for 2 s of headroom (far more than 250 ms) so
        // there should be no overrun, and stopAndFinalize() blocks until the
        // writer has drained.
        EXPECT_EQ(dataBytes, static_cast<uint32_t>(kTotalFrames * 2));  // 16-bit mono
        EXPECT_EQ(riffSize, dataBytes + 36);
        EXPECT_EQ(bytes.size(), static_cast<size_t>(44 + kTotalFrames * 2));
    }
    EXPECT_EQ(session.framesCaptured(), static_cast<int64_t>(kTotalFrames));
    EXPECT_EQ(session.overrunFrames(), 0);

    std::remove(path.c_str());
}

TEST(RecordingSession_CancelDiscardsTheFile) {
    const std::string path = "recording_session_test_cancel.wav";
    std::remove(path.c_str());

    RecordingSession session;
    session.start(path, 16000);
    feedFrames(session, 800);
    EXPECT_TRUE(fileExists(path));

    session.cancelAndDiscard();
    EXPECT_FALSE(session.isRecording());
    EXPECT_FALSE(fileExists(path));
}

TEST(RecordingSession_PushBeforeStartIsANoOp) {
    RecordingSession session;
    float buf[16] = {};
    session.pushSamples(buf, 16);  // must not crash with no ring buffer allocated
    EXPECT_EQ(session.framesCaptured(), static_cast<int64_t>(0));
}

TEST(RecordingSession_DestructorWithoutStopCleansUpAndDoesNotLeaveAFile) {
    const std::string path = "recording_session_test_destructor.wav";
    std::remove(path.c_str());
    {
        RecordingSession session;
        session.start(path, 16000);
        feedFrames(session, 500);
        // No stopAndFinalize()/cancelAndDiscard() call — the destructor is
        // the safety net a forcibly-closed AudioEngine would rely on.
    }
    EXPECT_FALSE(fileExists(path));
}
