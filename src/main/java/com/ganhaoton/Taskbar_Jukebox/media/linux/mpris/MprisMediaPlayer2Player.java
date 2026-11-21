package com.ganhaoton.Taskbar_Jukebox.media.linux.mpris;

import com.ganhaoton.Taskbar_Jukebox.media.linux.MprisNames;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperties;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * MPRIS Player 接口。
 *
 * <p>所有能力位均为 {@code false}，方法实现为空；桌面控件即使绕过能力位调用，
 * 也不会回写游戏。{@code Position} 在 Get 时动态计算，单位为微秒。</p>
 */
@DBusInterfaceName(MprisNames.PLAYER_INTERFACE)
@DBusProperties({
        @DBusProperty(name = "PlaybackStatus", type = String.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "LoopStatus", type = String.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "Rate", type = Double.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "Shuffle", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "Metadata", type = MprisMetadataType.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "Volume", type = Double.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "Position", type = Long.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "MinimumRate", type = Double.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "MaximumRate", type = Double.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanControl", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanPlay", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanPause", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanSeek", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanGoNext", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanGoPrevious", type = Boolean.class, access = DBusProperty.Access.READ)
})
public interface MprisMediaPlayer2Player extends DBusInterface {
    void Next();

    void Previous();

    void Pause();

    void PlayPause();

    void Stop();

    void Play();

    void Seek(long offset);

    void SetPosition(DBusPath trackId, long position);

    void OpenUri(String uri);
}
