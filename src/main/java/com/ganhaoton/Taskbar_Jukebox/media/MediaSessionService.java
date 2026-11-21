package com.ganhaoton.Taskbar_Jukebox.media;

import com.ganhaoton.Taskbar_Jukebox.NetMusicReader;
import com.ganhaoton.Taskbar_Jukebox.TaskbarJukebox;
import com.ganhaoton.Taskbar_Jukebox.TrackInfo;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 渲染线程与媒体会话后端之间的隔离层。
 *
 * <p>渲染线程只做两件事：按 100ms 节流读取 NetMusic，然后把不可变快照投递到
 * 专用守护线程。初始化后端、更新属性、发送平台信号全部在该线程按 FIFO 执行。</p>
 *
 * <p>后端初始化失败或运行中抛出异常时，本服务关闭旧后端并替换为
 * {@link NoopBackend}，同时停止后续读取投递，保证游戏不受影响。</p>
 */
public final class MediaSessionService {
    private static final long SCAN_INTERVAL_MS = 100L;

    private final ExecutorService mediaThread;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean initScheduled = new AtomicBoolean();

    private volatile MediaSessionBackend backend;
    private volatile boolean disabled;
    private long lastScan;

    public MediaSessionService(MediaSessionBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend");
        }
        this.backend = backend;
        this.mediaThread = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Taskbar Jukebox Media");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 渲染线程入口：只读取和投递，不触碰任何平台 API。 */
    public void onClientTick(ClientTickEvent.Post event) {
        if (closed.get() || disabled) {
            return;
        }

        if (initScheduled.compareAndSet(false, true)) {
            mediaThread.execute(this::initializeOnMediaThread);
        }

        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;

        TrackInfo track;
        try {
            track = NetMusicReader.readCurrent();
        } catch (Throwable t) {
            TaskbarJukebox.errorOnce("media.read", "读取 NetMusic 状态失败", t);
            track = null;
        }

        final TrackInfo snapshot = track;
        try {
            mediaThread.execute(() -> applyOnMediaThread(snapshot));
        } catch (RejectedExecutionException e) {
            // 服务正在关闭，忽略。
        }
    }

    private void initializeOnMediaThread() {
        if (closed.get() || disabled) {
            return;
        }
        try {
            if (!backend.initialize()) {
                fallbackToNoop("后端初始化失败");
                return;
            }
            TaskbarJukebox.debug("媒体会话后端已就绪: {}", backend.getClass().getName());
        } catch (Throwable t) {
            TaskbarJukebox.errorOnce("media.init", "初始化媒体会话后端失败，降级为空模组", t);
            fallbackToNoop("初始化异常: " + t.getClass().getSimpleName());
        }
    }

    private void applyOnMediaThread(TrackInfo track) {
        if (closed.get() || disabled) {
            return;
        }
        try {
            if (track == null) {
                backend.clear();
            } else {
                backend.updateMetadata(track);
                backend.updatePlayback(
                        track.playing() ? PlaybackState.PLAYING : PlaybackState.PAUSED,
                        track.positionMs());
            }
        } catch (Throwable t) {
            TaskbarJukebox.errorOnce("media.update", "更新媒体会话失败，降级为空模组", t);
            fallbackToNoop("更新异常: " + t.getClass().getSimpleName());
        }
    }

    private void fallbackToNoop(String reason) {
        if (disabled) {
            return;
        }
        disabled = true;
        MediaSessionBackend old = backend;
        backend = new NoopBackend(reason);
        if (old != null) {
            try {
                old.close();
            } catch (Throwable t) {
                TaskbarJukebox.debugOnce("media.fallback.close", "关闭失败的后端时出错", t);
            }
        }
        TaskbarJukebox.LOGGER.info("Taskbar Jukebox 已降级为空模组：{}", reason);
    }

    /** 客户端停止/卸载时调用；不等待专用线程结束。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        disabled = true;
        try {
            mediaThread.execute(() -> {
                try {
                    backend.close();
                } catch (Throwable t) {
                    TaskbarJukebox.debugOnce("media.close", "关闭媒体会话后端失败", t);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 已经关闭。
        } finally {
            mediaThread.shutdown();
        }
    }
}
