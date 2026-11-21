package com.ganhaoton.Taskbar_Jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只读取 NetMusic 客户端内存里的播放状态，不修改、不联网、不依赖 NetMusic 的编译期 API。
 *
 * <p>进度取自 NetMusicSound.tick（20 tick/秒），歌名与作者取自对应方块实体里的 CD 数据组件。
 * 这样比读取方块实体里同步不及时的剩余倒计时准确。</p>
 *
 * <p>注意 1.21.1 的 SoundEngine.tickingSounds 是 {@code List<TickableSoundInstance>}，
 * 不是 Map；instanceToChannel 才是 Map。两个来源都收集，避免遗漏。</p>
 */
public final class NetMusicReader {
    private static final String SOUND_CLASS = "com.github.tartaricacid.netmusic.client.audio.NetMusicSound";
    private static final String PLAYER_TE_CLASS = "com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer";
    private static final String CD_CLASS = "com.github.tartaricacid.netmusic.item.ItemMusicCD";

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static Method songInfoMethod;
    private static boolean songInfoMethodResolved;

    private NetMusicReader() {
    }

    /** 返回当前正在播放（或最后暂停）的 NetMusic 曲目；没有则返回 null。 */
    public static TrackInfo readCurrent() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            TaskbarJukebox.debugEvery("reader.noLevel", 2000, "未进入世界，跳过 NetMusic 读取");
            return null;
        }

        SoundManager soundManager = mc.getSoundManager();
        TaskbarJukebox.debugEvery("reader.enter", 1500,
                "开始读取 NetMusic: level={}, soundManager={}",
                mc.level.dimension().location(), soundManager.getClass().getName());

        Object engine = field(soundManager, "soundEngine");
        if (engine == null) {
            TaskbarJukebox.debugOnce("reader.engine",
                    "无法读取 SoundManager.soundEngine: soundManager={}",
                    soundManager.getClass().getName());
            return null;
        }

        // 两个来源都收集：tickingSounds(List) 是 tick 中的可 tick 音源，
        // instanceToChannel(Map) 是全部在发声的音源。NetMusicSound 属于前者。
        Set<Object> sounds = new LinkedHashSet<>();
        collect(sounds, field(engine, "tickingSounds"), "tickingSounds");
        collect(sounds, field(engine, "instanceToChannel"), "instanceToChannel");
        if (sounds.isEmpty()) {
            TaskbarJukebox.debugEvery("reader.emptyAll", 2000,
                    "声音系统里没有任何可收集音源: engine={}", engine.getClass().getName());
            return null;
        }

        Object currentSound = null;
        int bestTick = -1;
        int candidates = 0;
        int stopped = 0;
        for (Object sound : sounds) {
            if (sound == null || !SOUND_CLASS.equals(sound.getClass().getName())) {
                continue;
            }
            candidates++;
            if (sound instanceof AbstractTickableSoundInstance instance && instance.isStopped()) {
                stopped++;
                TaskbarJukebox.debugEvery("reader.stopped", 1500,
                        "忽略已停止的 NetMusic 音源: {}", sound.getClass().getName());
                continue;
            }
            Object value = field(sound, "tick");
            if (value instanceof Integer tick && tick > bestTick) {
                bestTick = tick;
                currentSound = sound;
            }
        }

        if (currentSound == null) {
            TaskbarJukebox.debugEvery("reader.empty", 1500,
                    "没有活动 NetMusic 音源: 总音源={}, NetMusic音源={}, 已停止={}",
                    sounds.size(), candidates, stopped);
            return null;
        }

        int tick = bestTick;
        Object durationValue = field(currentSound, "tickTimes");
        int tickTimes = durationValue instanceof Integer value ? value : 0;
        long durationMs = tickTimes * 50L;
        long positionMs = tick * 50L;

        TaskbarJukebox.debugEvery("reader.sound", 1500,
                "选中音源: class={}, tick={}, tickTimes={}, 总音源={}, NetMusic音源={}",
                currentSound.getClass().getName(), tick, tickTimes, sounds.size(), candidates);

        String title = "";
        String artist = "";
        // 音源仍在 tick 且未停止，就说明在播放。方块实体 isPlay 同步有滞后，只作日志参考，
        // 不再用它决定 PLAYING/PAUSED，避免“明明在播放却显示未播放”。
        boolean playing = !(currentSound instanceof AbstractTickableSoundInstance instance && instance.isStopped());

        Object posValue = field(currentSound, "pos");
        if (!(posValue instanceof BlockPos pos)) {
            TaskbarJukebox.debugOnce("reader.posMissing", "音源缺少 BlockPos 坐标: {}", posValue);
        } else {
            BlockEntity blockEntity = mc.level.getBlockEntity(pos);
            TaskbarJukebox.debugEvery("reader.be", 1500,
                    "坐标 {} 对应的方块实体: {}（期望 {}）",
                    pos, blockEntity == null ? "null" : blockEntity.getClass().getName(), PLAYER_TE_CLASS);

            if (blockEntity != null && PLAYER_TE_CLASS.equals(blockEntity.getClass().getName())) {
                Object flag = call(blockEntity, "isPlay");
                TaskbarJukebox.debugEvery("reader.isPlay", 1500,
                        "方块实体 isPlay={}（仅参考，播放状态以音源存活为准）", flag);

                Object inventory = call(blockEntity, "getPlayerInv");
                Object stack = inventory == null ? null : call(inventory, "getStackInSlot", 0);
                TaskbarJukebox.debugEvery("reader.cd", 1500,
                        "唱片 inventory={}, stack={}",
                        inventory == null ? "null" : inventory.getClass().getName(),
                        stack instanceof ItemStack item ? item.getDisplayName().getString() : stack);

                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    Object songInfo = getSongInfo(itemStack);
                    TaskbarJukebox.debugEvery("reader.songInfo", 1500, "SongInfo={}", songInfo);
                    if (songInfo != null) {
                        Object name = field(songInfo, "songName");
                        title = name instanceof String value ? value : "";
                        artist = joinArtists(field(songInfo, "artists"));
                        Object seconds = field(songInfo, "songTime");
                        if (seconds instanceof Integer value && value > 0) {
                            durationMs = value * 1000L;
                            TaskbarJukebox.debugEvery("reader.duration", 1500,
                                    "用 SongInfo.songTime 覆盖时长: {} 秒", value);
                        }
                    }
                }
            }
        }

        TrackInfo result = new TrackInfo(title, artist, positionMs, durationMs, playing);
        TaskbarJukebox.debugEvery("reader.result", 1500, "读取结果: {}", result);
        return result;
    }

    /** 把 Iterable（List/Set）或 Map 的键收集进 out，兼容两种字段实现。 */
    private static void collect(Set<Object> out, Object source, String fieldName) {
        if (source instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (key != null) {
                    out.add(key);
                }
            }
            TaskbarJukebox.debugEvery("reader.collect." + fieldName, 1500,
                    "来源 {} 是 Map，收集键 {} 个", fieldName, map.size());
        } else if (source instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element != null) {
                    out.add(element);
                }
            }
            TaskbarJukebox.debugEvery("reader.collect." + fieldName, 1500,
                    "来源 {} 是 Collection，收集元素 {} 个", fieldName, collection.size());
        } else if (source != null) {
            TaskbarJukebox.debugOnce("reader.collectType." + fieldName,
                    "来源 {} 类型未知: {}", fieldName, source.getClass().getName());
        }
    }

    private static Object getSongInfo(ItemStack stack) {
        if (!songInfoMethodResolved) {
            songInfoMethodResolved = true;
            try {
                // getSongInfo 是 ItemMusicCD 外部类的静态方法，不在 ItemMusicCD$SongInfo 上。
                Class<?> clazz = Class.forName(CD_CLASS);
                songInfoMethod = clazz.getMethod("getSongInfo", ItemStack.class);
                TaskbarJukebox.debugOnce("songInfo.resolve", "定位到 NetMusic SongInfo API: {}", songInfoMethod);
            } catch (Throwable e) {
                TaskbarJukebox.debugOnce("songInfo.resolveFail", "无法定位 NetMusic 的 SongInfo API", e);
            }
        }
        if (songInfoMethod == null) {
            return null;
        }
        try {
            return songInfoMethod.invoke(null, stack);
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("songInfo.invokeFail", "读取 NetMusic 歌曲信息失败", e);
            return null;
        }
    }

    private static String joinArtists(Object value) {
        if (value instanceof Collection<?> artists) {
            return String.join(" / ", artists.stream().map(String::valueOf).toList());
        }
        TaskbarJukebox.debugOnce("artists.type", "artists 字段不是集合: {}", value);
        return "";
    }

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        String key = target.getClass().getName() + '#' + name;
        try {
            Field field = FIELDS.computeIfAbsent(key, k -> {
                try {
                    Field found = target.getClass().getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (ReflectiveOperationException e) {
                    TaskbarJukebox.debugOnce("field." + key, "反射字段不存在: " + key, e);
                    return null;
                }
            });
            return field == null ? null : field.get(target);
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("fieldGet." + key, "读取字段失败: " + key, e);
            return null;
        }
    }

    private static Object call(Object target, String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
            }
            Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, args);
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("call." + name, "调用 NetMusic 方法失败: " + name, e);
            return null;
        }
    }
}
