package com.ganhaoton.Taskbar_Jukebox.media.linux.mpris;

import org.freedesktop.dbus.TypeRef;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/**
 * 让 dbus-java 的 introspection 把 MPRIS Metadata 声明为 {@code a{sv}}。
 * 裸 {@code Map.class} 会被默认声明成 {@code a{vv}}。
 */
public interface MprisMetadataType extends TypeRef<Map<String, Variant<?>>> {
}
