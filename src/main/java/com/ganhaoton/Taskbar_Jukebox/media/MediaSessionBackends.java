package com.ganhaoton.Taskbar_Jukebox.media;

import com.ganhaoton.Taskbar_Jukebox.SystemSupport;
import com.ganhaoton.Taskbar_Jukebox.media.linux.LinuxMprisBackend;
import com.ganhaoton.Taskbar_Jukebox.media.windows.WindowsSmtcBackend;

/**
 * 运行时后端选择。
 *
 * <p>平台分支放在独立方法中，确保 Linux 分支不会实例化 Windows 后端，
 * Windows 分支也不会实例化 Linux 后端。JVM 只在对应方法执行到 {@code new}
 * 时才加载后端类，从而避免错误平台加载 JMTC/JNA 或 dbus-java。</p>
 */
public final class MediaSessionBackends {
    private MediaSessionBackends() {
    }

    public static MediaSessionBackend create() {
        return switch (SystemSupport.platform()) {
            case WINDOWS -> createWindows();
            case LINUX -> createLinux();
            case OTHER -> new NoopBackend(SystemSupport.reason());
        };
    }

    private static MediaSessionBackend createWindows() {
        if (SystemSupport.isWindowsSmtcSupported()) {
            return new WindowsSmtcBackend();
        }
        return new NoopBackend(SystemSupport.reason());
    }

    private static MediaSessionBackend createLinux() {
        if (SystemSupport.isLinuxMprisSupported()) {
            return new LinuxMprisBackend();
        }
        return new NoopBackend(SystemSupport.reason());
    }
}
