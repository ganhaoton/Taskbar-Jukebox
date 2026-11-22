# 窗外的音乐 / Taskbar Jukebox

让《网络音乐机》的旋律，在系统媒体控件上继续流淌。

这是一个 Minecraft 1.21.1 NeoForge 的**纯客户端**模组：它读取《网络音乐机》（NetMusic）正在播放的曲目信息，单向推送到系统媒体控件（Windows SMTC / Linux MPRIS），供 Windows 任务栏媒体卡片、任务栏歌词插件以及 KDE Plasma 等 MPRIS 桌面媒体控件使用。模组不联网、不改动游戏播放行为。

## 目录

- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装](#安装)
- [自定义封面图标](#自定义封面图标)
- [配置](#配置)
- [工作原理](#工作原理)
- [从源码构建](#从源码构建)
- [故障排查](#故障排查)
- [更新日志](#更新日志)
- [致谢](#致谢)
- [许可证](#许可证)

## 功能特性

- **只读推送**：推送歌名、作者、播放进度、时长与播放/暂停状态，不写入、不联网。
- **自定义封面**：系统媒体卡片上的封面图可随时替换，无需重新编译，详见[自定义封面图标](#自定义封面图标)。
- **精妙背包兼容**：SophisticatedBackpacks 的唱片机升级播放 NetMusic 唱片时，曲目信息同样会被推送；背包放在世界方块上或佩戴在 Curios 饰品栏时均可识别，无需打开界面。
- **忽略系统操作**：Windows 任务栏上的播放、暂停、停止按钮保持可用以便系统建立媒体会话，但回调全部为空；Linux MPRIS 的 `CanControl/CanPlay/CanPause/CanSeek/CanGoNext/CanGoPrevious` 均为 `false`。点按不会回写游戏。
- **Linux MPRIS**：在支持 MPRIS 的桌面（如 KDE Plasma）显示歌名、作者、播放状态与进度；只读、不可控制游戏。
- **软依赖 NetMusic**：未安装 NetMusic 时模组照常加载，只是不推送任何曲目。
- **系统能力探测**：非 Windows/Linux、Windows 8.1 及更早版本、Linux 缺少 `DBUS_SESSION_BUS_ADDRESS`，或缺少前置类时，模组自动降级为完全空转的「空气模组」，不加载原生库、不建立 D-Bus 连接、不注册事件，从根本上避免崩溃。
- **诊断日志默认关闭**：可在 NeoForge 模组配置页面开启，也可用 JVM 参数强制覆盖。
- **前置库随包携带**：`JavaMediaTransportControls`、dbus-java core 与 JDK Unix socket transport 均通过 jarJar 内嵌；构建会剥掉 transport 的 `module-info.class`，使其在 FML 的 plugin layer 中以自动模块加载。玩家无需单独安装前置，Linux 不新增 JNI/native 依赖。

## 环境要求

- Minecraft **1.21.1**
- NeoForge **21.1.235** 或更高
- Java **21**
- Windows 10 / Windows 11（任务栏媒体控件依赖系统 SMTC）
- 或 Linux + 支持 MPRIS 的桌面（如 KDE Plasma），且存在可用的 session D-Bus
- 其他系统（macOS、无 session bus 的 Linux 等）会自动空转
- 《网络音乐机》NetMusic（可选，但需要有它播放音乐才有内容可推送）

## 安装

1. 安装 NeoForge 1.21.1（21.1.235+）。
2. 将构建产物中**带 `-all` 后缀**的 jar（例如 `taskbar_jukebox-1.2.1-all.jar`）放入游戏目录的 `mods` 文件夹。
3. 启动游戏，进入世界并用 NetMusic 播放音乐；Windows 任务栏媒体控件、KDE Plasma 媒体控件或歌词插件即可读到曲目信息。

> 注意：不带 `-all` 后缀的是不含前置库的瘦 jar，单独使用会因缺少 `JavaMediaTransportControls` 而无法工作。

## 自定义封面图标

系统媒体卡片上显示的封面图可以自由更换，Windows 与 Linux 均适用。

**默认图标**：模组内置一张 256×256 的 Minecraft 草方块图标（取自 Minecraft 官方安装器）。首次启动时会把它释放到游戏运行目录下的 `config/taskbar_jukebox/cover.png`。

**换成自己的图标**：

1. 准备一张 PNG 图片，建议 256×256 像素（其他尺寸也可），可带透明通道。
2. 用它覆盖 `config/taskbar_jukebox/cover.png`。
3. 无需重启游戏，也无需重新编译。模组检测到文件变动后会自动重新推送，任务栏媒体卡片、歌词插件或桌面控件随即更新。

> 说明：
> - 已存在的 `cover.png` 不会被再次启动覆盖，升级模组也不会冲掉你的图。
> - 想恢复默认图标，删掉该文件后重启游戏，模组会重新释放内置图。
> - 封面只在本机显示，不上传、不联网。

## 配置

打开 `模组 (Mods)` 页面，找到「窗外的音乐 / Taskbar Jukebox」，进入配置，将客户端项 **启用详细诊断日志** 打开即可。配置修改即时生效，无需重启。

也可以启动前追加 JVM 参数强制覆盖配置页：

```
-Dtaskbar_jukebox.debug=true
```

排查问题时建议开启日志，正常使用时保持关闭。

## 工作原理

- **读取**：在渲染线程通过反射读取客户端 `SoundManager` 的声音引擎，定位 NetMusic 的音源，取播放刻度与总刻度换算为进度；歌名、作者、时长则来自对应方块实体中唱片的数据。方块实体上的播放标记存在同步延迟，因此播放状态以音源是否停止为准。精妙背包的唱片则直接读 CD 的数据组件，并按背包所在位置或佩戴槽位定位，不依赖 GUI 是否打开。
- **推送**：读取与推送已解耦为 `MediaSessionBackend`。Windows 后端把原 SMTC 逻辑收进 `WindowsSmtcBackend`；前置库的原生 DLL 通过 `winrt::init_apartment()` 创建与线程绑定的 SMTC 对象，因此初始化与全部推送操作都在同一条专用守护线程上执行。Linux 后端 `LinuxMprisBackend` 在同一类专用线程上建立 dbus-java 连接、导出 `org.mpris.MediaPlayer2` 对象并发送 `PropertiesChanged`；渲染线程只负责读取并把不可变的曲目快照投递过去。封面来自 `config/taskbar_jukebox/cover.png`，由媒体线程在文件变化时重新推送。
- **降级**：非 Windows/Linux、旧 Windows、Linux 无 session bus、服务名冲突或 D-Bus 初始化失败时，后端替换为 `NoopBackend`，不向游戏主线程抛异常。

## 从源码构建

需要 JDK 21。Gradle Wrapper 已配置国内镜像；NeoForge 依赖仓库优先使用官方 ForgeCDN，并把 NeoGradle 注入的 `maven.neoforged.net` 就地改写为 ForgeCDN。

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

产物位于 `build/libs/taskbar_jukebox-<版本号>-all.jar`。

若本机存在多个 JDK，请用 `JAVA_HOME` 明确指向 JDK 21；以更高版本（如 JDK 27）运行 Gradle 会在脚本解析阶段报 `Unsupported class file major version`。

## 故障排查

- **任务栏没有曲目信息**：确认使用 `-all` jar、已进入世界、NetMusic 正在播放；开启诊断日志后查看是否出现「已推送 SMTC」。
- **封面仍是旧图**：确认替换的是游戏运行目录下 `config/taskbar_jukebox/cover.png`，且格式为 PNG；替换后等待自动重新推送，或重启游戏。
- **日志出现 `Invalid memory access`**：初始化线程被污染，请确认使用本版本未改动的线程模型。
- **日志出现 `Module Reflect reads more than one module named com.sun.jna`**：不要再次把 JNA 打包进模组，Minecraft 本体已提供该库。
- **Linux 没有媒体控件**：确认 `echo $DBUS_SESSION_BUS_ADDRESS` 非空，且桌面支持 MPRIS；开启诊断日志后查看是否出现「Linux MPRIS 媒体会话已就绪」。
- **Linux 服务名冲突**：同一 session bus 上只允许第一个实例注册 `org.mpris.MediaPlayer2.taskbarjukebox`；后启动的实例会安静降级为空模组。
- **在 macOS 上运行**：模组会按设计空转，这是预期行为。

### Linux 验证命令

```bash
playerctl -p org.mpris.MediaPlayer2.taskbarjukebox status
playerctl -p org.mpris.MediaPlayer2.taskbarjukebox metadata
playerctl -p org.mpris.MediaPlayer2.taskbarjukebox position
busctl --user introspect org.mpris.MediaPlayer2.taskbarjukebox /org/mpris/MediaPlayer2
busctl --user get-property org.mpris.MediaPlayer2.taskbarjukebox /org/mpris/MediaPlayer2 \
  org.mpris.MediaPlayer2.Player PlaybackStatus
```

`CanControl/CanPlay/CanPause/CanSeek/CanGoNext/CanGoPrevious` 应全部为 `false`；桌面控件不应能控制游戏。

## 更新日志

### 1.2.1

- 修复服务器上背包易主后曲目不更新：把背包扔给其他玩家、对方换歌后，本端曾一直停留在旧歌，直到打开背包才刷新。改为在收到 NetMusic 的播放广播时直接缓存其中的完整曲目信息，不再依赖滞后的背包内容同步，无需打开 GUI 即可正确显示。

### 1.2.0

- 内置封面改为 Minecraft 官方安装器的草方块图标（256×256）。
- README 重新编排：新增目录导航与自定义封面图标教程，历史版本说明统一归入「更新日志」。

### 1.1.5

- 默认封面换成 Minecraft 草方块（用原版贴图绘制），构建内自带，首次启动仍释放到 `config/taskbar_jukebox/cover.png`，可随时替换。
- 修复背包放在 Curios 饰品栏时不开 GUI 就读不到曲目：现在直接遍历玩家佩戴的 Curios 槽位定位背包，并在客户端内容尚未同步时自动请求一次同步（只读请求，与悬停提示的行为一致）。

### 1.1.4

- 内置了自定义封面图，首次启动释放到 `config/taskbar_jukebox/cover.png` 并推送给系统媒体控件；仍可随时用自己的 PNG 覆盖该文件换图。
- 修复精妙背包放在世界方块上播放时，关闭 GUI 后就无法识别曲目的问题：改为从音源自带的方块坐标定位背包，不再依赖打开的界面。

### 1.1.3

- 系统媒体控件不再显示默认图标：首次启动会在 `config/taskbar_jukebox/cover.png` 生成一张透明占位图并推送给系统。把自己准备的 PNG（建议 256×256）覆盖该文件即可换图标，无需重新编译；文件变化后会自动重新推送。

### 1.1.2

- 精妙背包唱片改为直接读取 CD 的 `SONG_INFO` 组件，不再依赖本地歌曲列表，自定义 ID 的唱片也能显示正确歌名；读不到时标题留空，不再显示假的“网络音乐机”。

### 1.1.1

- 修复精妙背包（SophisticatedBackpacks）唱片机升级播放 NetMusic 唱片时无法识别的问题：按音源 URL 从 NetMusic 歌曲列表反查歌名、作者与时长。

### 1.1.0

- 读取与推送解耦为 `MediaSessionBackend`，Windows SMTC 行为保持不变。
- 新增 Linux MPRIS 后端，KDE Plasma 等桌面可显示歌曲信息与进度。
- 只读语义：Linux 所有 `Can*` 能力位为 `false`，桌面控件不能控制游戏。
- 无 session bus、服务名冲突、旧 Windows 或初始化失败时自动降级为空模组。
- 修复 dbus-java transport 在 FML plugin layer 中不可见的问题；不新增 native 依赖。

## 致谢

参考项目：`IMPSMTC`、`JavaMediaTransportControls`、`NetMusic`、`NetMusicDisplay`。感谢这些项目的作者为 Windows 媒体控件与 NetMusic 数据读取提供的实现思路。默认封面图标取自 Minecraft 官方安装器。

## 许可证

模组本体：All Rights Reserved。MDK 模板文件（如 Gradle Wrapper、`gradlew` 等）仍遵循 `TEMPLATE_LICENSE.txt` 中的 MIT 许可。





# Taskbar Jukebox

Let the melodies of NetMusic find a second home in your system media controls.

Taskbar Jukebox is a **client-side-only** mod for Minecraft 1.21.1 on NeoForge. It reads the track currently playing in NetMusic (the in-game internet radio) and forwards it, one way, to the system media controls — Windows SMTC or Linux MPRIS — making the song available to Windows taskbar media cards, taskbar lyric plugins and MPRIS desktops such as KDE Plasma. It never connects to the network and never alters playback in the game.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Custom cover art](#custom-cover-art)
- [Configuration](#configuration)
- [How it works](#how-it-works)
- [Building from source](#building-from-source)
- [Troubleshooting](#troubleshooting)
- [Changelog](#changelog)
- [Acknowledgements](#acknowledgements)
- [License](#license)

## Features

- **Read-only forwarding.** Publishes the title, artist, playback position, duration and playing/paused state. Nothing is written back and no network request is made.
- **Custom cover art.** The artwork on the system media card can be swapped at any time without recompiling — see [Custom cover art](#custom-cover-art).
- **Sophisticated Backpacks support.** A NetMusic disc played through the backpack jukebox upgrade is forwarded just like one on a network music player. It works whether the backpack rests in the world or is worn in a Curios slot, with no GUI required.
- **System controls are inert.** On Windows, play/pause/stop remain enabled so the system creates a media session, but every callback is empty. On Linux, all MPRIS `Can*` capabilities are `false` and every control method is a no-op. Clicking them has no effect on the game.
- **Linux MPRIS.** Shows title, artist, playback status and position in MPRIS desktops such as KDE Plasma; read-only and unable to control the game.
- **NetMusic is optional.** Without NetMusic installed the mod still loads cleanly; it simply has nothing to publish.
- **Capability detection.** On non-Windows/Linux systems, on Windows 8.1 or older, on Linux without `DBUS_SESSION_BUS_ADDRESS`, or when prerequisites are missing, the mod degrades into a complete no-op — no native library is loaded, no D-Bus connection is opened and no event is registered, so it cannot crash the game.
- **Diagnostic logging off by default.** Enable it from the NeoForge mod config screen, or force it with a JVM argument.
- **Prerequisites bundled.** `JavaMediaTransportControls`, dbus-java core and the JDK Unix-socket transport are embedded via jarJar; the build strips the transport's `module-info.class` so FML loads it as an automatic module in the plugin layer. Players need to install nothing extra, and Linux gains no new JNI/native dependency.

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.235** or newer
- Java **21**
- Windows 10 / Windows 11 — taskbar media controls rely on the system SMTC
- or Linux with an MPRIS-capable desktop (such as KDE Plasma) and a working session D-Bus
- other systems (macOS, Linux without a session bus, etc.) cause the mod to no-op by design
- NetMusic (optional, though you need it playing something to have anything to forward)

## Installation

1. Install NeoForge 1.21.1 (21.1.235 or newer).
2. Copy the build artifact whose name ends in **`-all`** (for example `taskbar_jukebox-1.2.1-all.jar`) into the game's `mods` folder.
3. Launch the game, join a world and start playback with NetMusic. Windows taskbar media controls, the KDE Plasma media widget and any lyric plugin will now see the track.

> The jar without the `-all` suffix is a thin artifact without the bundled prerequisites; on its own it cannot work because `JavaMediaTransportControls` is missing.

## Custom cover art

The artwork shown on the system media card can be changed freely, on both Windows and Linux.

**Default artwork.** The mod ships with a 256×256 Minecraft grass block icon, taken from the official Minecraft installer. On first launch it is extracted to `config/taskbar_jukebox/cover.png` under the game directory.

**Using your own image.**

1. Prepare a PNG image — 256×256 pixels is recommended (other sizes work too), and transparency is supported.
2. Replace `config/taskbar_jukebox/cover.png` with it.
3. No restart and no recompilation are needed. The mod detects the change and re-pushes the artwork automatically, so the taskbar media card, lyric plugins and desktop widgets update right away.

> Notes:
> - An existing `cover.png` is never overwritten on later launches, so updating the mod keeps your image.
> - To restore the default, delete the file and restart the game; the mod will extract the built-in artwork again.
> - The cover is displayed locally only — nothing is uploaded and no network request is made.

## Configuration

Open the **Mods** screen, find "Taskbar Jukebox / 窗外的音乐", enter its settings and turn on the client option **Enable verbose diagnostic logs**. Changes take effect immediately; no restart is needed.

You can also force the setting with a JVM argument at launch:

```
-Dtaskbar_jukebox.debug=true
```

Keep logging on while troubleshooting and off during normal play.

## How it works

- **Reading.** On the render thread the mod reflects into the client `SoundManager`'s sound engine, locates the NetMusic sound instance and converts its tick counters into a playback position. Title, artist and duration come from the disc data of the corresponding block entity. Because the block entity's play flag is subject to synchronization lag, the playing state is derived from whether the sound instance has stopped. For Sophisticated Backpacks, the disc's data component is read directly and the backpack is located by its world position or equipped slot, independent of any open GUI.
- **Publishing.** Reading is decoupled from publishing by a thin `MediaSessionBackend`. The Windows backend keeps the original SMTC path: the bundled native DLL calls `winrt::init_apartment()`, which binds the SMTC object to the thread that created it. The Linux backend establishes a dbus-java connection, exports `org.mpris.MediaPlayer2`, and emits `PropertiesChanged` on the same dedicated daemon thread. The render thread only reads NetMusic and hands over an immutable snapshot. The cover comes from `config/taskbar_jukebox/cover.png` and is re-pushed by the media thread whenever the file changes.
- **Fallback.** On non-Windows/Linux systems, old Windows, Linux without a session bus, service-name conflicts or D-Bus initialization failures, the backend is replaced with `NoopBackend`; no exception reaches the game thread.

## Building from source

JDK 21 is required. The Gradle wrapper is configured with domestic mirrors; NeoForge artifacts prefer the official ForgeCDN, and NeoGradle's injected `maven.neoforged.net` URL is rewritten to ForgeCDN in place.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

The artifact is written to `build/libs/taskbar_jukebox-<version>-all.jar`.

If several JDKs are installed, point `JAVA_HOME` explicitly at JDK 21. Running Gradle on a newer JDK (JDK 27, for instance) fails during script evaluation with `Unsupported class file major version`.

## Troubleshooting

- **No track in the taskbar.** Make sure you are using the `-all` jar, that you are in a world, and that NetMusic is playing. With diagnostics enabled, look for a "已推送 SMTC" line in the log.
- **The cover is still the old image.** Make sure you replaced `config/taskbar_jukebox/cover.png` under the game directory with a PNG, then wait for the automatic re-push or restart the game.
- **`Invalid memory access` in the log.** The initialization thread has been polluted; keep the threading model in this version untouched.
- **`Module Reflect reads more than one module named com.sun.jna`.** Do not bundle JNA again — Minecraft already provides it.
- **No media controls on Linux.** Check that `echo $DBUS_SESSION_BUS_ADDRESS` is non-empty and your desktop supports MPRIS; with diagnostics enabled, look for the "Linux MPRIS 媒体会话已就绪" line.
- **MPRIS service-name conflict.** Only the first instance on a session bus may own `org.mpris.MediaPlayer2.taskbarjukebox`; later instances quietly degrade to a no-op.
- **Running on macOS.** The mod intentionally does nothing; this is expected behavior.

### Linux verification

```bash
playerctl -p org.mpris.MediaPlayer2.taskbarjukebox status
playerctl -p org.mpris.MediaPlayer2.taskbarjukebox metadata
playerctl -p org.mpris.MediaPlayer2.taskbarjukebox position
busctl --user introspect org.mpris.MediaPlayer2.taskbarjukebox /org/mpris/MediaPlayer2
busctl --user get-property org.mpris.MediaPlayer2.taskbarjukebox /org/mpris/MediaPlayer2 \
  org.mpris.MediaPlayer2.Player PlaybackStatus
```

All `CanControl/CanPlay/CanPause/CanSeek/CanGoNext/CanGoPrevious` capabilities should be `false`; desktop controls must not be able to control the game.

## Changelog

### 1.2.1

- Fixed the track not updating on a server after a backpack changes hands: when you hand a backpack to another player and they play a different song, this client used to stay on the old track until the backpack GUI was opened. The mod now caches the complete track information carried by NetMusic's playback broadcast instead of relying on lagging backpack-content sync, so the correct song shows up without opening any GUI.

### 1.2.0

- The built-in cover art is now the 256×256 grass block icon from the official Minecraft installer.
- README reorganised: added a table of contents and a custom cover art guide, with past release notes gathered under "Changelog".

### 1.1.5

- The built-in cover is now a Minecraft grass block drawn from the vanilla textures. It is still extracted to `config/taskbar_jukebox/cover.png` on first launch and can be replaced at any time.
- Fixed the metadata going blank when a backpack is worn in a Curios slot with no GUI open: the mod now locates the backpack by scanning the player's equipped Curios slots directly, and requests a one-off content sync when the client has not received the backpack contents yet (a read-only request, the same action a tooltip triggers).

### 1.1.4

- Ships with a custom cover image, extracted on first launch to `config/taskbar_jukebox/cover.png` and pushed to the system media session. You can still replace that file with your own PNG at any time.
- Fixed playback from a Sophisticated Backpacks block in the world going undetected once its GUI was closed: the mod now locates the backpack via the sound's own block coordinates instead of relying on an open screen.

### 1.1.3

- The system media session no longer shows the default artwork. On first launch the mod writes a transparent placeholder to `config/taskbar_jukebox/cover.png` and pushes it to the system. Replace that file with your own PNG (256x256 recommended) to change the icon — no recompilation needed, and changes are pushed automatically.

### 1.1.2

- Sophisticated Backpacks discs are now read straight from the CD's `SONG_INFO` component rather than the local song list, so custom-ID discs show the correct title. When no title is available it stays blank instead of displaying a fake "网络音乐机".

### 1.1.1

- Fixed playback from the Sophisticated Backpacks jukebox upgrade going undetected: title, artist and duration are now resolved from NetMusic's song list by the sound's URL.

### 1.1.0

- Reading and publishing are decoupled behind `MediaSessionBackend`; Windows SMTC behaviour is unchanged.
- New Linux MPRIS backend so KDE Plasma and other MPRIS desktops can show track information and progress.
- Read-only semantics: every Linux `Can*` capability is `false`; desktop controls cannot control the game.
- Quiet fallback to a no-op on missing session bus, service-name conflicts, old Windows or initialization failures.
- Fixed the dbus-java transport not being visible in FML's plugin layer; no new native dependency.

## Acknowledgements

Reference projects: `IMPSMTC`, `JavaMediaTransportControls`, `NetMusic` and `NetMusicDisplay`. Thanks to their authors for the implementations that informed both the SMTC integration and the NetMusic data access. The default cover icon is taken from the official Minecraft installer.

## License

The mod itself is All Rights Reserved. The MDK template files (Gradle Wrapper, `gradlew`, etc.) remain under the MIT license in `TEMPLATE_LICENSE.txt`.
