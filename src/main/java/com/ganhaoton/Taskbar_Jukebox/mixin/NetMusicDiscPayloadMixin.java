package com.ganhaoton.Taskbar_Jukebox.mixin;

import com.ganhaoton.Taskbar_Jukebox.PayloadSongCache;
import com.github.tartaricacid.netmusic.compat.sbackpack.NetMusicDiscPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端处理 NetMusicDiscPayload 时，把服务端广播下来的权威 SongInfo 旁路缓存。
 *
 * <p>NetMusic 自己的 onHandle 只用 songUrl 与 songTime 造音源，歌名/作者被丢弃；
 * 背包易主后本客户端又收不到背包 contents 同步，导致从背包 CD 读到旧歌。
 * 这里在 payload 入口把完整 SongInfo 按 storageUuid 存一份，读取器即可拿到权威数据。</p>
 *
 * <p>只加一行旁路写入，不改变 NetMusic 的任何行为。目标类是第三方模组类，
 * 因此 {@code remap = false}。NetMusic 未安装时该 mixin 会被安静跳过。</p>
 */
@Mixin(value = NetMusicDiscPayload.class, remap = false)
public class NetMusicDiscPayloadMixin {
    @Inject(method = "handlePayload", at = @At("HEAD"), remap = false)
    private static void taskbarJukebox$cacheSongInfo(NetMusicDiscPayload payload,
                                                     IPayloadContext context,
                                                     CallbackInfo ci) {
        try {
            PayloadSongCache.put(payload.storgeUuid(), payload.songInfo());
        } catch (Throwable ignored) {
            // 旁路缓存失败绝不能影响 NetMusic 的正常处理
        }
    }
}
