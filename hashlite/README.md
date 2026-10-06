# 哈希计算（HashLite）

纯净版文件哈希工具：只保留最常见的算法，界面为主，不带基准/自检/温控仪表。

- 包名 `io.github.xiaokun19.hashlite`　minSdk 24 / compileSdk 35 / targetSdk 35
- UI：Jetpack Compose + Material 3，自定义冷色主题（不跟随动态取色）
- **不申请任何存储权限**：文件通过 SAF（`OpenDocument`）只读打开；整目录批量校验用 SAF 目录树（`OpenDocumentTree`），同样不申请权限
- 支持从其它应用「分享 / 打开」进来：**只把文件带进来，不自动开算**（需自己点一下"开始计算"）
- 两个页签：**单个文件** / **批量校验**（批量＝选目录 + 可选校验文件 → 并行哈希 → 逐文件判定）

## 算法（10 个，分两组）

| 组 | 算法 | 实现 | 加速情况（本机实测） |
|---|---|---|---|
| 常用 | MD5 | 平台 | 软件（无对应指令） |
| 常用 | SHA-1 / 224 / 256 / 384 / 512 | 平台 BoringSSL | **ARMv8 指令，实测比软件快 11–29×** |
| 更多（默认折叠） | SHA3-256 / SHA3-512 | **native**（vendored OpenSSL 汇编）→ 回退 BouncyCastle | native 实测比 BC **快 2.8×**（1017.8 vs 367.7 MB/s，见下） |
| 更多 | SM3（国密） | BouncyCastle | 纯软件（CPU 有 `sm3` 指令，但平台无实现、也还没写 native） |
| 更多 | CRC32 | java.util.zip（native/zlib） | 实测比 Java 查表实现快 26–34× |

- "更多"默认折叠，点标题行展开；**收起时会自动取消其中已勾选项**，避免"看不见却还在算"。
- 引入 BouncyCastle 后 debug APK 由 11.8MB 变为 19.6MB：SHA-3 与 SM3 在 Android 上没有平台实现，只能自带。
- CRC32 是**校验和**，不是加密哈希，UI 里明确标注。

---

## 硬件加速检测（`core/HardwareAcceleration.kt`）

目标：**在不同机器上都按实际情况决定要不要亮那枚芯片徽标**。分两层，缺一不可。

### 第 1 层：CPU 有没有这条指令

读 `/proc/self/auxv` 的 `AT_HWCAP`（比解析 `/proc/cpuinfo` 文本精确，不受型号命名影响），
读不到再回退到 cpuinfo 的 Features 文本。

| 位 | 5 | 6 | 7 | 17 | 18 | 21 |
|---|---|---|---|---|---|---|
| 含义 | sha1 | sha2 | crc32 | sha3 | sm3 | sha512 |

> 位号可信度：本机 `/proc/cpuinfo` 的 Features 顺序与 HWCAP 位序一致
> （`… dcpop sha3 sm3 sm4 asimddp sha512 …`），据此逐一对应，并有单测钉死。

成本：**0.8 ms**（首次）/ **3.4 ms**（含 SharedPreferences 首次读取）。

### 第 2 层：平台库有没有真的用上

读位只能说明"CPU 有能力"。**CPU 有指令 ≠ 库用了指令**——本机就是活例子：

```
SHA3-256   徽标=灭   纯软件实现；CPU 有 sha3 指令但平台无实现
SM3        徽标=灭   纯软件实现；CPU 有 sm3 指令但平台无实现
```

所以还要实测：把**平台实现**与**同类纯软件实现**各跑一遍同样大小的数据，算比值。

本机实测（1MiB × 2 轮，取最优）：

| 算法 | 平台 | 纯软件基线 | 比值 | 判定 |
|---|---|---|---|---|
| SHA-1 | 1.87 GB/s | 0.16 GB/s（BC） | 11.6× | 硬件 ✓ |
| SHA-256 | 2.01 GB/s | 0.07 GB/s（BC） | 28.5× | 硬件 ✓ |
| SHA-512 | 1.28 GB/s | 0.09 GB/s（BC） | 14.1× | 硬件 ✓ |
| CRC32 | ~2.6 GB/s | 0.08 GB/s（Java 查表） | 26–34× | 原生(zlib) ✓ |

判定规则与门槛：

