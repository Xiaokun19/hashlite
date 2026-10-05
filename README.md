# 哈希工具（HashLite）

Android 文件哈希计算 / 校验工具，本仓库**只有一个模块**：

| 模块 | 应用名 / 包名 | 定位 |
|---|---|---|
| `:hashlite` | 哈希计算 / `io.github.xiaokun19.hashlite` | 纯净版：10 个常用算法 + 批量校验 / 并行哈希，界面为主 |

> **远程仓库**：<https://github.com/Xiaokun19/hashlite>（私有）·
> **CI**：`.github/workflows/ci.yml`，push / PR 时在 ubuntu-latest 上跑 **48 个单测 + Debug APK**（APK 作为 artifact）。

详细设计（算法表、硬件加速两层检测、引擎、批量校验、图标流水线、全部实测数据）见 **[`hashlite/README.md`](hashlite/README.md)**。


---

## 构建 / 测试

```bash
./gradlew :hashlite:test :hashlite:assembleDebug
```

- **ARM64 上编 APK 必须先** `./setup_android_env.sh`（把 ARM64 版 aapt2 装到 `/root/.operit-tools/aapt2`，
  由 `gradle.properties` 的 `android.aapt2FromMavenOverride` 指过去）；文件丢了会**硬失败**，重跑脚本即可。
- 环境：JDK 17 / Android SDK `/root/Android`（platforms;android-35）/ Gradle 9.1 wrapper / AGP 9.0.0 /
  Kotlin 2.3.10 / Compose BOM 2026.01.01；minSdk 24 / compileSdk 35 / targetSdk 35。
- 依赖只有 BouncyCastle（`bcprov-jdk18on`，SHA-3 / SM3 用），版本在根 `gradle/libs.versions.toml` 统一管。


```bash
# shell 侧
```

## 无头入口（不用点屏幕，报告写到 App 外部目录，shell 可读）

```bash
# 硬件加速检测报告 → hwcheck.txt
am start -n io.github.xiaokun19.hashlite/.MainActivity -e hwcheck 1
# 批量并行自检：并行度 → 吞吐曲线 + 清单闭环（正向 / 故意改坏 / 缺失）→ batchcheck.txt
am start -n io.github.xiaokun19.hashlite/.MainActivity -e batchcheck 1 -e files 16 -e sizeMiB 64 -e workers 1,2,4,6,8
# 报告位置
/sdcard/Android/data/io.github.xiaokun19.hashlite/files/
```

## 仓库里的其它文档

| 文件 | 作用 |
|---|---|
| `hashlite/README.md` | 纯净版完整设计文档（先看这个） |
| `setup_android_env.sh` | ARM64 aapt2 与构建环境初始化 |
| `gradle/libs.versions.toml` | 依赖与插件版本目录 |