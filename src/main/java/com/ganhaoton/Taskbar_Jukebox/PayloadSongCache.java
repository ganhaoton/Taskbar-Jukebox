package com.ganhaoton.Taskbar_Jukebox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存服务端随 {@code NetMusicDiscPayload} 广播下来的权威 SongInfo。
 *
 * <p>背景：背包易主（如把背包扔给别人）后，原客户端不再收到背包 contents 同步，
 * 本地 {@code BackpackStorage} 副本里的 CD 永远停在换歌前，导致读到旧歌。
 * 而 payload 本身发给附近所有玩家且携带完整 SongInfo（歌名/作者/时长/URL），
 * 只是 NetMusic 客户端 onHandle 用完后就丢弃。</p>
 *
 * <p>本类由 Mixin 在 {@code NetMusicDiscPayload.handlePayload} 入口写入，
 * 读取器按 storageUuid 取用。全程只读旁路，不修改 NetMusic 行为、不联网。
 * NetMusic 未安装时 Mixin 不生效，本类保持为空，不影响任何逻辑。</p>
 */
public final class PayloadSongCache {
    private static final Map<UUID, Object> SONGS = new ConcurrentHashMap<>();

    private PayloadSongCache() {
    }

    /** 由 Mixin 调用：记录某个 storage 当前正在播放的权威 SongInfo。 */
    public static void put(UUID storageUuid, Object songInfo) {
        if (storageUuid == null || songInfo == null) {
            return;
        }
        SONGS.put(storageUuid, songInfo);
        TaskbarJukebox.debug("已缓存 NetMusicDiscPayload 的权威 SongInfo: uuid={}", storageUuid);
    }

    /** 按 storageUuid 取 SongInfo；没有返回 null。 */
    public static Object get(UUID storageUuid) {
        return storageUuid == null ? null : SONGS.get(storageUuid);
    }

    /** 清除某个 storage 的缓存（音源停止时用，避免旧信息残留）。 */
    public static void remove(UUID storageUuid) {
        if (storageUuid != null) {
            SONGS.remove(storageUuid);
        }
    }
}
