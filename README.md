# CW Contest — Android CW Keying App

业余无线电 CW 拍发线安卓应用
专为 CQ WW CW / CQ WW VHF / CQ WPX CW 设计
支持老电台的转接，需搭配配套转接板
界面逻辑贴近 N1MM Logger。

——Create by codex（DeepSeek）
---

## 目录
1. [功能特性](#功能特性)
2. [硬件接线](#硬件接线)
3. [获取 APK](#获取-apk)
4. [编译安装](#编译安装)
5. [首次配置](#首次配置)
6. [主界面说明](#主界面说明)
7. [宏变量参考](#宏变量参考)
8. [比赛模式说明](#比赛模式说明)
9. [ADIF / CSV 导出](#adif--csv-导出)
10. [项目结构](#项目结构)
11. [常见问题](#常见问题)
12. [更新日志](#更新日志)

---

## 功能特性

| 功能 | 说明 |
|------|------|
| USB 串口 CW 键控 | 支持 CH340 / CP210x / FTDI / PL2303 / STM32 CDC |
| RTS 或 DTR 键控 | 可选高/低电平有效，独立或同时输出 |
| 边音监听 | AudioTrack 正弦波，频率 / 音量可调 |
| 时序精度 | PARIS 标准（点=1、划=3、字符内 1、字符间 3、字间 7 单位），绝对时间片调度，不因 USB 延迟抖动 |
| WPM 调速 | 5–60 WPM，滑条 + `−`/`+` 按钮 + 点击数值直接输入 |
| 拨片键控 | 直发 / Iambic A / Iambic B，屏幕 DOT·DASH 双桨 + 键盘方向键 |
| 比赛模式 | CQ WW CW、CQ WW VHF、CQ WPX CW、卫星（SAT） |
| 宏 F1–F12 | 模板变量自动展开，每种比赛独立一组，长按可编辑 / 改名 / 恢复默认，修改自动保存 |
| CQ 循环 | F1 触发，间隔可设，后台循环并在每轮震动提示 |
| 序号管理 | 仅 CQ WPX 递增交换序号（001/002…），按比赛独立计数 |
| Dupe 检查 | 输入时实时红色高亮 |
| QSO 日志 | 实时记录，自动落盘（重启后自动恢复），ADIF / CSV 导出 |
| 键盘支持 | 硬件键盘 F1–F12 / ESC / 方向键（DOT·DASH） |
| 主题 | 暗色 / 亮色一键切换，立即生效 |
| 后台发送 | 消息发送、CQ 循环在应用切到后台时继续运行 |

---

## 硬件接线

### 最小电路（直接驱动 3.5mm 插孔）

```
手机 USB-C OTG ──► CH340 模块
                        │
                       RTS ──┬── 100Ω ──► 3.5mm TIP (KEY Line)
                       GND ──┴──────────► 3.5mm SLEEVE (GND)
```

适合电台键控输入为 TTL/CMOS 电平（如很多 SDR 电台）。

### 继电器隔离电路（推荐用于大功率电台）

```
                    RTS ──── 1kΩ ──── Base (NPN, e.g. 2N2222)
                    GND ──────────── Emitter
                                     Collector ──┬── 继电器线圈 ──── +5V
                                                 └── 续流二极管 1N4148
                    继电器 COM ──► 电台 KEY
                    继电器 NO  ──► 电台 GND
```

### 连接核心参数

| 参数 | 推荐值 |
|------|--------|
| 波特率 | 9600（任意，RTS/DTR 不依赖波特率）|
| 逻辑极性 | 默认高电平有效；勾选"Invert Logic"改为低电平 |
| 边音频率 | 700 Hz（CW 标准监听音） |

---

## 获取 APK

有两条路，任选其一。

### 方案 A：用 GitHub Actions 自动出包（推荐，不需要本地 Android 环境）

仓库已包含 `.github/workflows/android.yml`。把它推到 GitHub 后：

1. 打开仓库 → **Actions** → **Android CI** → 最新一次运行；
2. 页面底部 **Artifacts** 下载 `cwcontest-debug-apk`；
3. 解压得到 `app-debug.apk`，传到手机、允许"安装未知应用"后安装。

打 tag 时会自动在 **Releases** 里发布 APK：

```bash
git tag v1.1.0 && git push origin v1.1.0
```

CI 会跑单元测试并产出两种包：

| 产物 | 说明 |
|------|------|
| `app-debug.apk` | 已用 debug 密钥签名，可直接安装（包名带 `.debug` 后缀）|
| `app-release-unsigned.apk` | 体积更小（R8 混淆），需自行签名后才能安装 |

自签名 release 包：

```bash
keytool -genkeypair -v -keystore cwcontest.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias cwcontest
$ANDROID_HOME/build-tools/34.0.0/zipalign -v 4 app-release-unsigned.apk app-release.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner sign --ks cwcontest.jks app-release.apk
```

### 方案 B：本地构建

需要 Android Studio（含 SDK 34）或命令行 JDK 17 + Android SDK 34。

```bash
./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # 直接装到已连接手机
./gradlew test                   # 单元测试
```

---

## 编译安装

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 34
- 目标设备 Android 8.0（API 26）或更新

> **重要**：`local.properties` 里的 `sdk.dir` 必须指向本机的 Android SDK。
> 该文件与本机相关、不进入版本管理；用 Android Studio 打开工程时它会自动重写。
> 若命令行构建报 `SDK location not found`，请设置环境变量 `ANDROID_HOME`
> 或手工修改 `sdk.dir`。

### 步骤

```bash
# 1. 克隆项目
git clone <你的仓库地址> && cd CWType-Andriod

# 2. 用 Android Studio 打开，或命令行构建
./gradlew assembleDebug

# 3. 安装到已连接设备
./gradlew installDebug
```

> **注意**：`settings.gradle` 中已包含 JitPack 仓库，用于下载
> `usb-serial-for-android` 库，**首次构建必须联网**（下载 AGP、Kotlin、
> AndroidX 与 Gradle 发行版，约数百 MB，之后走本地缓存）。

### 依赖说明

| 库 | 版本 | 用途 |
|----|------|------|
| usb-serial-for-android | 3.4.6 | USB 串口驱动（mik3y，经 JitPack） |
| androidx.recyclerview | 1.3.2 | 日志列表 |
| core-ktx | 1.12.0 | Kotlin 扩展 |
| appcompat | 1.6.1 | 界面与主题（含亮色/暗色）|

---

## 首次配置

1. 点击右上角 **⚙** 或菜单 → **Settings**
2. 填入 **My Callsign**（如 `BG4ABC`）
3. 填入 **My Grid**（VHF 赛使用，如 `OM12`）
4. 填入 **CQ Zone**（WW 赛使用，如 `24`）
5. 选择 **Use RTS** 或 **Use DTR**，与实际接线一致
6. 开启 **Sidetone** 并设置边音频率（默认 700 Hz）
7. 保存后返回主界面

---

## 主界面说明

```
┌─────────────────────────────────────────────────────────────┐
│[CQ WW CW▼] 14.025MHz 20m − ████████ + 25 WPM RTS○ DTR○ NR:001 USB ⋮ ⚙│
├────────────────────────────┬────────────────────────────────┤
│▶ CQ CQ DE BG4ABC BG4ABC K   │                               │
├────────────────────────────┼────────────────────────────────┤
│ CALLSIGN: [  W1AW       ]  │ [F1 CQ    ] [F2 Exch  ]       │
│ RCVD EXC: [  599 05     ]  │ [F3 TU    ] [F4 Call  ]       │
│ TX: 599 24                 │ [F5 AGN?  ] [F6 NR?   ]       │
│ [LOG QSO]  [■ ABORT]       │ [F7 QRZ?  ] [F8 CQ Sht]       │
│ [· DOT  ]  [— DASH ]       │ [F9 ?     ] [F10 Full  ]      │
│                            │ [F11 S&P  ] [F12 TU+CQ]       │
├────────────────────────────┴────────────────────────────────┤
│ Time   │ Callsign │ Sent      │ Rcvd    │ Band │  #         │
│ 123456 │ W1AW     │ 599 24    │ 599 05  │ 20m  │ 001        │
└─────────────────────────────────────────────────────────────┘
```

| 元素 | 操作 |
|------|------|
| 比赛选择器 | 下拉切换；每种比赛有独立宏集和序号 |
| 频率显示 | 在呼号框输入 `14.025`（MHz）或 `14025`（kHz）后回车，仅更新显示，**不控制任何硬件**；波段不被当前比赛允许时数字变红 |
| WPM | 拖动滑条、点 `−`/`+`（步进 1）、或点击 "25 WPM" 弹窗直接输入数字 |
| RTS● / DTR● | 发键时变绿 |
| 呼号框变红 | 该呼号在当前波段已 QSO（Dupe 警告），状态栏同时提示 |
| TX 提示 | 显示本台应发的交换信息（如 `TX: 599 24`），配置缺失时显示 ⚠ |
| 宏按钮 | 单击发送；**长按**弹出菜单：编辑模板 / 改名 / 立即发送 / 恢复默认（自动保存）|
| DOT / DASH | 按住即发。模式由 Settings → Keyer Mode 决定；Iambic 模式下两键同时按住即"挤压"交替发点划 |
| CQ 循环 | Settings 中开启后，单击 F1 自动循环，再点 **■ ABORT** 或 ESC 停止；每轮震动提示 |
| LOG QSO | 或直接在 Exchange 框按 Enter |
| 日志行 | 单击弹出选项：重发交换信息 / 删除本条 |
| ⋮ 菜单 | 导出 ADIF / 导出 CSV / 清空日志 / 恢复宏默认 / 设置（NoActionBar 主题下用弹出菜单）|
| 软键盘弹出时 | 自动收起拨片 / TX 提示 / 日志区与顶栏第二行，**F1–F12 宏键与呼号 / 交换框始终保持可见**；键盘收起后自动复原 |

### 硬件键盘快捷键

| 按键 | 动作 |
|------|------|
| F1–F12 | 发送对应宏 |
| ESC | 中止发送 + 停止 CQ 循环 |
| Enter（呼号框）| 若为频率则更新频率显示并清空输入，否则跳到交换信息框 |
| Enter（交换框）| LOG QSO |
| ↑ / PageUp | 按住 = DOT 拨片 |
| ↓ / PageDown | 按住 = DASH 拨片 |

---

## 宏变量参考

| 变量 | 内容 |
|------|------|
| `{MYCALL}` | 设置中的己方呼号 |
| `{CALL}` | 呼号输入框中的对方呼号 |
| `{RST}` | 固定 `599` |
| `{SERIAL}` | 当前序号（格式 `001`） |
| `{NR}` | 同 `{SERIAL}` |
| `{ZONE}` | 设置中的 CQ 分区（两位） |
| `{GRID}` | 设置中的己方 Maidenhead 网格 |
| `{THEIRGRID}` | 交换信息框内容（VHF 赛用） |
| `{THEIRNR}` | 同 `{THEIRGRID}`（语义化别名）|
| `{BAND}` | 当前波段标签，如 `20m` |
| `{FREQ}` | 当前频率，如 `14.025` |

**示例模板：**
```
CQ WW DE {MYCALL} {MYCALL} K
{CALL} 599 {ZONE} {MYCALL} K
TU {SERIAL} {MYCALL} CQ DE {MYCALL} K
```

---

## 比赛模式说明

### CQ WW CW
- 波段：1.8 / 3.5 / 7 / 14 / 21 / 28 MHz
- 交换信息：RST + CQ 分区（如 `599 24`）
- 序号：无（发送自己的分区，收对方分区）

### CQ WW VHF
- 波段：50 MHz（6m）/ 144 MHz（2m）
- 交换信息：4字符 Maidenhead 网格（如 `OM12`），**不含 RST**
- 序号：无

### CQ WPX CW
- 波段：1.8 / 3.5 / 7 / 14 / 21 / 28 MHz
- 交换信息：RST + 递增序号（如 `599 001`）
- 序号：每次 LOG QSO 自动递增，从 001 开始；**仅本模式**使用序号，WW / VHF 不会推进该计数

### 卫星（SAT）
- 波段：10m / 70cm / 2m（默认 2m）
- 交换信息：只有信号报告 `5NN`
- 序号：无。选中卫星模式时**隐藏序号显示与"收到交换"输入框**（呼号框的 IME 动作直接变成 LOG QSO）
- 日志的"收到"列按本台发出的报告 `5NN` 记录

---

## ADIF / CSV 导出

⋮ 菜单 → **Export ADIF** 或 **Export CSV**。文件名格式：

```
cwcontest_20241215.adi
cwcontest_20241215.csv
```

保存位置：

| Android 版本 | 位置 | 说明 |
|--------------|------|------|
| 10 (API 29) 及以上 | `Download/` | 通过 MediaStore 写入，无需任何权限 |
| 8 / 9 (API 26–28) | `Downloads/` | 首次导出会请求存储权限 |
| 权限被拒绝时 | `Android/data/com.cwcontest/files/Documents/` | 应用私有目录，仍然可导出 |

ADIF 字段：`QSO_DATE` `TIME_ON` `CALL` `BAND` `FREQ` `MODE` `RST_SENT`
`RST_RCVD` `STX_STRING` `SRX_STRING` `CONTEST_ID` `NOTES`（宏内容），
WPX 另有 `STX` 序号。CSV 列：日期/时间/呼号/波段/频率/发出交换/收到交换/比赛/序号/宏。

日志在每次 LOG QSO 后立即追加写入
`Android/data/com.cwcontest/files/Documents/cwcontest_log.adi`，
应用重启后自动读回，比赛中断不会丢日志。

---

## 项目结构

```
app/src/main/java/com/cwcontest/
├── Models.kt           — 数据模型（ContestMode / Band / QSOEntry / Macro…）
├── MorseCode.kt        — ITU 摩尔斯码表 + 元素编码器
├── CWEngine.kt         — CW 时序引擎（精确睡眠 + AudioTrack 边音）
├── USBSerialManager.kt — USB 串口管理（RTS/DTR 键控）
├── ContestManager.kt   — 比赛逻辑（序号 / Dupe / 交换信息）
├── MacroEngine.kt      — 宏模板变量替换
├── LogManager.kt       — QSO 日志 + 落盘恢复 + ADIF/CSV 导出
├── SettingsManager.kt  — SharedPreferences 持久化（含每种比赛的宏）
├── MainActivity.kt     — 主界面 Activity
└── SettingsActivity.kt — 设置界面 Activity

app/src/main/res/
├── layout/activity_main.xml
├── layout/activity_settings.xml
├── layout/item_qso.xml
├── menu/menu_main.xml
├── values/strings.xml
├── values/attrs.xml            — 亮色/暗色主题属性
├── values/themes.xml           — Theme.CWContest / .Light
└── xml/usb_device_filter.xml   — USB 自动启动过滤器

app/src/test/java/com/cwcontest/
└── CWContestTest.kt    — JUnit 单元测试（MorseCode / Macro / Contest / Band）

.github/workflows/
└── android.yml         — CI：跑单测 + 出 debug / release APK + tag 时发布 Release

根目录：LICENSE（MIT）/ CHANGELOG.md / .gitignore / .gitattributes
```

---

## 常见问题

**Q: 找不到 USB 设备？**  
A: 确认手机支持 USB Host（OTG），使用带芯片的 OTG 转接线，并确认芯片型号在
`usb_device_filter.xml` 中已列出。

**Q: RTS 信号有延迟？**  
A: 键控时序在 CWEngine 中按绝对时间片调度，最后 ~0.8 ms 用忙等待精调，
并且**每个点划绝不因 USB 传输耗时被压缩**，因此不会出现点划比例失真。
为保证后台连续拍发，请在电池设置中将本 APP 设为"不限制后台活动"
（部分品牌的省电策略会冻结后台线程）。

**Q: 边音和键控不同步？**  
A: 边音以 20 ms 小块写入 AudioTrack，缓冲约 40–80 ms，属正常现象，
不影响实际 RF 键控精度（RTS/DTR 与边音是两条独立通路）。

**Q: 拨片不工作？**  
A: DOT/DASH 拨片只在空闲时生效——正在拍发宏消息时按键会被忽略，
等消息发完再按。键控模式（直发 / Iambic A / Iambic B）在 Settings → Keyer Mode 中选择。

**Q: 为什么没有顶部菜单栏？**  
A: 主题是 NoActionBar（N1MM 风格全屏布局），所有菜单项都在右上角 **⋮** 按钮里。

**Q: 宏改完以后又变回默认了？**  
A: 宏按比赛模式分别保存在 SharedPreferences 中，切换比赛会切换宏组（这是特性）。
若要恢复某一组的默认值，用 ⋮ 菜单 → Restore/Reset Macros。

**Q: 构建时报 `Could not resolve com.github.mik3y:usb-serial-for-android:3.4.6`？**  
A: 该库通过 JitPack 分发，首次拉取需要网络且可能较慢（JitPack 是构建一次后缓存）。
重试一次通常即可；若仍失败，可把 `app/build.gradle` 中的版本换成 `3.6.0`
（该版本的 `setParameters(baudRate, dataBits, stopBits, parity)` 签名与本工程一致），
或把库源码以 `include ':usb-serial-for-android'` 的方式放进工程本地编译。

**Q: 手机上装不上 APK？**  
A: `app-debug.apk` 用 debug 密钥签名，可直接安装（首次需允许"安装未知应用"）；
`app-release-unsigned.apk` 是**未签名**包，必须先用自己的密钥签名再安装。

**Q: 如何支持更多 USB 芯片？**  
A: 在 `usb_device_filter.xml` 中添加对应 VID/PID，`usb-serial-for-android`
已内置大量驱动，一般无需修改 Kotlin 代码。

**Q: 想在 50 MHz 和 144 MHz 之间切换波段怎么办？**  
A: 在 CQ WW VHF 模式下，呼号框为空时直接输入频率（如 `144.100`），应用会
自动识别波段并更新波段显示，LOG QSO 时记录正确波段。

---

## 许可证

MIT License — 自由使用、修改和分发，请保留原始版权声明，全文见 [LICENSE](LICENSE)。

## 更新日志

各版本变更见 [CHANGELOG.md](CHANGELOG.md)。

73 de BG2GFC / CWType Project
