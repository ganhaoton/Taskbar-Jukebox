package com.ganhaoton.Taskbar_Jukebox.media.linux;

import com.ganhaoton.Taskbar_Jukebox.SystemSupport;
import com.ganhaoton.Taskbar_Jukebox.TaskbarJukebox;
import com.ganhaoton.Taskbar_Jukebox.TrackInfo;
import com.ganhaoton.Taskbar_Jukebox.media.CoverArt;
import com.ganhaoton.Taskbar_Jukebox.media.MediaSessionBackend;
import com.ganhaoton.Taskbar_Jukebox.media.PlaybackState;
import com.ganhaoton.Taskbar_Jukebox.media.linux.mpris.MprisMediaPlayer2;
import com.ganhaoton.Taskbar_Jukebox.media.linux.mpris.MprisMediaPlayer2Player;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.connections.transports.TransportBuilder;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linux MPRIS D-Bus 后端。
 *
 * <p>只读单向：所有 Can* 能力位为 false，Player 方法为空实现；不控制游戏播放。
 * 服务固定为 {@code org.mpris.MediaPlayer2.taskbarjukebox}，对象路径
 * {@code /org/mpris/MediaPlayer2}。</p>
 *
 * <p>初始化与更新由 {@code MediaSessionService} 的专用线程调用；D-Bus 的
 * Get/GetAll 由 dbus-java 接收线程调用，因此共享状态使用不可变 map 与
 * volatile 字段。</p>
 */
