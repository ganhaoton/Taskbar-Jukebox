package com.ganhaoton.Taskbar_Jukebox.media.linux;

/**
 * MPRIS 服务标识。保持固定名/固定路径，便于 KDE 等桌面识别。
 */
public final class MprisNames {
    public static final String SERVICE = "org.mpris.MediaPlayer2.taskbarjukebox";
    public static final String OBJECT_PATH = "/org/mpris/MediaPlayer2";
    public static final String ROOT_INTERFACE = "org.mpris.MediaPlayer2";
    public static final String PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player";
    public static final String TRACK_PATH_PREFIX = "/org/taskbarjukebox/track/";
    public static final String IDENTITY = "Taskbar Jukebox";
    public static final String DESKTOP_ENTRY = "";

    private MprisNames() {
    }
}
