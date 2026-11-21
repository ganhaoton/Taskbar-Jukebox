package com.ganhaoton.Taskbar_Jukebox;

import io.github.selemba1000.JMTC;
import io.github.selemba1000.JMTCCallbacks;
import io.github.selemba1000.JMTCEnabledButtons;
import io.github.selemba1000.JMTCMediaType;
import io.github.selemba1000.JMTCMusicProperties;
import io.github.selemba1000.JMTCParameters;
import io.github.selemba1000.JMTCPlayingState;
import io.github.selemba1000.JMTCSettings;
import io.github.selemba1000.JMTCTimelineProperties;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把 NetMusic 的曲目信息单向推送到 Windows 媒体播放控件。
 *
 * <p>所有回调保持为空，任务栏上的暂停、切歌、拖动进度一律不作用于游戏。</p>
 *
 * <p><b>线程模型（关键）：</b>前置库 DLL 的 init() 会调用 winrt::init_apartment()
 * 并创建绑定在该线程上的 SMTC COM 对象，所有 SMTC 操作必须在同一线程执行。</p>
 * <ul>
 *   <li>该线程必须「干净」：渲染线程已初始化 COM apartment，在其上 init 会让
 *       MediaPlayer() 返回空，进而 Invalid memory access 崩溃；</li>
 *   <li>init 与所有 update 必须同线程：跨 apartment 调用会被静默丢弃，
 *       表现为 DLL 无异常但系统媒体控件不显示/显示「未连接」。</li>
 * </ul>
 * 因此本类用一条专用守护线程承载全部 JMTC 调用；渲染线程只负责读 NetMusic，
 * 再把不可变的 TrackInfo 投递给该线程。
 */
public final class SmtcManager {
    private static final JMTCMusicProperties EMPTY_PROPERTIES =
            new JMTCMusicProperties("", "", "", "", new String[0], 0, 1, null);

