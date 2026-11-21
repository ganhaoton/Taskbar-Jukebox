package com.ganhaoton.Taskbar_Jukebox.media;

/**
 * 与平台无关的播放状态。各后端自行映射为 SMTC/MPRIS 的表示。
 */
public enum PlaybackState {
    PLAYING,
    PAUSED,
    STOPPED
}
