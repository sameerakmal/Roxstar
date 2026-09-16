#ifndef ROXSTAR_WAV_WRITER_H
#define ROXSTAR_WAV_WRITER_H

#include <array>
#include <cstdint>
#include <cstdio>
#include <string>

namespace roxstar {

/** Standard 44-byte canonical PCM RIFF/WAV header. */
using WavHeaderBytes = std::array<uint8_t, 44>;

/**
 * Builds a 44-byte RIFF/WAV header for 16-bit signed PCM.
 * Pure function — no I/O, fully unit-testable.
 */
WavHeaderBytes buildWavHeader(uint32_t sampleRate, uint16_t channelCount, uint32_t dataBytes);

/** Converts a float sample in roughly [-1, 1] to clipped 16-bit signed PCM. */
int16_t floatToInt16(float sample);

/**
 * Writes a mono 16-bit PCM WAV file incrementally.
 *
 * Intended for use only on the writer thread (never the audio callback) —
 * every method here does blocking file I/O. The header is written as a
 * placeholder on open() and patched with the real sizes on finalize(), so a
 * process death between those two points leaves a file whose header sizes
 * disagree with the actual file size — that mismatch is exactly what the
 * startup cleanup step looks for.
 */
class WavFileWriter {
public:
    WavFileWriter() = default;
    ~WavFileWriter();

    WavFileWriter(const WavFileWriter &) = delete;
    WavFileWriter &operator=(const WavFileWriter &) = delete;

    /** Creates the file and writes a placeholder header. */
    bool open(const std::string &path, uint32_t sampleRate, uint16_t channelCount);

    /** Converts float -> int16 and appends. Writer thread only. */
    bool appendFloatSamples(const float *samples, size_t count);

    /** Patches the RIFF/data sizes with the real totals and closes the file. */
    bool finalize();

    /** Closes (if open) and deletes the file — used on cancel/failure. */
    void abortAndDelete();

    bool isOpen() const { return mFile != nullptr; }
    uint64_t sampleCount() const { return mSampleCount; }
    const std::string &path() const { return mPath; }

private:
    std::FILE *mFile = nullptr;
    std::string mPath;
    uint32_t mSampleRate = 0;
    uint16_t mChannelCount = 1;
    uint64_t mSampleCount = 0;
};

}  // namespace roxstar

#endif  // ROXSTAR_WAV_WRITER_H