    /** 唯一的 SMTC 线程：init 与所有 JMTC 调用都在这里，保证 apartment 一致。 */
    private static final ExecutorService SMTC_THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Taskbar Jukebox SMTC");
        t.setDaemon(true); // 不阻止 JVM 退出
        return t;
    });

    // 以下状态只在 SMTC_THREAD 上读写；initAttempted/lastScan 在渲染线程读写。
    private static JMTC jmtc;
    private static boolean ready;
    private static boolean visible;
    private static long lastPush;
    private static String lastFingerprint = "";
    private static long lastReadback;
    private static long lastScan;
    private static boolean initAttempted;

    private SmtcManager() {
    }

    /** 渲染线程：触发一次性初始化，读取 NetMusic，并把曲目投递到 SMTC 线程。 */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!initAttempted) {
            initAttempted = true;
            // init 与后续 push 投递到同一单线程 executor，FIFO 保证 init 先执行完。
            SMTC_THREAD.execute(SmtcManager::initOnSmThread);
        }

        // 读 NetMusic 必须在渲染线程（要访问客户端世界/声音引擎）。
        long now = System.currentTimeMillis();
        if (now - lastScan < 100L) {
            return;
        }
        lastScan = now;
        final TrackInfo track = NetMusicReader.readCurrent();

        SMTC_THREAD.execute(() -> pushOnSmThread(track));
    }

    /** 在专用 SMTC 线程上初始化。 */
    private static void initOnSmThread() {
        TaskbarJukebox.debug("SMTC 初始化开始: os={}, tmpdir={}, thread={}",
                System.getProperty("os.name"), System.getProperty("java.io.tmpdir"),
                Thread.currentThread().getName());

        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            TaskbarJukebox.debug("非 Windows 系统，跳过 SMTC 初始化");
            TaskbarJukebox.LOGGER.info("当前不是 Windows，Taskbar Jukebox 不会初始化 SMTC。");
            return;
        }

        try {
            var jmtcSource = JMTC.class.getProtectionDomain().getCodeSource();
            TaskbarJukebox.debug("JavaMediaTransportControls 来源: {}",
                    jmtcSource == null ? "null" : jmtcSource.getLocation());
            try {
                Class<?> jnaClass = Class.forName("com.sun.jna.Native");
                var jnaSource = jnaClass.getProtectionDomain().getCodeSource();
                TaskbarJukebox.debug("JNA 来源: {}", jnaSource == null ? "null" : jnaSource.getLocation());
            } catch (Throwable e) {
                TaskbarJukebox.debugOnce("jna.missing", "JNA 类不可用", e);
            }
            try {
                Class<?> dbusClass = Class.forName("org.freedesktop.dbus.interfaces.DBusInterface");
                var dbusSource = dbusClass.getProtectionDomain().getCodeSource();
                TaskbarJukebox.debug("DBus 类来源: {}", dbusSource == null ? "null" : dbusSource.getLocation());
            } catch (Throwable e) {
                TaskbarJukebox.debugOnce("dbus.missing", "DBus 类不可用", e);
            }

            TaskbarJukebox.debug("创建 JMTC 实例: id=taskbar-jukebox, thread={}",
                    Thread.currentThread().getName());
            jmtc = JMTC.getInstance(new JMTCSettings("Taskbar Jukebox", "taskbar-jukebox"));
            if (jmtc == null) {
                TaskbarJukebox.LOGGER.warn("JavaMediaTransportControls 未返回播放器实例，SMTC 已禁用。");
                return;
            }

            jmtc.setMediaType(JMTCMediaType.Music);
            jmtc.setEnabled(true);
            jmtc.setCallbacks(new JMTCCallbacks()); // 全部为 null：系统按钮点了也不回写游戏
            // 播放/暂停/停止按钮保持启用：Windows 需要至少一个可用传输按钮才会
            // 在任务栏建立媒体会话卡片。按钮可点但回调为空，故系统操作被忽略。
            jmtc.setEnabledButtons(new JMTCEnabledButtons(true, true, true, false, false));
            jmtc.setParameters(new JMTCParameters(JMTCParameters.LoopStatus.None, 1d, 1d, false));
            TaskbarJukebox.debug("已配置: mediaType=Music, enabled=true, callbacks=空, buttons=播放/暂停/停止启用(空回调), playbackRate=1");

            jmtc.setMediaProperties(EMPTY_PROPERTIES);
            jmtc.setTimelineProperties(new JMTCTimelineProperties(0L, 0L, 0L, 0L));
            jmtc.setPosition(0L);
            jmtc.setPlayingState(JMTCPlayingState.CLOSED);
            jmtc.updateDisplay();
            ready = true;
            TaskbarJukebox.debug("SMTC 初始状态已推送: CLOSED, 空曲目");
            TaskbarJukebox.LOGGER.info("Windows 媒体播放控件已就绪。");
        } catch (Throwable e) {
            TaskbarJukebox.LOGGER.error("SMTC 初始化失败，模组已停用", e);
        }
    }

    /** 在专用 SMTC 线程上推送曲目。 */
    private static void pushOnSmThread(TrackInfo track) {
        if (!ready || jmtc == null) {
            TaskbarJukebox.debugEvery("smtc.notReady", 3000,
                    "SMTC 未就绪，跳过 tick: ready={}, jmtc={}", ready, jmtc != null);
            return;
        }

        long now = System.currentTimeMillis();
        if (track == null) {
            if (visible) {
                TaskbarJukebox.debug("没有活动曲目，隐藏任务栏控件");
                hide();
            }
            return;
        }

        String fingerprint = track.title() + '\u0000' + track.artist() + '\u0000'
                + track.durationMs() + '\u0000' + track.playing();
        boolean changed = !fingerprint.equals(lastFingerprint);
        if (changed) {
            TaskbarJukebox.debug("曲目元数据变化: {}", track);
        }
        if (!changed && now - lastPush < 250L) {
            return;
        }

        try {
            jmtc.setMediaProperties(new JMTCMusicProperties(
                    track.title(), track.artist(), "", "", new String[0], 0, 1, null));
            jmtc.setTimelineProperties(new JMTCTimelineProperties(
                    0L, track.durationMs(), 0L, track.durationMs()));
            jmtc.setPlayingState(track.playing() ? JMTCPlayingState.PLAYING : JMTCPlayingState.PAUSED);
            jmtc.setPosition(track.positionMs());
            jmtc.updateDisplay();
            lastFingerprint = fingerprint;
            lastPush = now;
            visible = true;
            TaskbarJukebox.debug("已推送 SMTC: title={}, artist={}, position={}ms, duration={}ms, state={}, thread={}",
                    track.title(), track.artist(), track.positionMs(), track.durationMs(),
                    track.playing() ? "PLAYING" : "PAUSED", Thread.currentThread().getName());
            dumpReadback(now);
        } catch (Throwable e) {
            TaskbarJukebox.errorOnce("smtc.push", "推送 SMTC 状态失败", e);
        }
    }

    /** 从 DLL 回读它实际持有的状态，确认写入是否真的生效。每 4 秒一次。 */
    private static void dumpReadback(long now) {
        if (now - lastReadback < 4000L) {
            return;
        }
        lastReadback = now;
        try {
            Object props = jmtc.getMediaProperties();
            String title = props instanceof JMTCMusicProperties music ? music.title : String.valueOf(props);
            var buttons = jmtc.getEnabledButtons();
            TaskbarJukebox.debug("SMTC 回读 DLL: enabled={}, state={}, mediaType={}, title={}, buttons(play/pause/stop/next/prev)={}/{}/{}/{}/{}",
                    jmtc.getEnabled(), jmtc.getPlayingState(), jmtc.getMediaType(), title,
                    buttons == null ? "null" : buttons.isPlayEnabled,
                    buttons == null ? "null" : buttons.isPauseEnabled,
                    buttons == null ? "null" : buttons.isStopEnabled,
                    buttons == null ? "null" : buttons.isNextEnabled,
                    buttons == null ? "null" : buttons.isPreviousEnabled);
        } catch (Throwable e) {
            TaskbarJukebox.errorOnce("smtc.readback", "回读 DLL 状态失败", e);
        }
    }

    private static void hide() {
        try {
            jmtc.setPlayingState(JMTCPlayingState.CLOSED);
            jmtc.setMediaProperties(EMPTY_PROPERTIES);
            jmtc.setTimelineProperties(new JMTCTimelineProperties(0L, 0L, 0L, 0L));
            jmtc.setPosition(0L);
            jmtc.updateDisplay();
            TaskbarJukebox.debug("已推送空状态并关闭 SMTC 显示");
        } catch (Throwable e) {
            TaskbarJukebox.errorOnce("smtc.hide", "隐藏 SMTC 状态失败", e);
        }
        visible = false;
        lastFingerprint = "";
    }
}
