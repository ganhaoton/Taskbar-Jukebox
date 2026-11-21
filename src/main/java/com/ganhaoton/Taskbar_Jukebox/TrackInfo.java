package com.ganhaoton.Taskbar_Jukebox;

/**
 * 从 NetMusic 客户端音源中读出的一首歌。时间统一为毫秒。
 */
public record TrackInfo(String title, String artist, long positionMs, long durationMs, boolean playing) {
    public TrackInfo {
        title = title == null || title.isEmpty() ? "网络音乐机" : title;
        artist = artist == null ? "" : artist;
        durationMs = Math.max(0L, durationMs);
        positionMs = Math.max(0L, Math.min(positionMs, durationMs));
    }
}
