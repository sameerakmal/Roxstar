#include "../src/WavReader.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../WavWriter.h"
#include "mini_test.h"

using roxstar::WavFileWriter;
using roxstar::WavReader;

namespace {

void putU32LE(std::vector<uint8_t> &b, size_t offset, uint32_t v) {
    b[offset] = static_cast<uint8_t>(v & 0xFF);
    b[offset + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    b[offset + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    b[offset + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

void putU16LE(std::vector<uint8_t> &b, size_t offset, uint16_t v) {
    b[offset] = static_cast<uint8_t>(v & 0xFF);
    b[offset + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

/** Builds a syntactically valid 44-byte-header WAV with the given fmt fields. */
std::vector<uint8_t> buildWav(uint16_t audioFormat, uint16_t channels, uint32_t sampleRate,
                              uint16_t bitsPerSample, const std::vector<uint8_t> &data) {
    std::vector<uint8_t> b(44 + data.size(), 0);
    std::memcpy(&b[0], "RIFF", 4);
    putU32LE(b, 4, static_cast<uint32_t>(36 + data.size()));
    std::memcpy(&b[8], "WAVE", 4);
    std::memcpy(&b[12], "fmt ", 4);
    putU32LE(b, 16, 16);
    putU16LE(b, 20, audioFormat);
    putU16LE(b, 22, channels);
    putU32LE(b, 24, sampleRate);
    putU32LE(b, 28, sampleRate * channels * (bitsPerSample / 8));
    putU16LE(b, 32, static_cast<uint16_t>(channels * (bitsPerSample / 8)));
    putU16LE(b, 34, bitsPerSample);
    std::memcpy(&b[36], "data", 4);
    putU32LE(b, 40, static_cast<uint32_t>(data.size()));
    std::memcpy(&b[44], data.data(), data.size());
    return b;
}

void writeFile(const std::string &path, const std::vector<uint8_t> &bytes) {
    std::FILE *f = std::fopen(path.c_str(), "wb");
    std::fwrite(bytes.data(), 1, bytes.size(), f);
    std::fclose(f);
}

std::vector<uint8_t> pcm16(const std::vector<int16_t> &samples) {
    std::vector<uint8_t> b(samples.size() * 2);
    for (size_t i = 0; i < samples.size(); ++i) {
        b[i * 2] = static_cast<uint8_t>(samples[i] & 0xFF);
        b[i * 2 + 1] = static_cast<uint8_t>((samples[i] >> 8) & 0xFF);
    }
    return b;
}

}  // namespace

TEST(WavReader_ParsesAFileWrittenByWavFileWriter) {
    const std::string path = "wav_reader_test_roundtrip.wav";
    std::remove(path.c_str());

    WavFileWriter writer;
    writer.open(path, 22050, 1);
    const float samples[5] = {0.0f, 0.25f, -0.25f, 0.5f, -1.0f};
    writer.appendFloatSamples(samples, 5);
    writer.finalize();

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_TRUE(reader.isLoaded());
    EXPECT_EQ(reader.sampleRate(), 22050);
    EXPECT_EQ(reader.channelCount(), 1);
    EXPECT_EQ(reader.frameCount(), static_cast<int64_t>(5));

    // Round-tripped through int16, so allow the quantization step as tolerance.
    for (int i = 0; i < 5; ++i) {
        EXPECT_NEAR(reader.samples()[i], samples[i], 1.0 / 32768.0 + 1e-6);
    }

    std::remove(path.c_str());
}

TEST(WavReader_RejectsMissingFile) {
    WavReader reader;
    EXPECT_FALSE(reader.load("wav_reader_test_does_not_exist.wav"));
    EXPECT_FALSE(reader.isLoaded());
}

TEST(WavReader_RejectsBadRiffHeader) {
    const std::string path = "wav_reader_test_bad_riff.wav";
    auto bytes = buildWav(1, 1, 16000, 16, pcm16({1, 2, 3}));
    std::memcpy(&bytes[0], "JUNK", 4);  // not "RIFF"
    writeFile(path, bytes);

    WavReader reader;
    EXPECT_FALSE(reader.load(path));

    std::remove(path.c_str());
}

TEST(WavReader_RejectsBadWaveTag) {
    const std::string path = "wav_reader_test_bad_wave.wav";
    auto bytes = buildWav(1, 1, 16000, 16, pcm16({1, 2, 3}));
    std::memcpy(&bytes[8], "JUNK", 4);  // not "WAVE"
    writeFile(path, bytes);

    WavReader reader;
    EXPECT_FALSE(reader.load(path));

    std::remove(path.c_str());
}

TEST(WavReader_RejectsNonPcmFormat) {
    const std::string path = "wav_reader_test_non_pcm.wav";
    writeFile(path, buildWav(3, 1, 16000, 16, pcm16({1, 2, 3})));  // 3 = IEEE float, not PCM

    WavReader reader;
    EXPECT_FALSE(reader.load(path));

    std::remove(path.c_str());
}

TEST(WavReader_RejectsNonMono) {
    const std::string path = "wav_reader_test_stereo.wav";
    writeFile(path, buildWav(1, 2, 16000, 16, pcm16({1, 2, 3, 4})));  // stereo

    WavReader reader;
    EXPECT_FALSE(reader.load(path));

    std::remove(path.c_str());
}

TEST(WavReader_RejectsNon16Bit) {
    const std::string path = "wav_reader_test_8bit.wav";
    std::vector<uint8_t> data8 = {10, 20, 30};
    writeFile(path, buildWav(1, 1, 16000, 8, data8));

    WavReader reader;
    EXPECT_FALSE(reader.load(path));

    std::remove(path.c_str());
}

TEST(WavReader_ExtractsCorrectSampleRate) {
    const std::string path = "wav_reader_test_rate.wav";
    writeFile(path, buildWav(1, 1, 48000, 16, pcm16({0, 0})));

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_EQ(reader.sampleRate(), 48000);

    std::remove(path.c_str());
}

TEST(WavReader_DecodesPcm16CorrectlyIncludingExtremes) {
    const std::string path = "wav_reader_test_decode.wav";
    writeFile(path, buildWav(1, 1, 16000, 16, pcm16({0, 16384, -16384, 32767, -32768})));

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_EQ(reader.frameCount(), static_cast<int64_t>(5));
    EXPECT_NEAR(reader.samples()[0], 0.0, 1e-6);
    EXPECT_NEAR(reader.samples()[1], 0.5, 1e-4);
    EXPECT_NEAR(reader.samples()[2], -0.5, 1e-4);
    EXPECT_NEAR(reader.samples()[3], 32767.0 / 32768.0, 1e-4);
    EXPECT_NEAR(reader.samples()[4], -1.0, 1e-4);

    std::remove(path.c_str());
}

TEST(WavReader_EmptyDataChunkLoadsAsZeroFrames) {
    const std::string path = "wav_reader_test_empty.wav";
    writeFile(path, buildWav(1, 1, 16000, 16, {}));

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_TRUE(reader.isLoaded());
    EXPECT_EQ(reader.frameCount(), static_cast<int64_t>(0));

    std::remove(path.c_str());
}

TEST(WavReader_TruncatedDataChunkIsClampedNotRejected) {
    // Simulates reading a file mid-write: the data chunk's declared size
    // extends past the actual end of the file. This should be treated
    // leniently (use what's actually there) rather than rejected outright.
    const std::string path = "wav_reader_test_truncated.wav";
    auto bytes = buildWav(1, 1, 16000, 16, pcm16({1, 2, 3, 4}));
    putU32LE(bytes, 40, 1000);  // claim far more data than is actually present
    writeFile(path, bytes);

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_EQ(reader.frameCount(), static_cast<int64_t>(4));  // clamped to what's really there

    std::remove(path.c_str());
}

TEST(WavReader_SkipsUnknownChunksBetweenFmtAndData) {
    // A LIST/INFO chunk (or any other) between "fmt " and "data" must not
    // break parsing — chunks are walked by ID, not assumed at fixed offsets.
    auto wav = buildWav(1, 1, 16000, 16, pcm16({7, 8, 9}));
    std::vector<uint8_t> extra = {'L', 'I', 'S', 'T', 4, 0, 0, 0, 'a', 'b', 'c', 'd'};
    std::vector<uint8_t> withExtra(wav.begin(), wav.begin() + 36);
    withExtra.insert(withExtra.end(), extra.begin(), extra.end());
    withExtra.insert(withExtra.end(), wav.begin() + 36, wav.end());
    putU32LE(withExtra, 4, static_cast<uint32_t>(withExtra.size() - 8));  // fix RIFF size

    const std::string path = "wav_reader_test_extra_chunk.wav";
    writeFile(path, withExtra);

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_EQ(reader.frameCount(), static_cast<int64_t>(3));

    std::remove(path.c_str());
}

TEST(WavReader_ResetClearsLoadedState) {
    const std::string path = "wav_reader_test_reset.wav";
    writeFile(path, buildWav(1, 1, 16000, 16, pcm16({1, 2, 3})));

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_TRUE(reader.isLoaded());

    reader.reset();
    EXPECT_FALSE(reader.isLoaded());
    EXPECT_EQ(reader.frameCount(), static_cast<int64_t>(0));

    std::remove(path.c_str());
}

TEST(WavReader_LoadingAgainAfterAFailureLeavesReaderEmpty) {
    const std::string path = "wav_reader_test_reload.wav";
    writeFile(path, buildWav(1, 1, 16000, 16, pcm16({1, 2})));

    WavReader reader;
    EXPECT_TRUE(reader.load(path));
    EXPECT_TRUE(reader.load("wav_reader_test_does_not_exist.wav") == false);
    EXPECT_FALSE(reader.isLoaded());  // failed load must not leave stale data "loaded"

    std::remove(path.c_str());
}
