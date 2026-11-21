package com.ganhaoton.Taskbar_Jukebox.media.windows;

import com.ganhaoton.Taskbar_Jukebox.SystemSupport;
import com.ganhaoton.Taskbar_Jukebox.TaskbarJukebox;
import com.ganhaoton.Taskbar_Jukebox.TrackInfo;
import com.ganhaoton.Taskbar_Jukebox.media.MediaSessionBackend;
import com.ganhaoton.Taskbar_Jukebox.media.PlaybackState;
import io.github.selemba1000.JMTC;
import io.github.selemba1000.JMTCCallbacks;
import io.github.selemba1000.JMTCEnabledButtons;
import io.github.selemba1000.JMTCMediaType;
import io.github.selemba1000.JMTCMusicProperties;
import io.github.selemba1000.JMTCParameters;
import io.github.selemba1000.JMTCPlayingState;
import io.github.selemba1000.JMTCSettings;
import io.github.selemba1000.JMTCTimelineProperties;

/**
 * Windows SMTC 后端：把 NetMusic 快照推送到任务栏媒体控件。
 *
 * <p>本类的方法全部由 {@code MediaSessionService} 的专用守护线程调用；
 * JMTC 的 DLL 会在该线程调用 {@code winrt::init_apartment()} 并创建 COM 对象，
 * 因此初始化与后续所有 JMTC 操作必须在同一条线程上，不能换线程。</p>
 *
 * <p>回调保持为空、播放/暂停/停止按钮保持启用，以维持现有“任务栏可建立媒体会话，
 * 但系统控件不能控制游戏”的行为。</p>
 */
public final class WindowsSmtcBackend implements MediaSessionBackend {
    private static final JMTCMusicProperties EMPTY_PROPERTIES =
            new JMTCMusicProperties("", "", "", "", new String[0], 0, 1, null);

    private JMTC jmtc;
    private boolean initialized;
    private boolean ready;
    private boolean visible;
    private long lastPush;
    private String lastFingerprint = "";
    private long lastReadback;
    private TrackInfo current;

    @Override
    public boolean isAvailable() {
        return ready;
    }

    @Override
    public boolean initialize() {
        if (initialized) {
            return ready;
        }
        initialized = true;

        TaskbarJukebox.debug("SMTC 初始化开始: os={}, tmpdir={}, thread={}",
                System.getProperty("os.name"), System.getProperty("java.io.tmpdir"),
                Thread.currentThread().getName());

        if (!SystemSupport.isWindows()) {
            TaskbarJukebox.debug("非 Windows 系统，跳过 SMTC 初始化");
            return false;
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
                return false;
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
            return true;
        } catch (Throwable e) {
            TaskbarJukebox.LOGGER.error("SMTC 初始化失败，模组已停用", e);
            ready = false;
            return false;
        }
    }

    @Override
    public void updateMetadata(TrackInfo track) {
        if (track == null) {
            clear();
            return;
        }
        current = track;
    }

    @Override
    public void updatePlayback(PlaybackState state, long positionMs) {
        if (state == PlaybackState.STOPPED) {
            clear();
            return;
        }
        if (!ready || jmtc == null) {
            TaskbarJukebox.debugEvery("smtc.notReady", 3000,
                    "SMTC 未就绪，跳过 tick: ready={}, jmtc={}", ready, jmtc != null);
            return;
        }

        TrackInfo track = current;
        if (track == null) {
            clear();
            return;
        }

        long now = System.currentTimeMillis();
        String fingerprint = track.title() + '\u0000' + track.artist() + '\u0000'
                + track.durationMs() + '\u0000' + state.name();
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
            jmtc.setPlayingState(state == PlaybackState.PLAYING
                    ? JMTCPlayingState.PLAYING : JMTCPlayingState.PAUSED);
            jmtc.setPosition(positionMs);
            jmtc.updateDisplay();
            lastFingerprint = fingerprint;
            lastPush = now;
            visible = true;
            TaskbarJukebox.debug("已推送 SMTC: title={}, artist={}, position={}ms, duration={}ms, state={}, thread={}",
                    track.title(), track.artist(), positionMs, track.durationMs(),
                    state, Thread.currentThread().getName());
            dumpReadback(now);
        } catch (Throwable e) {
            TaskbarJukebox.errorOnce("smtc.push", "推送 SMTC 状态失败", e);
        }
    }

    @Override
    public void clear() {
        current = null;
        if (!ready || jmtc == null) {
            return;
        }
        if (!visible) {
            lastFingerprint = "";
            return;
        }
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

    @Override
    public void close() {
        ready = false;
        visible = false;
        current = null;
        lastFingerprint = "";

        JMTC old = jmtc;
        jmtc = null;
        if (old == null) {
            return;
        }
        try {
            old.setPlayingState(JMTCPlayingState.CLOSED);
            old.setMediaProperties(EMPTY_PROPERTIES);
            old.setTimelineProperties(new JMTCTimelineProperties(0L, 0L, 0L, 0L));
            old.setPosition(0L);
            old.updateDisplay();
            TaskbarJukebox.debug("SMTC 后端已关闭");
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("smtc.close", "关闭 SMTC 时出错", e);
        }
    }

    /** 从 DLL 回读它实际持有的状态，确认写入是否真的生效。每 4 秒一次。 */
    private void dumpReadback(long now) {
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
}
