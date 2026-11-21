package com.ganhaoton.Taskbar_Jukebox;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置。默认关闭详细诊断日志，玩家可在 NeoForge 模组配置页面按需开启。
 */
public final class JukeboxConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("启用详细诊断日志（默认关闭）。开启后日志会输出 [DEBUG] 前缀的排错信息。",
                    "也可通过 JVM 参数 -Dtaskbar_jukebox.debug=true/false 强制覆盖。")
            .translation("taskbar_jukebox.configuration.debug")
            .define("debug", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private JukeboxConfig() {
    }
}
