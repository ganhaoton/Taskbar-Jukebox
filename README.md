# 窗外的音乐 / Taskbar Jukebox

让《网络音乐机》的旋律，在系统媒体控件上继续流淌。

这是一个 Minecraft 1.21.1 NeoForge 的**纯客户端**模组：它读取《网络音乐机》（NetMusic）正在播放的曲目信息，单向推送到系统媒体控件（Windows SMTC / Linux MPRIS），供 Windows 任务栏媒体卡片、任务栏歌词插件以及 KDE Plasma 等 MPRIS 桌面媒体控件使用。模组不联网、不改动游戏播放行为。

## 功能特性

- **只读推送**：推送歌名、作者、播放进度、时长与播放/暂停状态，不写入、不联网。
- **忽略系统操作**：Windows 任务栏上的播放、暂停、停止按钮保持可用以便系统建立媒体会话，但回调全部为空；Linux MPRIS 的 `CanControl/CanPlay/CanPause/CanSeek/CanGoNext/CanGoPrevious` 均为 `false`。点按不会回写游戏。
- **Linux MPRIS**：在支持 MPRIS 的桌面（如 KDE Plasma）显示歌名、作者、播放状态与进度；只读、不可控制游戏。
- **软依赖 NetMusic**：未安装 NetMusic 时模组照常加载，只是不推送任何曲目。
- **系统能力探测**：非 Windows/Linux、Windows 8.1 及更早版本、Linux 缺少 `DBUS_SESSION_BUS_ADDRESS`，或缺少前置类时，模组自动降级为完全空转的「空气模组」，不加载原生库、不建立 D-Bus 连接、不注册事件，从根本上避免崩溃。
- **诊断日志默认关闭**：可在 NeoForge 模组配置页面开启，也可用 JVM 参数强制覆盖。
- **前置库随包携带**：`JavaMediaTransportControls` 与 dbus-java core 通过 jarJar 内嵌；dbus-java 的 JDK Unix socket transport 类平铺进模组 jar，避免 FML 嵌套库加载时 provider 不可见。玩家无需单独安装前置，Linux 不新增 JNI/native 依赖。

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
2. 将构建产物中**带 `-all` 后缀**的 jar（例如 `taskbar_jukebox-1.0.7-all.jar`）放入游戏目录的 `mods` 文件夹。
3. 启动游戏，进入世界并用 NetMusic 播放音乐；Windows 任务栏媒体控件、KDE Plasma 媒体控件或歌词插件即可读到曲目信息。

> 注意：不带 `-all` 后缀的是不含前置库的瘦 jar，单独使用会因缺少 `JavaMediaTransportControls` 而无法工作。

## 配置

打开 `模组 (Mods)` 页面，找到「窗外的音乐 / Taskbar Jukebox」，进入配置，将客户端项 **启用详细诊断日志** 打开即可。配置修改即时生效，无需重启。

也可以启动前追加 JVM 参数强制覆盖配置页：

```
-Dtaskbar_jukebox.debug=true
```

排查问题时建议开启日志，正常使用时保持关闭。

## 工作原理

- **读取**：在渲染线程通过反射读取客户端 `SoundManager` 的声音引擎，定位 NetMusic 的音源，取播放刻度与总刻度换算为进度；歌名、作者、时长则来自对应方块实体中唱片的数据。方块实体上的播放标记存在同步延迟，因此播放状态以音源是否停止为准。
- **推送**：读取与推送已解耦为 `MediaSessionBackend`。Windows 后端把原 SMTC 逻辑收进 `WindowsSmtcBackend`；前置库的原生 DLL 通过 `winrt::init_apartment()` 创建与线程绑定的 SMTC 对象，因此初始化与全部推送操作都在同一条专用守护线程上执行。Linux 后端 `LinuxMprisBackend` 在同一类专用线程上建立 dbus-java 连接、导出 `org.mpris.MediaPlayer2` 对象并发送 `PropertiesChanged`；渲染线程只负责读取并把不可变的曲目快照投递过去。
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

## 致谢

参考项目：`IMPSMTC`、`JavaMediaTransportControls`、`NetMusic`、`NetMusicDisplay`。感谢这些项目的作者为 Windows 媒体控件与 NetMusic 数据读取提供的实现思路。

