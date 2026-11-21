package com.ganhaoton.Taskbar_Jukebox.media.linux.mpris;

import com.ganhaoton.Taskbar_Jukebox.media.linux.MprisNames;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusProperties;
import org.freedesktop.dbus.annotations.DBusProperty;
import org.freedesktop.dbus.interfaces.DBusInterface;

/**
 * MPRIS 根接口。能力位全部只读，且不允许退出/唤起。
 */
@DBusInterfaceName(MprisNames.ROOT_INTERFACE)
@DBusProperties({
        @DBusProperty(name = "Identity", type = String.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "DesktopEntry", type = String.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "SupportedUriSchemes", type = String[].class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "SupportedMimeTypes", type = String[].class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "HasTrackList", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanQuit", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanRaise", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "CanSetFullscreen", type = Boolean.class, access = DBusProperty.Access.READ),
        @DBusProperty(name = "Fullscreen", type = Boolean.class, access = DBusProperty.Access.READ)
})
public interface MprisMediaPlayer2 extends DBusInterface {
    void Raise();

    void Quit();
}
