# 窗外的音乐 / Taskbar Jukebox

让《网络音乐机》的旋律，在 Windows 任务栏上继续流淌。

这是一个 Minecraft 1.21.1 NeoForge 的**纯客户端**模组：它读取《网络音乐机》（NetMusic）正在播放的曲目信息，单向推送到 Windows 媒体播放控件，供任务栏媒体卡片与各类任务栏歌词插件使用。模组不联网、不改动游戏播放行为。

## 功能特性

- **只读推送**：推送歌名、作者、播放进度、时长与播放/暂停状态，不写入、不联网。
- **忽略系统操作**：任务栏上的播放、暂停、停止按钮保持可用以便 Windows 建立媒体会话，但回调全部为空——点按不会回写游戏。
- **软依赖 NetMusic**：未安装 NetMusic 时模组照常加载，只是不推送任何曲目。
- **系统能力探测**：非 Windows、Windows 8.1 及更早版本，或缺少 SMTC 前置类时，模组自动降级为完全空转的「空气模组」，不加载原生库、不注册事件，从根本上避免崩溃。
- **诊断日志默认关闭**：可在 NeoForge 模组配置页面开启，也可用 JVM 参数强制覆盖。
- **前置库随包携带**：`JavaMediaTransportControls` 与所需 DBus 依赖通过 jarJar 内嵌，玩家无需单独安装前置。

## 环境要求

- Minecraft **1.21.1**
- NeoForge **21.1.235** 或更高
- Java **21**
- Windows 10 / Windows 11（任务栏媒体控件依赖系统 SMTC；其他系统会自动空转）
- 《网络音乐机》NetMusic（可选，但需要有它播放音乐才有内容可推送）

## 安装

1. 安装 NeoForge 1.21.1（21.1.235+）。
2. 将构建产物中**带 `-all` 后缀**的 jar（例如 `taskbar_jukebox-1.0.7-all.jar`）放入游戏目录的 `mods` 文件夹。
3. 启动游戏，进入世界并用 NetMusic 播放音乐；任务栏媒体控件或歌词插件即可读到曲目信息。

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
- **推送**：前置库的原生 DLL 通过 `winrt::init_apartment()` 创建与线程绑定的 SMTC 对象，因此初始化与全部推送操作都在同一条专用守护线程上执行；渲染线程只负责读取并把不可变的曲目快照投递过去。这样既避免跨线程调用被静默丢弃，也避免在其他线程初始化导致的原生崩溃。

## 从源码构建

需要 JDK 21。Gradle Wrapper 已配置国内镜像，依赖仓库亦优先使用国内镜像。

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

产物位于 `build/libs/taskbar_jukebox-<版本号>-all.jar`。

若本机存在多个 JDK，请用 `JAVA_HOME` 明确指向 JDK 21；以更高版本（如 JDK 27）运行 Gradle 会在脚本解析阶段报 `Unsupported class file major version`。

## 故障排查

- **任务栏没有曲目信息**：确认使用 `-all` jar、已进入世界、NetMusic 正在播放；开启诊断日志后查看是否出现「已推送 SMTC」。
- **日志出现 `Invalid memory access`**：初始化线程被污染，请确认使用本版本未改动的线程模型。
- **日志出现 `Module Reflect reads more than one module named com.sun.jna`**：不要再次把 JNA 打包进模组，Minecraft 本体已提供该库。
- **在 Linux / macOS 上运行**：模组会按设计空转，这是预期行为。

## 致谢

参考项目：`IMPSMTC`、`JavaMediaTransportControls`、`NetMusic`、`NetMusicDisplay`。感谢这些项目的作者为 Windows 媒体控件与 NetMusic 数据读取提供的实现思路。

## 许可证

All Rights Reserved。详见 `TEMPLATE_LICENSE.txt`。





# Taskbar Jukebox

Let the melodies of NetMusic find a second home on the Windows taskbar.

Taskbar Jukebox is a **client-side-only** mod for Minecraft 1.21.1 on NeoForge. It reads the track currently playing in NetMusic (the in-game internet radio) and forwards it, one way, to the Windows media playback controls — making the song available to taskbar media cards and taskbar lyric plugins. It never connects to the network and never alters playback in the game.

## Features

- **Read-only forwarding.** Publishes the title, artist, playback position, duration and playing/paused state. Nothing is written back and no network request is made.
- **System controls are inert.** Play, pause and stop remain enabled so Windows will create a media session, but every callback is empty — clicking them has no effect on the game.
- **NetMusic is optional.** Without NetMusic installed the mod still loads cleanly; it simply has nothing to publish.
- **Capability detection.** On non-Windows systems, on Windows 8.1 or older, or when the SMTC prerequisites are missing, the mod degrades into a complete no-op — no native library is loaded and no event is registered, so it cannot crash the game.
- **Diagnostic logging off by default.** Enable it from the NeoForge mod config screen, or force it with a JVM argument.
- **Prerequisites bundled.** `JavaMediaTransportControls` and its DBus dependencies are embedded via jarJar; players need to install nothing extra.

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.235** or newer
- Java **21**
- Windows 10 / Windows 11 — taskbar media controls rely on the system SMTC; other systems cause the mod to no-op by design
- NetMusic (optional, though you need it playing something to have anything to forward)

## Installation

1. Install NeoForge 1.21.1 (21.1.235 or newer).
2. Copy the build artifact whose name ends in **`-all`** (for example `taskbar_jukebox-1.0.7-all.jar`) into the game's `mods` folder.
3. Launch the game, join a world and start playback with NetMusic. The taskbar media controls and any lyric plugin will now see the track.

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
- **Publishing.** The bundled native DLL calls `winrt::init_apartment()`, which binds the SMTC object to the thread that created it. Initialization and every subsequent update therefore run on one dedicated daemon thread; the render thread only reads NetMusic and hands over an immutable snapshot. This avoids both silently discarded cross-thread calls and native crashes caused by initializing on an unrelated thread.

## Building from source

JDK 21 is required. The Gradle wrapper and dependency repositories are configured with domestic mirrors.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew build
```

The artifact is written to `build/libs/taskbar_jukebox-<version>-all.jar`.

If several JDKs are installed, point `JAVA_HOME` explicitly at JDK 21. Running Gradle on a newer JDK (JDK 27, for instance) fails during script evaluation with `Unsupported class file major version`.

## Troubleshooting

- **No track in the taskbar.** Make sure you are using the `-all` jar, that you are in a world, and that NetMusic is playing. With diagnostics enabled, look for a "已推送 SMTC" line in the log.
- **`Invalid memory access` in the log.** The initialization thread has been polluted; keep the threading model in this version untouched.
- **`Module Reflect reads more than one module named com.sun.jna`.** Do not bundle JNA again — Minecraft already provides it.
- **Running on Linux or macOS.** The mod intentionally does nothing; this is expected behavior.

## Acknowledgements

Reference projects: `IMPSMTC`, `JavaMediaTransportControls`, `NetMusic` and `NetMusicDisplay`. Thanks to their authors for the implementations that informed both the SMTC integration and the NetMusic data access.

## License

All Rights Reserved. See `TEMPLATE_LICENSE.txt`.
