package com.ganhaoton.Taskbar_Jukebox;

import java.util.Locale;

/**
 * 运行环境能力的门面。
 *
 * <p>内部按平台拆分探测：Windows 只看 SMTC 需要的系统版本与 JMTC/JNA/DBus 类；
 * Linux 只看 session bus 地址与 dbus-java 核心/transport 类。探测失败时返回
 * 对应原因，由 {@code MediaSessionBackends} 选择 {@code NoopBackend}。</p>
 *
 * <p>这里刻意只做 {@code Class.forName(..., initialize=false)} 与系统属性/环境变量
 * 检查，不调用 {@code JMTC.getInstance()}，也不建立 D-Bus 连接；真正的初始化
 * 由后端在专用线程上执行。</p>
 */
public final class SystemSupport {
    public enum Platform {
        WINDOWS,
        LINUX,
        OTHER
    }

    private static final String OS_NAME = System.getProperty("os.name", "");
    private static final Platform PLATFORM = detectPlatform(OS_NAME);

    private static final String REQ_JNA = "com.sun.jna.Native";
    private static final String REQ_JMTC = "io.github.selemba1000.JMTC";
    private static final String REQ_DBUS_INTERFACE = "org.freedesktop.dbus.interfaces.DBusInterface";
    private static final String REQ_DBUS_CORE = "org.freedesktop.dbus.connections.impl.DBusConnectionBuilder";
    private static final String REQ_DBUS_TRANSPORT = "org.freedesktop.dbus.transport.jre.NativeTransportProvider";

    private static final boolean WINDOWS_SMTC_SUPPORTED;
    private static final String WINDOWS_SMTC_REASON;
    private static final boolean LINUX_MPRIS_SUPPORTED;
    private static final String LINUX_MPRIS_REASON;

    static {
        boolean winSupported = false;
        String winReason;

        if (PLATFORM == Platform.WINDOWS) {
            String version = System.getProperty("os.version", "");
            Integer major = parseMajor(version);
            // Win10/Win11/Server2016+ 的 os.version 主版本为 10；Win8.1=6.3、Win7=6.1。
            if (major != null && major < 10) {
                winReason = "Windows 版本过低，SMTC 需要 Windows 10/11 (os.version=" + version + ")";
            } else {
                String missing = firstMissingClass(REQ_JNA, REQ_JMTC, REQ_DBUS_INTERFACE);
                if (missing != null) {
                    winReason = "缺少 SMTC 前置类: " + missing;
                } else {
                    winSupported = true;
                    winReason = "SMTC 环境可用 (os.name=" + OS_NAME + ", os.version=" + version + ")";
                }
            }
        } else {
            winReason = "非 Windows 系统 (os.name=" + OS_NAME + ")";
        }

        WINDOWS_SMTC_SUPPORTED = winSupported;
        WINDOWS_SMTC_REASON = winReason;

        boolean linuxSupported = false;
        String linuxReason;

        if (PLATFORM == Platform.LINUX) {
            String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
            if (address == null || address.isBlank()) {
                linuxReason = "缺少 DBUS_SESSION_BUS_ADDRESS，无法连接 session bus";
            } else {
                String missing = firstMissingClass(REQ_DBUS_CORE, REQ_DBUS_TRANSPORT);
                if (missing != null) {
                    linuxReason = "缺少 MPRIS 前置类: " + missing;
                } else {
                    linuxSupported = true;
                    linuxReason = "MPRIS 环境可用 (os.name=" + OS_NAME + ")";
                }
            }
        } else {
            linuxReason = "非 Linux 系统 (os.name=" + OS_NAME + ")";
        }

        LINUX_MPRIS_SUPPORTED = linuxSupported;
        LINUX_MPRIS_REASON = linuxReason;
    }

    private SystemSupport() {
    }

    public static Platform platform() {
        return PLATFORM;
    }

    public static boolean isWindows() {
        return PLATFORM == Platform.WINDOWS;
    }

    public static boolean isLinux() {
        return PLATFORM == Platform.LINUX;
    }

    public static boolean isWindowsSmtcSupported() {
        return WINDOWS_SMTC_SUPPORTED;
    }

    public static boolean isLinuxMprisSupported() {
        return LINUX_MPRIS_SUPPORTED;
    }

    /** 当前平台是否存在可用的媒体会话后端。 */
    public static boolean isSupported() {
        return WINDOWS_SMTC_SUPPORTED || LINUX_MPRIS_SUPPORTED;
    }

    /** 当前平台的能力探测说明，用于启动日志。 */
    public static String reason() {
        return switch (PLATFORM) {
            case WINDOWS -> WINDOWS_SMTC_REASON;
            case LINUX -> LINUX_MPRIS_REASON;
            case OTHER -> "非 Windows/Linux 系统 (os.name=" + OS_NAME + ")";
        };
    }

    private static Platform detectPlatform(String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return Platform.WINDOWS;
        }
        if (lower.contains("linux")) {
            return Platform.LINUX;
        }
        return Platform.OTHER;
    }

    /** 解析 os.version 的主版本号；格式异常时返回 null（不误杀，交由后续初始化兜底）。 */
    private static Integer parseMajor(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        String[] parts = version.split("\\.");
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 检查前置类是否存在。使用 initialize=false，避免触发 JMTC 静态初始化进入 native。
     * 返回第一个缺失类的描述，全部存在则返回 null。
     */
    private static String firstMissingClass(String... names) {
        ClassLoader loader = SystemSupport.class.getClassLoader();
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable t) {
                return name + " (" + t.getClass().getSimpleName() + ")";
            }
        }
        return null;
    }
}
