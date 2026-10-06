# cpp/ —— native Keccak（vendored 第三方汇编 + 薄封装）

给 App 提供 SHA-3/SHAKE 的 native 实现。**实测比 BouncyCastle 快 2.8×**

## 目录

| 文件 | 说明 |
|---|---|
| `keccak1600-armv8.S` | **vendored 第三方代码**：OpenSSL 3.0.13 `crypto/sha/asm/keccak1600-armv8.pl` 的生成物。指令本体未改动，仅前置了一段来源说明。同时包含普通变体与 EOR3 变体，且以 `.inst` 编码给出 |
| `keccak_native.c/.h` | 薄封装：流式 API、HWCAP 探测、变体选择、自检、测速。**桌面 Linux 与 Android 共用**（本地 gcc 就能验证） |
| `jni_keccak.c` | JNI 胶水，对应 Kotlin 侧 `core/NativeKeccak.kt` |
| `test_linux.c` | 本地验证程序（gcc 直接编，不需要 NDK）：向量自检 + 与系统 OpenSSL 对拍 + 测速 |
| `CMakeLists.txt` | 只针对 arm64-v8a |

## 许可证（要发布时请照做）

- OpenSSL 3.x 本体是 **Apache-2.0**；
- 这份 `.pl` / 生成的 `.S` 还额外受 **CRYPTOGAMS 许可**（Andy Polyakov，BSD 风格）约束，
  属"双许可"——**随项目分发时保留文件头说明**；若要把汇编单独再发布，请一并带上两份许可文本。
- 生成物里保留了原作者署名串（`CRYPTOGAMS by <appro@openssl.org>`），请勿删除。

## 重新生成

```bash
curl -sSL -o ossl.tar.gz \
tar xzf ossl.tar.gz && cd openssl-openssl-3.0.13
# 生成 .inst 形式（不依赖汇编器认识 armv8.2-a+sha3，最可移植）
perl crypto/sha/asm/keccak1600-armv8.pl linux64 /tmp/keccak1600-armv8.S
```

## 本地验证（不需要 NDK）

```bash
cd hashlite/src/main/cpp
gcc -O2 -o /tmp/test_linux test_linux.c keccak_native.c keccak1600-armv8.S -lcrypto
/tmp/test_linux          # 自检 + 与 OpenSSL 对拍 + plain/cext 测速
```

## 为什么本地默认不编译

NDK 的**宿主工具链只有 `linux-x86_64`**，ARM64 手机执行不了它的 clang。
所以本地默认关（`-Phashlite.native` 开关），由 CI（x86_64 runner）出 `.so`。