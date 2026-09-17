#include "../src/PlaybackBuffer.h"

#include <vector>

#include "mini_test.h"

using roxstar::fillPlaybackBuffer;

TEST(PlaybackBuffer_CopiesMonoSourceToMonoOutput) {
    const float source[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    float dst[4] = {};
    int64_t pos = 0;

    const int32_t real = fillPlaybackBuffer(dst, 4, 1, source, 4, &pos);

    EXPECT_EQ(real, 4);
    EXPECT_EQ(pos, static_cast<int64_t>(4));
    for (int i = 0; i < 4; ++i) {
        EXPECT_NEAR(dst[i], source[i], 1e-9);
    }
}

TEST(PlaybackBuffer_ExpandsMonoSourceToEveryOutputChannel) {
    const float source[2] = {0.5f, -0.5f};
    float dst[6] = {};  // 2 frames x 3 channels
    int64_t pos = 0;

    const int32_t real = fillPlaybackBuffer(dst, 2, 3, source, 2, &pos);

    EXPECT_EQ(real, 2);
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(dst[0 * 3 + c], 0.5f, 1e-9);
    for (int c = 0; c < 3; ++c) EXPECT_NEAR(dst[1 * 3 + c], -0.5f, 1e-9);
}

TEST(PlaybackBuffer_FillsSilenceAndReportsShortfallAtEndOfSource) {
    const float source[2] = {0.9f, -0.9f};
    float dst[5] = {1, 1, 1, 1, 1};  // pre-filled with a sentinel, not silence
    int64_t pos = 0;

    const int32_t real = fillPlaybackBuffer(dst, 5, 1, source, 2, &pos);

    EXPECT_EQ(real, 2);              // only 2 real frames existed
    EXPECT_EQ(pos, static_cast<int64_t>(2));
    EXPECT_NEAR(dst[0], 0.9f, 1e-9);
    EXPECT_NEAR(dst[1], -0.9f, 1e-9);
    EXPECT_NEAR(dst[2], 0.0f, 1e-9);  // silence, not the sentinel
    EXPECT_NEAR(dst[3], 0.0f, 1e-9);
    EXPECT_NEAR(dst[4], 0.0f, 1e-9);
}

TEST(PlaybackBuffer_AlreadyAtEndReturnsAllSilenceAndDoesNotMovePosition) {
    const float source[2] = {0.1f, 0.2f};
    float dst[3] = {9, 9, 9};
    int64_t pos = 2;  // already at the end

    const int32_t real = fillPlaybackBuffer(dst, 3, 1, source, 2, &pos);

    EXPECT_EQ(real, 0);
    EXPECT_EQ(pos, static_cast<int64_t>(2));
    for (int i = 0; i < 3; ++i) EXPECT_NEAR(dst[i], 0.0f, 1e-9);
}

TEST(PlaybackBuffer_EmptySourcePlaysNothing) {
    float dst[3] = {9, 9, 9};
    int64_t pos = 0;

    const int32_t real = fillPlaybackBuffer(dst, 3, 1, nullptr, 0, &pos);

    EXPECT_EQ(real, 0);
    EXPECT_EQ(pos, static_cast<int64_t>(0));
    for (int i = 0; i < 3; ++i) EXPECT_NEAR(dst[i], 0.0f, 1e-9);
}

TEST(PlaybackBuffer_SequentialCallsContinueFromWherePreviousLeftOff) {
    // Simulates several audio callbacks in a row during one playback, as
    // PlaybackSession::onAudioReady would drive it.
    const float source[6] = {0, 1, 2, 3, 4, 5};
    int64_t pos = 0;
    std::vector<float> played;

    for (int call = 0; call < 3; ++call) {
        float dst[2] = {};
        const int32_t real = fillPlaybackBuffer(dst, 2, 1, source, 6, &pos);
        EXPECT_EQ(real, 2);
        played.push_back(dst[0]);
        played.push_back(dst[1]);
    }

    EXPECT_EQ(pos, static_cast<int64_t>(6));
    for (int i = 0; i < 6; ++i) {
        EXPECT_NEAR(played[static_cast<size_t>(i)], source[i], 1e-9);
    }
}

TEST(PlaybackBuffer_RepeatedPlayCyclesProduceIdenticalOutputEveryTime) {
    // "Multiple start/stop cycles without state corruption": fillPlaybackBuffer
    // holds no state of its own beyond the caller-owned position, so resetting
    // position to 0 between cycles (exactly what PlaybackSession::stop() does)
    // must replay identically every time, with no drift or leftover state.
    const float source[4] = {0.25f, -0.25f, 0.5f, -0.5f};

    std::vector<float> firstCycle;
    for (int cycle = 0; cycle < 20; ++cycle) {
        int64_t pos = 0;
        float dst[4] = {};
        const int32_t real = fillPlaybackBuffer(dst, 4, 1, source, 4, &pos);
        EXPECT_EQ(real, 4);
        EXPECT_EQ(pos, static_cast<int64_t>(4));

        std::vector<float> thisCycle(dst, dst + 4);
        if (cycle == 0) {
            firstCycle = thisCycle;
        } else {
            for (int i = 0; i < 4; ++i) {
                EXPECT_NEAR(thisCycle[static_cast<size_t>(i)], firstCycle[static_cast<size_t>(i)], 1e-9);
            }
        }
    }
}

TEST(PlaybackBuffer_NegativePositionIsTreatedAsZero) {
    const float source[2] = {0.7f, 0.8f};
    float dst[2] = {};
    int64_t pos = -5;

    const int32_t real = fillPlaybackBuffer(dst, 2, 1, source, 2, &pos);

    EXPECT_EQ(real, 2);
    EXPECT_NEAR(dst[0], 0.7f, 1e-9);
}
