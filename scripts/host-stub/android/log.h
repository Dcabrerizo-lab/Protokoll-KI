#pragma once
#include <cstdarg>
#include <cstdio>
enum { ANDROID_LOG_INFO=4, ANDROID_LOG_WARN=5, ANDROID_LOG_ERROR=6 };
// Auf dem PC: Meldungen nach stderr (zeigt im Qualitätstest u. a. die Zeitmessung „PKREPORT llm.N“)
inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    if (prio < ANDROID_LOG_INFO) return 0;
    va_list ap; va_start(ap, fmt);
    std::fprintf(stderr, "[%s] ", tag); std::vfprintf(stderr, fmt, ap); std::fputc('\n', stderr);
    va_end(ap);
    return 0;
}
