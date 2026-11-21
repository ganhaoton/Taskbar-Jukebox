package com.ganhaoton.Taskbar_Jukebox;

/**
 * 从 NetMusic 客户端音源中读出的一首歌。时间统一为毫秒。
 *
 * <p>{@code album}/{@code trackId}/{@code artUrl} 为空字符串表示当前读取不到。
 * Linux MPRIS 后端会在 {@code trackId} 为空时自行生成合法的 D-Bus object path；
 * Windows SMTC 后端忽略这三个字段。</p>
 */
public record TrackInfo(
        String title,
        String artist,
        String album,
        String trackId,
        String artUrl,
        long positionMs,
        long durationMs,
        boolean playing) {

    /** 兼容原有 NetMusicReader 调用：没有 album/trackId/artUrl。 */
    public TrackInfo(String title, String artist, long positionMs, long durationMs, boolean playing) {
        this(title, artist, "", "", "", positionMs, durationMs, playing);
    }

    public TrackInfo {
        title = title == null || title.isEmpty() ? "网络音乐机" : title;
        artist = artist == null ? "" : artist;
        album = album == null ? "" : album;
        trackId = trackId == null ? "" : trackId;
        artUrl = artUrl == null ? "" : artUrl;
        durationMs = Math.max(0L, durationMs);
        positionMs = Math.max(0L, Math.min(positionMs, durationMs));
    }
}
