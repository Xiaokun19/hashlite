# 第三方组件与许可声明（Third-Party Notices）

本仓库整体以 **MIT** 协议开源（见 [`LICENSE`](LICENSE)）。以下内容除外——它们随仓库携带或随 APK 分发，保留各自的许可。

## 1. vendored 汇编：`hashlite/src/main/cpp/keccak1600-armv8.S`

- **来源**：OpenSSL 3.0.13 的 `crypto/sha/asm/keccak1600-armv8.pl` 官方生成物（`perl keccak1600-armv8.pl linux64`）。
  已做对拍：本仓库文件（除前置的中文出处说明外）与该命令重新生成的输出**逐行一致**，指令本体未改动。
- **版权**：Copyright 2017-2020 The OpenSSL Project Authors；Written by Andy Polyakov <appro@openssl.org>。
- **许可**：**Apache License 2.0**。上游原件同时声明："dual licensed under OpenSSL and CRYPTOGAMS licenses
  depending on where you obtain it"（https://www.openssl.org/~appro/cryptogams/）；本仓库的副本取自 OpenSSL 项目，
  适用 OpenSSL 许可（3.x = Apache-2.0）。
- **许可全文**：[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)。

## 2. 运行期依赖（通过 Maven 引入，随 APK 一起分发）

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack Compose（`androidx.*`） | Apache-2.0 |
| Bouncy Castle（`org.bouncycastle:bcprov-jdk18on`） | Bouncy Castle Licence（官方说明：按 MIT 方式解读，https://www.bouncycastle.org/licence.html） |

---

如对版权或署名有疑问，欢迎在本仓库提 Issue。