- 摘要类（JCA）：`CPU 有位` **且** `比值 ≥ 2.0`
- CRC32：门槛单独抬到 `5.0` —— 它的基线是 Java 查表实现，而平台是 native(zlib)，
  差距里混着"语言/运行时"因素，不能按摘要类算
- 纯软件实现（BC 的 SHA-3 / SM3）：**永不亮徽标**
- 还没测过时先用"CPU 有位"的乐观估计，测完自动修正

成本与缓存：冷启动一次性约 **0.6–0.9 秒**（大部分是纯软件基线的 JIT 预热），
结果写进 SharedPreferences；之后启动**读缓存只要 ~3 ms，完全不再探测**。

### 这套设计踩过的坑

1. 最初把 CRC32 的软件基线写成**逐位实现**，得到 346× —— 这个数字毫无意义（基线太慢）。
   换成 256 项查表后降到 26–34×，才反映真实差距。
2. 探测一开始用 4MiB × 3 轮，耗时约 1.5s；改成 1MiB × 2 轮后 ≈0.6s，
   比值余量（十几到几十倍）大到完全不怕噪声。
3. 微基准会受温度/频率影响，但**比值**有 10–30× 的余量，比绝对速度稳健得多。

## 引擎要点（`core/LiteHasher.kt`）

1. direct `ByteBuffer` + 定位读（`FileChannel.read(buf, pos)`），零拷贝喂 `MessageDigest`
2. 2 个预读线程 + 有界槽队列：读取与计算重叠（4MB 块 + 2 线程是实测甜点）
3. 进度用**最近 2 秒滑窗**速度算 ETA，而不是累计平均（否则降频时"剩余时间"会卡住）
4. 文件大小未知时自动退化为单缓冲顺序读到 EOF
5. 只用 `ParcelFileDescriptor.AutoCloseInputStream` 包 dup 出来的 fd —— 直接
   `FileInputStream(dup.fd)` 会让 GC 终结器提前关掉 fd，偶发 `Bad file descriptor`

## 界面

- 顶部大标题 + 一行硬件加速状态 + 右上角**帮助按钮**（圆底问号，点开是自底升起的说明页：
  为什么要用哈希 / 这几个算法分别是什么 / 硬件加速是什么意思 / 本应用的取舍）
- 选文件卡片（未选时是整块可点的"选择文件"区）
- 算法 chips（**2 列 × 3 行**）+ 每个支持硬件加速的算法右侧一枚 CPU 芯片徽标 + 一行图例
  - 徽标放在 `FilterChip` 自带的 `trailingIcon` 槽，而不是塞进 `label` 的 Row：
    M3 的 label 是 `weight(1f, fill=false)`，长标签会先让位，徽标才不会被挤成几 dp
  - 用 2 列而非 3 列：本机字体缩放大（约 1.2×）时，"SHA-256 + 徽标"在 1/3 宽里
    会把标签截成 `SHA-2…`
- 全宽主按钮 + 自定义进度条（已读/总量 · 速度 · 剩余；拿不到总大小时只报已读与速度）
- 结果卡：每个算法一行，**点按整行即复制**（带"已复制"回显），"复制全部"
- **"大写"开关**：只影响显示与复制的内容，内部一律小写存储/比对，切换不重算
- 校验卡：粘贴任意形式的校验值 → 自动按长度识别算法 → 大色块显示"校验通过/不通过"
  （**忽略大小写**，容忍 `0x` 前缀、空格、`SHA256 (f) = xxx`、整行 `sha256sum` 输出）

## 批量校验 / 并行哈希（`core/ChecksumFile.kt` · `core/BatchHasher.kt` · `core/SafTree.kt`）

**为什么做**：单个文件的哈希链**无法并行**（实测硬顶 ~1.85 GB/s，加预读线程也不会更快），
想把**聚合**吞吐抬上去只能"同时算多个文件"；而下载场景真正要做的是"拿一份旁挂清单校验一整个目录"。

### 校验文件（三种主流格式，导入 + 导出）

