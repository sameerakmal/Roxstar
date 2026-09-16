#include "../WavWriter.h"

#include <cstdio>
#include <string>
#include <vector>

#include "mini_test.h"

using roxstar::buildWavHeader;
using roxstar::floatToInt16;
using roxstar::WavFileWriter;

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

uint16_t readU16LE(const uint8_t *p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

int16_t readI16LE(const uint8_t *p) {
    return static_cast<int16_t>(readU16LE(p));
}

}  // namespace

TEST(WavHeader_Is44BytesWithCorrectFixedFields) {
    const auto h = buildWavHeader(16000, 1, 2000);
    EXPECT_EQ(h.size(), static_cast<size_t>(44));

    EXPECT_EQ(std::string(reinterpret_cast<const char *>(&h[0]), 4), "RIFF");
    EXPECT_EQ(std::string(reinterpret_cast<const char *>(&h[8]), 4), "WAVE");
    EXPECT_EQ(std::string(reinterpret_cast<const char *>(&h[12]), 4), "fmt ");
    EXPECT_EQ(std::string(reinterpret_cast<const char *>(&h[36]), 4), "data");

    EXPECT_EQ(readU32LE(&h[16]), static_cast<uint32_t>(16));  // fmt chunk size (PCM)
    EXPECT_EQ(readU16LE(&h[20]), static_cast<uint16_t>(1));   // PCM format tag
    EXPECT_EQ(readU16LE(&h[22]), static_cast<uint16_t>(1));   // mono
    EXPECT_EQ(readU32LE(&h[24]), static_cast<uint32_t>(16000));  // sample rate
    EXPECT_EQ(readU16LE(&h[34]), static_cast<uint16_t>(16));  // bits per sample
}

TEST(WavHeader_DerivedFieldsMatchDataSize) {
    const uint32_t sampleRate = 48000;
    const uint16_t channels = 1;
    const uint32_t dataBytes = 9600;  // 4800 int16 samples
    const auto h = buildWavHeader(sampleRate, channels, dataBytes);

    const uint16_t blockAlign = readU16LE(&h[32]);
    const uint32_t byteRate = readU32LE(&h[28]);
    EXPECT_EQ(blockAlign, static_cast<uint16_t>(channels * 2));
    EXPECT_EQ(byteRate, sampleRate * blockAlign);
    EXPECT_EQ(readU32LE(&h[40]), dataBytes);           // data chunk size
    EXPECT_EQ(readU32LE(&h[4]), dataBytes + 36);        // RIFF chunk size
}

TEST(FloatToInt16_MidRangeValuesScaleLinearly) {
    EXPECT_EQ(floatToInt16(0.0f), static_cast<int16_t>(0));
    EXPECT_EQ(floatToInt16(0.5f), static_cast<int16_t>(16384));
    EXPECT_EQ(floatToInt16(-0.5f), static_cast<int16_t>(-16384));
}

TEST(FloatToInt16_ClipsAboveAndBelowFullScale) {
    EXPECT_EQ(floatToInt16(1.0f), static_cast<int16_t>(32767));    // clipped from 32768
    EXPECT_EQ(floatToInt16(2.0f), static_cast<int16_t>(32767));    // far over scale
    EXPECT_EQ(floatToInt16(-1.0f), static_cast<int16_t>(-32768));
    EXPECT_EQ(floatToInt16(-5.0f), static_cast<int16_t>(-32768));  // far under scale
}

TEST(WavFileWriter_FinalizedFileHasCorrectHeaderAndSamples) {
    const std::string path = "wav_writer_test_finalized.wav";
    std::remove(path.c_str());

    WavFileWriter writer;
    EXPECT_TRUE(writer.open(path, 16000, 1));

    const float samples[4] = {0.0f, 0.5f, -0.5f, 1.0f};  // last one clips
    EXPECT_TRUE(writer.appendFloatSamples(samples, 4));
    EXPECT_TRUE(writer.finalize());
    EXPECT_FALSE(writer.isOpen());

    const auto bytes = readWholeFile(path);
    EXPECT_EQ(bytes.size(), static_cast<size_t>(44 + 4 * 2));

    if (bytes.size() >= 44 + 4 * 2) {
        // Header must have been patched with the real sizes, not the placeholder.
        EXPECT_EQ(readU32LE(&bytes[40]), static_cast<uint32_t>(8));   // data bytes
        EXPECT_EQ(readU32LE(&bytes[4]), static_cast<uint32_t>(44));   // RIFF size = 36 + 8
        EXPECT_EQ(bytes.size(), readU32LE(&bytes[4]) + 8);            // self-consistent with file size

        EXPECT_EQ(readI16LE(&bytes[44]), static_cast<int16_t>(0));
        EXPECT_EQ(readI16LE(&bytes[46]), static_cast<int16_t>(16384));
        EXPECT_EQ(readI16LE(&bytes[48]), static_cast<int16_t>(-16384));
        EXPECT_EQ(readI16LE(&bytes[50]), static_cast<int16_t>(32767));  // clipped
    }

    std::remove(path.c_str());
}

TEST(WavFileWriter_CrashBeforeFinalizeLeavesDetectableMismatch) {
    // Simulates a process death between open() and finalize(): the header's
    // declared size (0, the placeholder) disagrees with the real file size.
    // This exact mismatch is what the Kotlin-side startup cleanup looks for.
    const std::string path = "wav_writer_test_crashed.wav";
    std::remove(path.c_str());

    {
        WavFileWriter writer;
        EXPECT_TRUE(writer.open(path, 16000, 1));
        const float samples[3] = {0.1f, 0.2f, 0.3f};
        EXPECT_TRUE(writer.appendFloatSamples(samples, 3));
        // No finalize() call — going out of scope here only closes the FILE*
        // (flushing it to disk), it does not patch the header. That is the
        // process-death case: header still says 0 bytes, real data on disk.
    }

    const auto bytes = readWholeFile(path);
    EXPECT_EQ(bytes.size(), static_cast<size_t>(44 + 3 * 2));
    if (bytes.size() >= 44) {
        EXPECT_EQ(readU32LE(&bytes[40]), static_cast<uint32_t>(0));  // still the placeholder
        EXPECT_TRUE(readU32LE(&bytes[40]) + 44 != bytes.size());     // mismatch is detectable
    }

    std::remove(path.c_str());
}

TEST(WavFileWriter_AbortAndDeleteRemovesIncompleteFile) {
    const std::string path = "wav_writer_test_aborted.wav";
    std::remove(path.c_str());

    WavFileWriter writer;
    EXPECT_TRUE(writer.open(path, 16000, 1));
    const float samples[2] = {0.1f, -0.1f};
    writer.appendFloatSamples(samples, 2);
    writer.abortAndDelete();

    EXPECT_FALSE(writer.isOpen());
    std::FILE *shouldBeGone = std::fopen(path.c_str(), "rb");
    EXPECT_TRUE(shouldBeGone == nullptr);
    if (shouldBeGone != nullptr) std::fclose(shouldBeGone);
}

TEST(WavFileWriter_OpenTwiceFails) {
    const std::string path = "wav_writer_test_reopen.wav";
    std::remove(path.c_str());

    WavFileWriter writer;
    EXPECT_TRUE(writer.open(path, 16000, 1));
    EXPECT_FALSE(writer.open(path, 16000, 1));
    writer.abortAndDelete();
}
