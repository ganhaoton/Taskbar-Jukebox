package com.ganhaoton.Taskbar_Jukebox.media;

import com.ganhaoton.Taskbar_Jukebox.TaskbarJukebox;
import com.ganhaoton.Taskbar_Jukebox.TrackInfo;

/**
 * 空后端：用于非 Windows/Linux、旧 Windows、缺少前置类、无 session bus
 * 或初始化失败后的安全降级。它不持有任何 native/D-Bus 资源。
 */
public final class NoopBackend implements MediaSessionBackend {
    private final String reason;

    public NoopBackend(String reason) {
        this.reason = reason == null ? "" : reason;
    }

    /** 降级原因，供启动日志使用。 */
    public String reason() {
        return reason;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean initialize() {
        TaskbarJukebox.debug("空后端初始化（无系统媒体会话）: {}", reason);
        return true;
    }

    @Override
    public void updateMetadata(TrackInfo track) {
        // 空实现。
    }

    @Override
    public void updatePlayback(PlaybackState state, long positionMs) {
        // 空实现。
    }

    @Override
    public void clear() {
        // 空实现。
    }

    @Override
    public void close() {
        // 空实现。
    }
}
