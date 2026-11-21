package com.ganhaoton.Taskbar_Jukebox;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 窗外的音乐 / Taskbar Jukebox
 *
 * <p>仅客户端：把《网络音乐机》(NetMusic) 正在播放的歌曲信息推送到 Windows 媒体播放控件。
 * 不联网，不接收任务栏控件上的暂停/切歌/拖动进度等操作。</p>
 */
@Mod(value = TaskbarJukebox.MODID, dist = Dist.CLIENT)
public final class TaskbarJukebox {
    public static final String MODID = "taskbar_jukebox";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** JVM 参数可强制覆盖配置页开关，便于排错：-Dtaskbar_jukebox.debug=true/false */
    private static final String DEBUG_PROPERTY = "taskbar_jukebox.debug";

    private static final Set<String> ONCE_KEYS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> LAST_DEBUG = new ConcurrentHashMap<>();

    public TaskbarJukebox(ModContainer container) {
        // 注册客户端配置：详细诊断日志默认关闭，玩家可在 NeoForge 模组配置页面开启。
        container.registerConfig(ModConfig.Type.CLIENT, JukeboxConfig.SPEC);

        debug("启动环境: os={}, arch={}, java={}, debug={}",
                System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), isDebug());

        // 能力探测：非 Windows / 版本过旧 / 缺少前置类时，模组退化为完全空转的「空气模组」。
        // 不注册事件、不启动 SMTC 线程、不触碰 native DLL，从根本上避免崩溃。
        if (!SystemSupport.isSupported()) {
            LOGGER.info("Taskbar Jukebox 将以空模组模式运行：{}", SystemSupport.reason());
            return;
        }

        boolean netmusicLoaded = ModList.get().isLoaded("netmusic");
        debug("NetMusic 安装状态: {}", netmusicLoaded ? "已安装" : "未安装（模组不会推送任何曲目）");

        // SMTC 的 init 与所有操作都在专用线程执行（见 SmtcManager 线程模型说明）。
        // 这里只注册客户端 tick：渲染线程读 NetMusic，投递给 SMTC 线程。
        NeoForge.EVENT_BUS.addListener(SmtcManager::onClientTick);
        LOGGER.info("Taskbar Jukebox 客户端已加载（{}），等待 NetMusic 播放信息。", SystemSupport.reason());
    }

    /**
     * 诊断日志开关（动态读取，配置页修改后立即生效，无需重启）。
     * 优先级：JVM 参数 -Dtaskbar_jukebox.debug &gt; 模组配置 &gt; 默认关闭。
     */
    public static boolean isDebug() {
        String override = System.getProperty(DEBUG_PROPERTY);
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        try {
            // 配置尚未加载时 get() 会抛异常，此时按默认（关闭）处理。
            return Boolean.TRUE.equals(JukeboxConfig.DEBUG.get());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 高频诊断日志，按 key 限频，避免每 tick 刷屏。 */
    public static void debugEvery(String key, long intervalMs, String format, Object... args) {
        if (!isDebug()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_DEBUG.get(key);
        if (last != null && now - last < intervalMs) {
            return;
        }
        LAST_DEBUG.put(key, now);
        debug(format, args);
    }

    /** 同一 key 只输出一次的诊断日志。 */
    public static void debugOnce(String key, String format, Object... args) {
        if (isDebug() && ONCE_KEYS.add("debug:" + key)) {
            debug(format, args);
        }
    }

    /** 带完整堆栈、只输出一次的诊断日志。 */
    public static void debugOnce(String key, String message, Throwable throwable) {
        if (isDebug() && ONCE_KEYS.add("debug:" + key)) {
            LOGGER.warn("[DEBUG] " + message, throwable);
        }
    }

    /** 带完整堆栈、只输出一次的错误日志。 */
    public static void errorOnce(String key, String message, Throwable throwable) {
        if (ONCE_KEYS.add("error:" + key)) {
            LOGGER.error("[Taskbar Jukebox] " + message, throwable);
        }
    }

    /** 详细诊断日志，统一带 [DEBUG] 前缀，默认关闭。 */
    public static void debug(String format, Object... args) {
        if (isDebug()) {
            LOGGER.info("[DEBUG] " + format, args);
        }
    }
}
