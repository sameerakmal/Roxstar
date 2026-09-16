#include "mini_test.h"

// Actual TEST() cases live in test_ring_buffer.cpp, test_wav_writer.cpp and
// test_recording_session.cpp; they self-register via static initializers.
int main() {
    return minitest::runAll();
}
