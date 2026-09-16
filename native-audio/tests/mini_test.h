#ifndef ROXSTAR_MINI_TEST_H
#define ROXSTAR_MINI_TEST_H

// Deliberately tiny, dependency-free test harness. The logic under test
// (RingBuffer, WavWriter, RecordingSession) is plain, portable C++17 with no
// Android/Oboe/JNI dependency, so it needs nothing more than this to run on
// the host — no NDK, no device, no fetched test framework.

#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <string>
#include <vector>

namespace minitest {

struct Test {
    const char *name;
    void (*fn)();
};

inline std::vector<Test> &registry() {
    static std::vector<Test> tests;
    return tests;
}

struct Registrar {
    Registrar(const char *name, void (*fn)()) { registry().push_back({name, fn}); }
};

inline int failures = 0;
inline const char *currentTest = "";

inline void fail(const char *file, int line, const std::string &message) {
    std::fprintf(stderr, "  FAIL [%s] %s:%d: %s\n", currentTest, file, line, message.c_str());
    ++failures;
}

inline int runAll() {
    int count = 0;
    for (const auto &t : registry()) {
        currentTest = t.name;
        std::printf("RUN  %s\n", t.name);
        const int before = failures;
        t.fn();
        if (failures == before) {
            std::printf("PASS %s\n", t.name);
        }
        ++count;
    }
    std::printf("\n%d test(s), %d failure(s)\n", count, failures);
    return failures == 0 ? 0 : 1;
}

}  // namespace minitest

#define TEST(name)                                                                     \
    void name();                                                                        \
    static ::minitest::Registrar registrar_##name(#name, &name);                        \
    void name()

#define EXPECT_TRUE(cond)                                                               \
    do {                                                                                \
        if (!(cond)) ::minitest::fail(__FILE__, __LINE__, "EXPECT_TRUE(" #cond ")");    \
    } while (0)

#define EXPECT_FALSE(cond)                                                              \
    do {                                                                                \
        if (cond) ::minitest::fail(__FILE__, __LINE__, "EXPECT_FALSE(" #cond ")");      \
    } while (0)

#define EXPECT_EQ(a, b)                                                                 \
    do {                                                                                \
        auto va = (a);                                                                  \
        auto vb = (b);                                                                  \
        if (!(va == vb)) {                                                              \
            std::ostringstream oss;                                                     \
            oss << #a " == " #b " (" << va << " != " << vb << ")";                      \
            ::minitest::fail(__FILE__, __LINE__, oss.str());                             \
        }                                                                                \
    } while (0)

#define EXPECT_NEAR(a, b, eps)                                                          \
    do {                                                                                \
        const double va = (double)(a);                                                 \
        const double vb = (double)(b);                                                 \
        const double diff = va > vb ? va - vb : vb - va;                                \
        if (diff > (eps)) {                                                             \
            ::minitest::fail(__FILE__, __LINE__,                                        \
                              std::string(#a " ~= " #b " (diff=") + std::to_string(diff) + \
                                  ")");                                                   \
        }                                                                                \
    } while (0)

#endif  // ROXSTAR_MINI_TEST_H