## 许可证

模组本体：All Rights Reserved。MDK 模板文件（如 Gradle Wrapper、`gradlew` 等）仍遵循 `TEMPLATE_LICENSE.txt` 中的 MIT 许可。





# Taskbar Jukebox

Let the melodies of NetMusic find a second home in your system media controls.

Taskbar Jukebox is a **client-side-only** mod for Minecraft 1.21.1 on NeoForge. It reads the track currently playing in NetMusic (the in-game internet radio) and forwards it, one way, to the system media controls — Windows SMTC or Linux MPRIS — making the song available to Windows taskbar media cards, taskbar lyric plugins and MPRIS desktops such as KDE Plasma. It never connects to the network and never alters playback in the game.

## Features

- **Read-only forwarding.** Publishes the title, artist, playback position, duration and playing/paused state. Nothing is written back and no network request is made.
- **System controls are inert.** On Windows, play/pause/stop remain enabled so the system creates a media session, but every callback is empty. On Linux, all MPRIS `Can*` capabilities are `false` and every control method is a no-op. Clicking them has no effect on the game.
- **Linux MPRIS.** Shows title, artist, playback status and position in MPRIS desktops such as KDE Plasma; read-only and unable to control the game.
- **NetMusic is optional.** Without NetMusic installed the mod still loads cleanly; it simply has nothing to publish.
- **Capability detection.** On non-Windows/Linux systems, on Windows 8.1 or older, on Linux without `DBUS_SESSION_BUS_ADDRESS`, or when prerequisites are missing, the mod degrades into a complete no-op — no native library is loaded, no D-Bus connection is opened and no event is registered, so it cannot crash the game.
- **Diagnostic logging off by default.** Enable it from the NeoForge mod config screen, or force it with a JVM argument.
- **Prerequisites bundled.** `JavaMediaTransportControls` and dbus-java core are embedded via jarJar; dbus-java's JDK Unix-socket transport classes are flattened into the mod jar so the provider stays visible under FML's nested-library loading. Players need to install nothing extra, and Linux gains no new JNI/native dependency.

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
2. Copy the build artifact whose name ends in **`-all`** (for example `taskbar_jukebox-1.0.7-all.jar`) into the game's `mods` folder.
3. Launch the game, join a world and start playback with NetMusic. Windows taskbar media controls, the KDE Plasma media widget and any lyric plugin will now see the track.

> The jar without the `-all` suffix is a thin artifact without the bundled prerequisites; on its own it cannot work because `JavaMediaTransportControls` is missing.

## Configuration

Open the **Mods** screen, find "Taskbar Jukebox / 窗外的音乐", enter its settings and turn on the client option **Enable verbose diagnostic logs**. Changes take effect immediately; no restart is needed.

You can also force the setting with a JVM argument at launch:

```
-Dtaskbar_jukebox.debug=true
```

Keep logging on while troubleshooting and off during normal play.

## How it works

- **Reading.** On the render thread the mod reflects into the client `SoundManager`'s sound engine, locates the NetMusic sound instance and converts its tick counters into a playback position. Title, artist and duration come from the disc data of the corresponding block entity. Because the block entity's play flag is subject to synchronization lag, the playing state is derived from whether the sound instance has stopped.
- **Publishing.** Reading is decoupled from publishing by a thin `MediaSessionBackend`. The Windows backend keeps the original SMTC path: the bundled native DLL calls `winrt::init_apartment()`, which binds the SMTC object to the thread that created it. The Linux backend establishes a dbus-java connection, exports `org.mpris.MediaPlayer2`, and emits `PropertiesChanged` on the same dedicated daemon thread. The render thread only reads NetMusic and hands over an immutable snapshot.
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

## Acknowledgements

Reference projects: `IMPSMTC`, `JavaMediaTransportControls`, `NetMusic` and `NetMusicDisplay`. Thanks to their authors for the implementations that informed both the SMTC integration and the NetMusic data access.

## License

The mod itself is All Rights Reserved. The MDK template files (Gradle Wrapper, `gradlew`, etc.) remain under the MIT license in `TEMPLATE_LICENSE.txt`.
