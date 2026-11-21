package com.ganhaoton.Taskbar_Jukebox;

import java.util.Locale;

/**
 * 启动时的运行环境能力探测，只做「不会触碰 native 代码」的静态检查。
 *
 * <p>本模组的 SMTC 推送依赖 Windows 10/11 的 SystemMediaTransportControls，
 * 以及前置库的三个 Java 类（JNA、JavaMediaTransportControls、DBus 接口）。
 * 只要有一项不满足，就不能加载/初始化 native DLL——否则在非 Windows 或
 * 过旧系统上会出现 UnsatisfiedLinkError / Invalid memory access 乃至崩溃。</p>
 *
 * <p>探测失败时，主类不会注册任何事件监听器、不会启动 SMTC 线程，模组退化为
 * 完全空转的「空气模组」，既不会崩溃也不会产生任何日志刷屏。</p>
 *
 * <p>注意：这里刻意不调用 {@code JMTC.getInstance()}，因为那会真正进入 native
 * init()；能力探测必须停留在 Class.forName(..., initialize=false) 层面。</p>
 */
public final class SystemSupport {
    /** 能力探测是否通过；在类首次加载时计算一次并缓存。 */
    public static final boolean SUPPORTED;
    /** 探测结论的可读说明，用于日志。 */
    public static final String REASON;

    private static final String REQ_JNA = "com.sun.jna.Native";
    private static final String REQ_JMTC = "io.github.selemba1000.JMTC";
    private static final String REQ_DBUS = "org.freedesktop.dbus.interfaces.DBusInterface";

    static {
        boolean supported = false;
        String reason;

        String os = System.getProperty("os.name", "");
        String version = System.getProperty("os.version", "");
        String osLower = os.toLowerCase(Locale.ROOT);

        if (!osLower.contains("win")) {
            reason = "非 Windows 系统 (os.name=" + os + ")";
        } else {
            Integer major = parseMajor(version);
            // Win10/Win11/Server2016+ 的 os.version 主版本为 10；Win8.1=6.3、Win7=6.1。
            // SMTC 任务栏媒体卡片需要 Windows 10 1809+，旧版直接降级为空模组。
            if (major != null && major < 10) {
                reason = "Windows 版本过低，SMTC 需要 Windows 10/11 (os.version=" + version + ")";
            } else {
                String missing = firstMissingClass();
                if (missing != null) {
                    reason = "缺少 SMTC 前置类: " + missing;
                } else {
                    supported = true;
                    reason = "SMTC 环境可用 (os.name=" + os + ", os.version=" + version + ")";
                }
            }
        }

        SUPPORTED = supported;
        REASON = reason;
    }

    private SystemSupport() {
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    public static String reason() {
        return REASON;
    }

    /** 解析 os.version 的主版本号；格式异常时返回 null（不误杀，交由后续 native 初始化兜底）。 */
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
    private static String firstMissingClass() {
        ClassLoader loader = SystemSupport.class.getClassLoader();
        for (String name : new String[]{REQ_JNA, REQ_JMTC, REQ_DBUS}) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable t) {
                return name + " (" + t.getClass().getSimpleName() + ")";
            }
        }
        return null;
    }
}
