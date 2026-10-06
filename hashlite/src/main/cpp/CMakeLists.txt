# ---------------------------------------------------------------------------
# libkeccak.so —— vendored OpenSSL keccak 汇编 + 薄封装 + JNI
#
# 注意：汇编是 aarch64 专属，且 NDK 宿主工具链只有 x86_64（本机 ARM64 上跑不了），
# 所以本地默认不编译它（见 build.gradle.kts 里的 hashLite.native 开关），由 CI 出 .so。
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)

project(keccak C ASM)

add_library(keccak SHARED
        keccak1600-armv8.S
        keccak_native.c
        jni_keccak.c)

target_compile_options(keccak PRIVATE -O2 -Wall)

# 汇编与实现里用到的 libc 函数（memcpy/clock_gettime/getauxval 等）由 bionic 提供；
# log 库留给以后诊断用。
find_library(log-lib log)
target_link_libraries(keccak ${log-lib})