| 格式 | 长什么样 | 备注 |
|---|---|---|
| coreutils | `<hex>  <文件名>` | `sha256sum` 的输出；`*` 前缀 = 二进制模式；文件名含换行/反斜杠时 GNU 会转义并在行首加 `\` |
| BSD | `SHA256 (文件名) = <hex>` | macOS `shasum` 风格；容忍 `MD5(f)=hex` 与引号 |
| SFV | `<文件名> <8位CRC32>` | `;` 开头是注释，导出用大写十六进制 |

- 解析**不整份作废**：看不懂的行进 `badLines`，界面提示"有 N 行没看懂，已忽略"。
- 算法消歧顺序：**行内标签 > 清单文件名提示 > 长度偏好**。
  64 位十六进制有 SHA-256 / SHA3-256 / SM3 三个候选，默认按 SHA-256，
  但 `foo.sha3-256` 这种文件名会把它纠回 SHA3-256。
- 匹配前一律归一化（去 `./`、`\`→`/`、小写），所以清单写完整路径 / 目录里只有文件名、
  或 Windows 写出的 `dir\file.bin`，都能对上。
- 导出时只写**算过的那个算法**；SFV 只装得下 CRC32，界面会把不合法组合拦住。

### 并行引擎

- K 个 worker 线程 + `AtomicInteger` 取号；每个文件一个 `LiteHasher`（1 预读线程、4MB 块）。
  结果按**输入下标**回填 → 顺序与并行度无关，**结果必然等于串行**（单测钉死 1/2/3/8 路一致）。
- 每个文件只算**清单要求的那个算法**：64 位十六进制清单不该顺手把 SHA3-256 + SM3 一起算了，那是白送几倍时间。
- 进度回调整体加锁：否则两个 worker 的"读计数 + 回调"会乱序，界面进度条往回跳。
- 判定：**匹配 / 不匹配 / 缺失（清单里有、目录里没有）/ 未列出 / 读取失败**；不选清单时全是"未列出"。
- 内存 = 并行度 × 2 × 块大小（>4 路自动降到 2MB 块）。

### 真机实测（16 × 64MiB = 1.00 GB，SHA-256，页缓存命中 + FUSE）

| 并行度 | 聚合吞吐 | 平均每文件 | 加速比 |
|---|---|---|---|
| 1 | 1.67 GB/s | 1.69 GB/s | 1.00× |
| 2 | 3.53 GB/s | 1.77 GB/s | 2.12× |
| 4 | 6.05 GB/s | 1.57 GB/s | 3.63× |
| 8 | 7.35 GB/s | 1.02 GB/s | 4.41× |

单文件上限 1.85 GB/s → 8 路并行把聚合推到 7.35 GB/s，**这就是"并行是唯一杠杆"的实测证据**。
（单次测量窗口 136–600 ms，噪声不小；同一批里并行度 6 出现的 4.92 GB/s 明显是干扰点。）

### 踩坑

1. 小文件（16MiB）时并行度 2 只从 928 MB/s 涨到 1.63 GB/s，且每文件速度反而下降：
   **每个文件都要新申请 2×4MB direct buffer + 线程启动开销**。文件又小又多时，缓冲区复用是下一步优化点。
2. `SafTree` 每个文件只在算它的那一瞬间持有 fd（算完就关），4000 个文件的目录也不会耗尽 fd。
3. `Android/data`、`Android/obb` 在系统 SAF 选择器里**根本选不了**；根目录/下载内容根会弹
   「无法使用此文件夹」，必须先进子目录才能点"使用这个文件夹"。
4. App 无 root → 无法 `drop_caches`，所有速度数字都带"页缓存命中"这个前提。

## native Keccak（SHA-3 提速 2.8×）

SHA3-256/512 走 **native**（vendored OpenSSL aarch64 汇编，`src/main/cpp/`），细节与许可见 `cpp/README.md`。

- **两道门**：`.so` 加载成功 **且** 向量自检通过才启用；否则回退 BouncyCastle——**宁可慢，不产出错哈希**；
- **变体校正**：汇编里有两套实现（普通 / EOR3 扩展）。首次遇到 ≥8 MiB 大块时用 16 MiB 实测，
  只有 EOR3 真快 >3% 才切（作者注释里存在"扩展反而更慢"的核）；
- **实测**（本机，App 内交替测量）：SHA3-256 **1017.8 MB/s vs BC 367.7 MB/s = 2.77×**，
  与 BC 逐字节一致；64 MiB 内存基准里 plain 772.8 / cext 989.8 MB/s（1.28×）；
- **构建**：native 默认**不在本地编**（NDK 宿主工具链只有 x86_64，ARM64 手机跑不了），
  由 CI 用 `-Phashlite.native=true` 出 `.so`；本地开发循环不受影响（自动回退 BC）。

自检入口（无头，报告写到 App 外部目录）：

```bash
am start -n io.github.xiaokun19.hashlite/.MainActivity -e nativecheck 1
cat /sdcard/Android/data/io.github.xiaokun19.hashlite/files/nativecheck.txt
```

## 图标

设计源与生成流水线都在 `icon/`：

| 文件 | 作用 |
|---|---|
| `icon/build_icon.py` | 唯一入口：生成下面所有产物 |
| `icon/ic_launcher.svg` | 设计源（512，由脚本生成，勿手改） |
| `res/drawable/ic_launcher_background.xml` | 自适应图标背景层（矢量渐变 + 柔光） |
| `res/drawable/ic_launcher_foreground.xml` | 前景层（斜体 # + HASH） |
| `res/drawable/ic_launcher_monochrome.xml` | Android 13+ 主题化图标用的单色层 |
| `res/mipmap-*/ic_launcher(.round).png` | API < 26 用的位图（脚本用 rsvg-convert + Pillow 生成） |

设计：冷蓝→青渐变方底 + **斜体 #（上移）** + 下方 **HASH**（DejaVu Sans Bold Oblique）。

两个必须知道的限制（脚本里都处理了）：
- **VectorDrawable 不支持文字**，也不支持 skew → "HASH" 用 fontTools 从字体抽成轮廓路径，
  `skewX(-12)` 直接烘焙进坐标
- fontTools 的 `SVGPathPen` 会输出 `H`/`V` 单参数简写与 `M` 后的隐式 lineto，
  解析时必须展开，否则整个路径坐标错位（第一版就是画歪的）

几何安全区：自适应图标只有中央 72dp 可见，脚本里所有坐标都以"512 空间 ↔ 108 层
（`scale = 72/512`）"换算，当前内容（# 与 HASH）在圆形面具下也不会被切到。

重新生成：

```bash
cd hashlite/icon
python3 build_icon.py        # 需要 rsvg-convert 与 Pillow
```

## 帮助页的两个排版坑（都踩过）

1. **滚动区必须有界**：`Column` 里放 `verticalScroll` 的区块，一定要给它 `weight(1f)`。
   若外层 Column 是 wrap-content，量算会跑偏——上一版一打开就停在内容**底部**。
2. **别在手机上做三列表格**：`名字 | 数值 | 长说明` 的第三列会被挤出屏幕。
   改成两行式条目（第一行"名称 + 右侧数值"，第二行说明占满整宽）。

## 构建 / 安装 / 测试

```bash
./gradlew :hashlite:test :hashlite:assembleDebug     # 48 个单测 + APK
# shell 侧：
# 直接喂文件给它算（自动开算）：
am start -a android.intent.action.VIEW -d content://media/external/file/<id> -t '*/*' -f 1 -n io.github.xiaokun19.hashlite/.MainActivity
# 无头硬件检测（读报告，不用点屏幕）：
am start -n io.github.xiaokun19.hashlite/.MainActivity -e hwcheck 1     # → hwcheck.txt
# 无头批量自检：并行度 → 吞吐曲线 + 清单闭环（正向/负向/缺失）
am start -n io.github.xiaokun19.hashlite/.MainActivity -e batchcheck 1 -e files 16 -e sizeMiB 64 -e workers 1,2,4,6,8
# 报告都在 /sdcard/Android/data/io.github.xiaokun19.hashlite/files/*.txt（shell 可读）
```

单测覆盖：38 条公开向量（empty / "abc" / 1e6×'a'）、9 种引擎配置与顺序哈希一致、
未知大小退化路径、进度单调、空文件、未选算法、粘贴解析与格式化、
以及"大小写只影响显示、不影响识别与比对"；
校验文件 16 条（三种格式解析/生成往返、GNU 逃逸、算法消歧、路径归一化、缺失判定）；
批量 10 条（**1/2/3/8 路并行结果与串行一致**、判定四态、进度单调、单文件失败不影响整批、
取消、未知大小、导出→回读→校验闭环）。