public final class LinuxMprisBackend implements MediaSessionBackend,
        MprisMediaPlayer2, MprisMediaPlayer2Player, Properties {

    private volatile DBusConnection connection;
    private volatile boolean available;
    private volatile boolean initialized;
    private volatile boolean closed;

    private volatile String playbackStatus = "Stopped";
    private volatile Map<String, Variant<?>> metadata = Map.of();
    private volatile long lengthUs;
    private volatile long basePositionUs;
    private volatile long anchorNanos;
    private volatile boolean playing;

    // 只在媒体线程读写。
    private boolean metadataDirty;
    private boolean statusDirty;

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public synchronized boolean initialize() {
        if (initialized) {
            return available;
        }
        initialized = true;

        if (closed || !SystemSupport.isLinux()) {
            return false;
        }
        String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (address == null || address.isBlank()) {
            TaskbarJukebox.debug("缺少 DBUS_SESSION_BUS_ADDRESS，跳过 MPRIS 初始化");
            return false;
        }

        try {
            TaskbarJukebox.debug("DBus transport providers: {}", TransportBuilder.getRegisteredBusTypes());
            DBusConnection newConnection = DBusConnectionBuilder.forSessionBus()
                    .withShared(false)
                    .withWeakReferences(false)
                    .build();
            connection = newConnection;

            DBus dbus = newConnection.getRemoteObject(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            UInt32 reply = dbus.RequestName(
                    MprisNames.SERVICE,
                    new UInt32(DBus.DBUS_NAME_FLAG_DO_NOT_QUEUE));
            int replyCode = reply.intValue();
            if (replyCode != DBus.DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER
                    && replyCode != DBus.DBUS_REQUEST_NAME_REPLY_ALREADY_OWNER) {
                TaskbarJukebox.LOGGER.info("MPRIS 服务名 {} 已被占用，Taskbar Jukebox 将保持空转。",
                        MprisNames.SERVICE);
                closeConnection();
                return false;
            }

            newConnection.exportObject(MprisNames.OBJECT_PATH, this);
            available = true;
            TaskbarJukebox.LOGGER.info("Linux MPRIS 媒体会话已就绪（{}）。", MprisNames.SERVICE);
            TaskbarJukebox.debug("MPRIS 已导出: name={}, path={}, unique={}",
                    MprisNames.SERVICE, MprisNames.OBJECT_PATH, newConnection.getUniqueName());
            return true;
        } catch (Throwable t) {
            TaskbarJukebox.LOGGER.warn("Linux MPRIS 初始化失败，将降级为空模组: {}", t.toString());
            TaskbarJukebox.debugOnce("mpris.init", "MPRIS 初始化失败", t);
            closeConnection();
            return false;
        }
    }

    @Override
    public void updateMetadata(TrackInfo track) {
        if (!available || track == null) {
            return;
        }
        try {
            String artUrl = track.artUrl().isBlank() ? CoverArt.artUrl() : track.artUrl();
            Map<String, Variant<?>> built = buildMetadata(track, artUrl);
            long newLengthUs = toMicros(track.durationMs());
            if (!built.equals(metadata) || newLengthUs != lengthUs || CoverArt.stampChanged()) {
                metadata = built;
                lengthUs = newLengthUs;
                metadataDirty = true;
            }
        } catch (Throwable t) {
            degrade("metadata", "构建 MPRIS 元数据失败", t);
        }
    }

    @Override
    public void updatePlayback(PlaybackState state, long positionMs) {
        if (!available) {
            return;
        }
        try {
            long newBase = toMicros(positionMs);
            if (lengthUs > 0) {
                newBase = Math.min(newBase, lengthUs);
            }
            basePositionUs = Math.max(0L, newBase);
            anchorNanos = System.nanoTime();
            playing = state == PlaybackState.PLAYING;

            String newStatus = statusFor(state);
            if (!newStatus.equals(playbackStatus)) {
                playbackStatus = newStatus;
                statusDirty = true;
            }

            if (metadataDirty || statusDirty) {
                Map<String, Variant<?>> changed = new LinkedHashMap<>();
                if (metadataDirty) {
                    changed.put("Metadata", new Variant<>(metadata, "a{sv}"));
                }
                if (statusDirty) {
                    changed.put("PlaybackStatus", new Variant<>(playbackStatus));
                }
                sendPropertiesChanged(changed);
                metadataDirty = false;
                statusDirty = false;
            }
        } catch (Throwable t) {
            degrade("playback", "更新 MPRIS 播放状态失败", t);
        }
    }

    @Override
    public void clear() {
        if (!available) {
            return;
        }
        try {
            boolean metadataChanged = !metadata.isEmpty();
            boolean statusChanged = !"Stopped".equals(playbackStatus);

            metadata = Map.of();
            lengthUs = 0L;
            basePositionUs = 0L;
            anchorNanos = System.nanoTime();
            playing = false;
            metadataDirty = false;
            statusDirty = false;

            if (metadataChanged || statusChanged) {
                playbackStatus = "Stopped";
                Map<String, Variant<?>> changed = new LinkedHashMap<>();
                if (metadataChanged) {
                    changed.put("Metadata", new Variant<>(metadata, "a{sv}"));
                }
                if (statusChanged) {
                    changed.put("PlaybackStatus", new Variant<>("Stopped"));
                }
                sendPropertiesChanged(changed);
            } else {
                playbackStatus = "Stopped";
            }
        } catch (Throwable t) {
            degrade("clear", "清空 MPRIS 状态失败", t);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        metadata = Map.of();
        playbackStatus = "Stopped";
        playing = false;
        lengthUs = 0L;
        basePositionUs = 0L;
        anchorNanos = System.nanoTime();
        closeConnection();
    }

    private void closeConnection() {
        DBusConnection old = connection;
        connection = null;
        available = false;
        if (old == null) {
            return;
        }
        try {
            old.unExportObject(MprisNames.OBJECT_PATH);
        } catch (Throwable t) {
            TaskbarJukebox.debugOnce("mpris.unexport", "取消导出 MPRIS 对象失败", t);
        }
        try {
            old.releaseBusName(MprisNames.SERVICE);
        } catch (Throwable t) {
            TaskbarJukebox.debugOnce("mpris.release", "释放 MPRIS 服务名失败", t);
        }
        try {
            old.close();
        } catch (Throwable t) {
            TaskbarJukebox.debugOnce("mpris.close", "关闭 MPRIS 连接失败", t);
        }
    }

    private void degrade(String key, String message, Throwable throwable) {
        TaskbarJukebox.errorOnce("mpris." + key, message, throwable);
        available = false;
        closeConnection();
    }

    private void sendPropertiesChanged(Map<String, Variant<?>> changed) {
        DBusConnection current = connection;
        if (current == null || changed.isEmpty()) {
            return;
        }
        try {
            current.sendMessage(new Properties.PropertiesChanged(
                    MprisNames.OBJECT_PATH,
                    MprisNames.PLAYER_INTERFACE,
                    changed,
                    List.of()));
        } catch (Throwable t) {
            degrade("signal", "发送 MPRIS PropertiesChanged 失败", t);
        }
    }

    private static String statusFor(PlaybackState state) {
        return switch (state) {
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            case STOPPED -> "Stopped";
        };
    }

    private static long toMicros(long millis) {
        if (millis <= 0L) {
            return 0L;
        }
        if (millis > Long.MAX_VALUE / 1000L) {
            return Long.MAX_VALUE;
        }
        return millis * 1000L;
    }

    private long positionUs() {
        long pos = basePositionUs;
        if (playing) {
            long elapsedNanos = System.nanoTime() - anchorNanos;
            if (elapsedNanos > 0L) {
                long elapsedUs = elapsedNanos / 1000L;
                if (Long.MAX_VALUE - pos < elapsedUs) {
                    pos = Long.MAX_VALUE;
                } else {
                    pos += elapsedUs;
                }
            }
        }
        if (lengthUs > 0L && pos > lengthUs) {
            pos = lengthUs;
        }
        return Math.max(0L, pos);
    }

    private static Map<String, Variant<?>> buildMetadata(TrackInfo track, String artUrl) {
        Map<String, Variant<?>> result = new LinkedHashMap<>();
        result.put("mpris:trackid", new Variant<>(new DBusPath(trackId(track))));
        result.put("mpris:length", new Variant<>(toMicros(track.durationMs())));
        result.put("xesam:title", new Variant<>(track.title()));
        List<String> artists = track.artist().isBlank() ? List.of() : List.of(track.artist());
        result.put("xesam:artist", new Variant<>(artists, "as"));
        result.put("xesam:album", new Variant<>(track.album()));
        if (!artUrl.isBlank()) {
            result.put("mpris:artUrl", new Variant<>(artUrl));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String trackId(TrackInfo track) {
        String provided = track.trackId();
        if (isValidObjectPath(provided)) {
            return provided;
        }
        String basis = track.title() + '\u0000' + track.artist() + '\u0000'
                + track.album() + '\u0000' + track.durationMs();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(basis.getBytes(StandardCharsets.UTF_8));
            return MprisNames.TRACK_PATH_PREFIX + HexFormat.of().formatHex(hash, 0, 16);
        } catch (Throwable t) {
            return MprisNames.TRACK_PATH_PREFIX + Integer.toHexString(basis.hashCode());
        }
    }

    private static boolean isValidObjectPath(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/') {
            return false;
        }
        String body = path.substring(1);
        if (body.isEmpty()) {
            return false;
        }
        for (String element : body.split("/", -1)) {
            if (element.isEmpty()) {
                return false;
            }
            char first = element.charAt(0);
            if (!(isAsciiLetter(first) || first == '_')) {
                return false;
            }
            for (int i = 1; i < element.length(); i++) {
                char c = element.charAt(i);
                if (!(isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_')) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A> A Get(String interfaceName, String propertyName) {
        Variant<?> value = GetAll(interfaceName).get(propertyName);
        if (value == null) {
            throw new DBusExecutionException("Unknown MPRIS property: "
                    + interfaceName + "." + propertyName);
        }
        return (A) value;
    }

    @Override
    public <A> void Set(String interfaceName, String propertyName, A value) {
        // 只读：即使客户端绕过 CanControl 直接 Set，也不改变任何状态。
        TaskbarJukebox.debugEvery("mpris.set", 5000,
                "忽略 MPRIS Set: {}.{}", interfaceName, propertyName);
    }

    @Override
    public Map<String, Variant<?>> GetAll(String interfaceName) {
        Map<String, Variant<?>> values = new LinkedHashMap<>();
        if (MprisNames.ROOT_INTERFACE.equals(interfaceName)) {
            values.put("Identity", new Variant<>(MprisNames.IDENTITY));
            values.put("DesktopEntry", new Variant<>(MprisNames.DESKTOP_ENTRY));
            values.put("SupportedUriSchemes", new Variant<>(new String[0], "as"));
            values.put("SupportedMimeTypes", new Variant<>(new String[0], "as"));
            values.put("HasTrackList", new Variant<>(false));
            values.put("CanQuit", new Variant<>(false));
            values.put("CanRaise", new Variant<>(false));
            values.put("CanSetFullscreen", new Variant<>(false));
            values.put("Fullscreen", new Variant<>(false));
        } else if (MprisNames.PLAYER_INTERFACE.equals(interfaceName)) {
            values.put("PlaybackStatus", new Variant<>(playbackStatus));
            values.put("LoopStatus", new Variant<>("None"));
            values.put("Rate", new Variant<>(1.0d));
            values.put("Shuffle", new Variant<>(false));
            values.put("Metadata", new Variant<>(metadata, "a{sv}"));
            values.put("Volume", new Variant<>(1.0d));
            values.put("Position", new Variant<>(positionUs()));
            values.put("MinimumRate", new Variant<>(1.0d));
            values.put("MaximumRate", new Variant<>(1.0d));
            values.put("CanControl", new Variant<>(false));
            values.put("CanPlay", new Variant<>(false));
            values.put("CanPause", new Variant<>(false));
            values.put("CanSeek", new Variant<>(false));
            values.put("CanGoNext", new Variant<>(false));
            values.put("CanGoPrevious", new Variant<>(false));
        }
        return values;
    }

    @Override
    public String getObjectPath() {
        return MprisNames.OBJECT_PATH;
    }

    // MPRIS 根接口：只读，不退出、不唤起。
    @Override
    public void Raise() {
    }

    @Override
    public void Quit() {
    }

    // MPRIS Player：只读，所有控制方法为空实现。
    @Override
    public void Next() {
    }

    @Override
    public void Previous() {
    }

    @Override
    public void Pause() {
    }

    @Override
    public void PlayPause() {
    }

    @Override
    public void Stop() {
    }

    @Override
    public void Play() {
    }

    @Override
    public void Seek(long offset) {
    }

    @Override
    public void SetPosition(DBusPath trackId, long position) {
    }

    @Override
    public void OpenUri(String uri) {
    }
}
