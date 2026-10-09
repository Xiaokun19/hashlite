# cpp/ —— native Keccak（vendored 第三方汇编 + 薄封装）

给 App 提供 SHA-3/SHAKE 的 native 实现。**实测比 BouncyCastle 快 2.8×**
（SHA3-256 1017.8 vs 367.7 MB/s；本机实测）。

## 目录

| 文件 | 说明 |
|---|---|
| `keccak1600-armv8.S` | **vendored 第三方代码**：OpenSSL 3.0.13 `crypto/sha/asm/keccak1600-armv8.pl` 的生成物。指令本体未改动，仅前置了一段来源说明。同时包含普通变体与 EOR3 变体，且以 `.inst` 编码给出 |
| `keccak_native.c/.h` | 薄封装：流式 API、HWCAP 探测、变体选择、自检、测速。**桌面 Linux 与 Android 共用**（本地 gcc 就能验证） |
| `jni_keccak.c` | JNI 胶水，对应 Kotlin 侧 `core/NativeKeccak.kt` |
| `test_linux.c` | 本地验证程序（gcc 直接编，不需要 NDK）：向量自检 + 与系统 OpenSSL 对拍 + 测速 |
| `test_nosha3.c` | 模拟"无 SHA3 扩展"的老芯片（`--wrap=getauxval`），回归验证 cext(EOR3) 被严格门控、绝不执行 |
| `CMakeLists.txt` | 只针对 arm64-v8a |

## 许可证

本项目整体为 **MIT**（见仓库根 `LICENSE`）。本目录的 `keccak1600-armv8.S` 是 vendored 第三方代码：

- **来源**：OpenSSL 3.0.13 `crypto/sha/asm/keccak1600-armv8.pl` 的官方生成物；已对拍——本仓库文件
  （除头部说明外）与官方重新生成**逐行一致**，指令本体未改动。
- **版权**：Copyright 2017-2020 The OpenSSL Project Authors。Written by Andy Polyakov <appro@openssl.org>。
- **许可**：**Apache License 2.0**（副本取自 OpenSSL 项目；上游原件同时声明
  "dual licensed under OpenSSL and CRYPTOGAMS licenses depending on where you obtain it"，
  见 https://www.openssl.org/~appro/cryptogams/）。
- **落点**：许可全文 `LICENSES/Apache-2.0.txt`、声明 `THIRD_PARTY_NOTICES.md`；文件头的前置说明请一并保留。

## 重新生成

```bash
# 取 OpenSSL 3.0.13 源码（约 15 MB）：
curl -sSL -o ossl.tar.gz \
  'https://github.com/openssl/openssl/archive/refs/tags/openssl-3.0.13.tar.gz'
# （直连不畅时可换任意可达的 GitHub 镜像 / 代理域名）
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

模拟"没有 SHA3 扩展"的老芯片（回归验证 cext 门控）：

```bash
gcc -O2 -o /tmp/test_nosha3 test_nosha3.c keccak_native.c keccak1600-armv8.S \
    -Wl,--wrap=getauxval -lcrypto
/tmp/test_nosha3   # 期望：全过、cext = 0 MB/s（绝不执行 EOR3）
```

（可选·更接近真机）用 qemu 租一颗无 SHA3 的 CPU：`apt install qemu-user` 后
`qemu-aarch64 -cpu cortex-a72 <测试程序>`。

## 指令门控（兼容性）

EOR3/RAX1/XAR/BCAX（ARMv8.2-SHA3 扩展）只在 **HWCAP sha3 = 1** 的 CPU 上允许执行——
没有该扩展的老芯片（Armv8.0 系，A72/A73 内核的机型）执行这些指令会 **SIGILL**。因此：

- 自检、测速里的 cext 路径，以及 `kc_set_variant(1)` 一律由 `kc_have_sha3ext()` 门控；
- 正常哈希默认 plain，只有实测快 > 3% 且 HWCAP 通过才切 cext；
- `test_nosha3.c` 是这份约束的回归测试（2026-10-10 加，此前自检/测速未门控，老芯片会闪退）。

## 为什么本地默认不编译

NDK 的**宿主工具链只有 `linux-x86_64`**，ARM64 手机执行不了它的 clang。
所以本地默认关（`-Phashlite.native` 开关），由 CI（x86_64 runner）出 `.so`。