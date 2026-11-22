package com.ganhaoton.Taskbar_Jukebox;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 只读地从精妙背包（SophisticatedBackpacks）里取出正在播放的 NetMusic 唱片信息。
 *
 * <p>NetMusic 的背包播放音源 {@code NetMusicBackpackSound} 自身不携带 SongInfo，
 * 服务端发下来的完整 SongInfo 在客户端被丢弃了。可靠的数据源只有背包里那张 CD 的
 * SONG_INFO data component（随容器/背包同步到客户端）。</p>
 *
 * <p>链路：SoundInstance → {@code StorageSoundHandler.storageSounds} 反查 storageUuid
 * → 找到该 storage 的 IStorageWrapper → UpgradeHandler → JukeboxUpgradeWrapper
 * → getDisc() → {@code ItemMusicCD.getSongInfo(stack)}。</p>
 *
 * <p>整个类只按类名反射，不编译期依赖 NetMusic / SophisticatedCore / SophisticatedBackpacks；
 * 任一类不存在或结构变化时安静返回 null，绝不让模组崩溃。</p>
 */
final class BackpackSongInfo {
    private static final String STORAGE_SOUND_HANDLER =
            "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StorageSoundHandler";
    private static final String STORAGE_CONTAINER_MENU =
            "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase";
    private static final String BACKPACK_WRAPPER =
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper";
    private static final String BACKPACK_BLOCK_ENTITY =
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity";
    private static final String JUKEBOX_WRAPPER =
            "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper";
    private static final String SONG_INFO_COMPONENT_OWNER =
            "net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents";
    private static final String WRAPPER_REPOSITORY =
            "net.p3pp3rf1y.sophisticatedcore.inventory.StorageWrapperRepository";
    private static final String CD_CLASS = "com.github.tartaricacid.netmusic.item.ItemMusicCD";
    // 饰品栏（Curios）与背包内容同步请求。全部按类名反射，软依赖缺失时静默跳过。
    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String CURIOS_ITEM_HANDLER =
            "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
    private static final String ITEM_HANDLER_MODIFIABLE =
            "net.neoforged.neoforge.items.IItemHandlerModifiable";
    private static final String BACKPACK_ITEM_CLIENT =
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItemClient";

    /** 每个 uuid 上次请求背包内容的时间，节流避免每 tick 发包。 */
    private static final Map<UUID, Long> LAST_CONTENT_REQUEST = new HashMap<>();
    private static final long CONTENT_REQUEST_COOLDOWN_MS = 3000L;

    /** 已读到 SongInfo 的 uuid → url，用于反查失败时的兜底匹配。 */
    private static final Map<UUID, String> UUID_URL_CACHE = new HashMap<>();

    private BackpackSongInfo() {
    }

    /** 返回与 sound 对应唱片的 SongInfo 对象；拿不到返回 null。 */
    static Object find(Object sound) {
        try {
            // 音源(entity != null)表示背包被玩家佩戴/携带（Curios 饰品栏等），
            // 此时音源自带的 x/y/z 是玩家坐标，不可能是背包方块，跳过世界方块路径。
            Object entity = fieldOf(sound, "entity");
            Object song = entity == null ? fromWorldBlock(sound) : null;
            if (song != null) {
                return song;
            }

            Object uuid = findStorageUuid(sound);
            if (uuid == null) {
                uuid = findUuidByUrl(sound); // Map 反查失败时用 URL 兜底（音源实例被替换的情况）
            }
            if (uuid == null) {
                TaskbarJukebox.debugOnce("bp.uuidMissing",
                        "StorageSoundHandler 里没有该音源对应的 storageUuid");
                return null;
            }
            rememberUrl(uuid, sound);

            song = fromOpenContainer(uuid);
            if (song == null) {
                song = fromUuidCache(uuid);
            }
            if (song == null) {
                song = fromInventory(uuid);
            }
            if (song == null) {
                // 饰品栏背包：GUI 无关，直接遍历玩家 Curios 槽位。
                song = fromCurios(uuid, entity instanceof LivingEntity living ? living : null);
            }
            if (song == null) {
                // 最短兜底：直接扫容器槽位里任何带 SONG_INFO 的 NetMusic CD。
                // 玩家打开背包时唱片就在槽位里，不依赖 storageUuid / wrapper 链路。
                song = fromAnyCdInContainer();
            }
            if (song == null) {
                TaskbarJukebox.debugEvery("bp.noDisc", 2000,
                        "按 storageUuid={} 未找到正在播放的唱片 CD", uuid);
            }
            return song;
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.error", "读取精妙背包唱片信息失败", e);
            return null;
        }
    }

    /**
     * 主路径：从音源坐标找世界上的背包方块实体。
     *
     * <p>{@code NetMusicBackpackSound} 对方块背包会把自己放在 {@code pos}，该坐标保存在
     * {@code SoundInstance} 的 x/y/z 字段里。因此 GUI 关闭后仍能靠坐标拿到
     * {@code BackpackBlockEntity} → wrapper → CD，不需要 UUID 缓存或打开的容器。</p>
     */
    private static Object fromWorldBlock(Object sound) {
        try {
            Object x = fieldOf(sound, "x");
            Object y = fieldOf(sound, "y");
            Object z = fieldOf(sound, "z");
            if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) {
                TaskbarJukebox.debugOnce("bp.posField", "音源缺少 x/y/z 坐标字段: {}", sound.getClass().getName());
                return null;
            }
            BlockPos pos = new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
            BlockEntity blockEntity = Minecraft.getInstance().level == null
                    ? null : Minecraft.getInstance().level.getBlockEntity(pos);
            if (blockEntity == null || !isInstance(BACKPACK_BLOCK_ENTITY, blockEntity)) {
                TaskbarJukebox.debugEvery("bp.beMiss", 2000,
                        "坐标 {} 不是背包方块实体: {}", pos,
                        blockEntity == null ? "null" : blockEntity.getClass().getName());
                return null;
            }
            Object wrapper = invoke(blockEntity, "getBackpackWrapper");
            Object song = songFromWrapper(wrapper);
            if (song != null) {
                TaskbarJukebox.debugEvery("bp.source.worldBlock", 2000,
                        "从世界背包方块 {} 读到唱片 SongInfo（GUI 无关）", pos);
            }
            return song;
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.worldBlock", "从世界背包方块读取唱片失败", e);
            return null;
        }
    }

    /**
     * 饰品栏背包路径：反射 {@code CuriosApi.getCuriosInventory(player).getEquippedCurios()}
     * 遍历所有已佩戴的 Curio 槽位，按 storageUuid 找背包 ItemStack。与 GUI 是否打开无关。
     *
     * <p>客户端可能尚未收到背包内容（contents 未同步），此时 wrapper 里没有唱片机升级，
     * 读不到 CD。这种情况下调用 SophisticatedBackpacks 自己的 {@code sendContentsRequest}
     * 请求服务端同步（节流），下一 tick 内容到达后即可读到，全程只读、不改游戏状态。</p>
     *
     * @param entity 音源绑定的玩家（优先），为空时退回本地玩家
     */
    private static Object fromCurios(Object uuid, LivingEntity entity) {
        try {
            Class<?> apiClass = Class.forName(CURIOS_API);
            Method getInventory = apiClass.getMethod("getCuriosInventory", LivingEntity.class);
            LivingEntity wearer = entity != null ? entity : Minecraft.getInstance().player;
            if (wearer == null) {
                return null;
            }
            Object optional = getInventory.invoke(null, wearer);
            Object handler = optional instanceof Optional<?> opt ? opt.orElse(null) : null;
            if (handler == null) {
                TaskbarJukebox.debugEvery("bp.curios.noHandler", 2000,
                        "CuriosApi 未返回该玩家的饰品栏 handler");
                return null;
            }

            // 用接口上的 Method（public），避免实现类非 public 时 invoke 抛 IllegalAccessException
            Method equippedMethod = Class.forName(CURIOS_ITEM_HANDLER).getMethod("getEquippedCurios");
            Object equipped = equippedMethod.invoke(handler);
            if (equipped == null) {
                return null;
            }
            Class<?> itemHandlerInterface = Class.forName(ITEM_HANDLER_MODIFIABLE);
            int slots = (int) itemHandlerInterface.getMethod("getSlots").invoke(equipped);
            Method stackInSlot = itemHandlerInterface.getMethod("getStackInSlot", int.class);

            Class<?> wrapperClass = Class.forName(BACKPACK_WRAPPER);
            Method fromStack = wrapperClass.getMethod("fromStack", ItemStack.class);

            for (int i = 0; i < slots; i++) {
                Object value = stackInSlot.invoke(equipped, i);
                if (!(value instanceof ItemStack stack) || stack.isEmpty()) {
                    continue;
                }
                Object contentsUuid = component(stack, "STORAGE_UUID");
                TaskbarJukebox.debugEvery("bp.curios.slot", 2000,
                        "Curios 槽 {} = {}, uuid={}", i, stack.getDisplayName().getString(), contentsUuid);
                if (uuid != null && !uuid.equals(contentsUuid)) {
                    continue;
                }

                // 找到了对应背包，尝试读 CD
                Object wrapper = fromStack.invoke(null, stack);
                Object song = songFromWrapper(wrapper);
                if (song != null) {
                    TaskbarJukebox.debugEvery("bp.source.curios", 2000,
                            "从 Curios 饰品栏背包读到唱片 SongInfo（槽 {}, GUI 无关）", i);
                    return song;
                }

                // 背包在 Curios 槽但客户端内容未同步 → 请求一次同步
                if (contentsUuid instanceof UUID id) {
                    requestContents(stack, id);
                }
            }
        } catch (ClassNotFoundException e) {
            // Curios / SophisticatedBackpacks 未安装，安静跳过
            TaskbarJukebox.debugOnce("bp.curios.absent", "Curios 或 SophisticatedBackpacks 未安装，跳过饰品栏路径");
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.curios", "扫描 Curios 饰品栏失败", e);
        }
        return null;
    }

    /** 请求服务端同步背包内容（只读请求，与打开 tooltip 触发的行为一致）；按 uuid 节流。 */
    private static void requestContents(ItemStack stack, UUID uuid) {
        long now = System.currentTimeMillis();
        Long last = LAST_CONTENT_REQUEST.get(uuid);
        if (last != null && now - last < CONTENT_REQUEST_COOLDOWN_MS) {
            return;
        }
        LAST_CONTENT_REQUEST.put(uuid, now);
        try {
            Class<?> client = Class.forName(BACKPACK_ITEM_CLIENT);
            Method send = client.getMethod("sendContentsRequest", ItemStack.class, UUID.class);
            send.invoke(null, stack, uuid);
            TaskbarJukebox.debug("已请求 Curios 背包内容同步: uuid={}", uuid);
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.requestContents", "请求背包内容同步失败", e);
        }
    }

    /** 记录 uuid ↔ songUrl，供音源实例被替换、Map 反查失败时兜底。 */
    private static void rememberUrl(Object uuid, Object sound) {
        if (!(uuid instanceof UUID id)) {
            return;
        }
        String url = stringOf(fieldOf(sound, "songUrl"));
        if (url != null) {
            UUID_URL_CACHE.put(id, url);
        }
    }

    /** 用音源 URL 反查上次成功匹配过的 uuid。 */
    private static Object findUuidByUrl(Object sound) {
        String url = stringOf(fieldOf(sound, "songUrl"));
        if (url == null) {
            return null;
        }
        for (Map.Entry<UUID, String> entry : UUID_URL_CACHE.entrySet()) {
            if (url.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }

    /** 从 StorageSoundHandler.storageSounds (Map&lt;UUID, SoundInstance&gt;) 按 sound 反查 uuid。 */
    private static Object findStorageUuid(Object sound) {
        try {
            Class<?> handler = Class.forName(STORAGE_SOUND_HANDLER);
            Field mapField = handler.getDeclaredField("storageSounds");
            mapField.setAccessible(true);
            Object map = mapField.get(null);
            if (!(map instanceof Map<?, ?> sounds)) {
                TaskbarJukebox.debugOnce("bp.mapType", "storageSounds 类型异常: {}", map);
                return null;
            }
            String myUrl = stringOf(fieldOf(sound, "songUrl"));
            for (Map.Entry<?, ?> entry : sounds.entrySet()) {
                if (entry.getValue() == sound) {
                    return entry.getKey();
                }
                // 音源实例可能被后续 payload 替换（同一 uuid 收到第二个包），
                // 我们手里的 tickingSounds 是旧实例，Map 里是新实例。URL 相同即视为同一曲目。
                if (myUrl != null && entry.getValue() != null) {
                    String theirUrl = stringOf(fieldOf(entry.getValue(), "songUrl"));
                    if (myUrl.equals(theirUrl)) {
                        return entry.getKey();
                    }
                }
            }
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.uuidLookup", "反查 storageUuid 失败", e);
        }
        return null;
    }

    /**
     * 兜底路径：从 StorageWrapperRepository 的 UUID 缓存按 storageUuid 取 wrapper。
     * 缓存按 ThreadGroup 分组，逐个遍历；命中不代表一定是这个背包，随后用
     * getContentsUuid 校验，避免拿错 wrapper 显示错歌。
     */
    private static Object fromUuidCache(Object uuid) {
        try {
            Class<?> repository = Class.forName(WRAPPER_REPOSITORY);
            Field mapField = repository.getDeclaredField("uuidStorageWrappers");
            mapField.setAccessible(true);
            Object groups = mapField.get(null);
            if (!(groups instanceof Map<?, ?> groupMap)) {
                return null;
            }
            for (Object cache : groupMap.values()) {
                Method getIfPresent = cache.getClass().getMethod("getIfPresent", Object.class);
                Object wrapper = getIfPresent.invoke(cache, uuid);
                if (wrapper == null) {
                    continue;
                }
                Object contentsUuid = invoke(wrapper, "getContentsUuid");
                if (uuid.equals(contentsUuid)) {
                    Object song = songFromWrapper(wrapper);
                    if (song != null) {
                        TaskbarJukebox.debugEvery("bp.source.uuidCache", 2000,
                                "从 StorageWrapperRepository 的 UUID 缓存读到唱片 SongInfo");
                        return song;
                    }
                }
            }
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.uuidCache", "从 UUID 缓存读取 wrapper 失败", e);
        }
        return null;
    }

    /** 路径 1：玩家正打开着这个 storage 的 GUI，直接从容器拿 wrapper。 */
    private static Object fromOpenContainer(Object uuid) {
        try {
            Object menu = Minecraft.getInstance().player == null
                    ? null : Minecraft.getInstance().player.containerMenu;
            if (menu == null || !isInstance(STORAGE_CONTAINER_MENU, menu)) {
                return null;
            }
            Object wrapper = invoke(menu, "getStorageWrapper");
            if (wrapper == null || !uuid.equals(invoke(wrapper, "getContentsUuid"))) {
                return null;
            }
            Object song = songFromWrapper(wrapper);
            if (song != null) {
                TaskbarJukebox.debugEvery("bp.source.container", 2000,
                        "从打开的背包容器读到唱片 SongInfo");
            }
            return song;
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.openContainer", "从打开的容器读取唱片失败", e);
            return null;
        }
    }

    /** 路径 2：遍历玩家背包与容器槽位，按 STORAGE_UUID component 找对应背包。 */
    private static Object fromInventory(Object uuid) {
        try {
            Class<?> wrapperClass = Class.forName(BACKPACK_WRAPPER);
            Method fromStack = wrapperClass.getMethod("fromStack", ItemStack.class);

            if (Minecraft.getInstance().player != null) {
                // 当前容器槽位
                Object menu = Minecraft.getInstance().player.containerMenu;
                Object slots = menu == null ? null : fieldOf(menu, "slots");
                Object song = scanSlots(slots, uuid, fromStack);
                if (song != null) {
                    TaskbarJukebox.debugEvery("bp.source.menuSlots", 2000,
                            "从容器槽位读到唱片 SongInfo");
                    return song;
                }

                // 玩家自己的背包
                Object inventory = fieldOf(Minecraft.getInstance().player, "inventory");
                Object items = inventory == null ? null : fieldOf(inventory, "items");
                song = scanSlots(items, uuid, fromStack);
                if (song != null) {
                    TaskbarJukebox.debugEvery("bp.source.playerInv", 2000,
                            "从玩家背包读到唱片 SongInfo");
                    return song;
                }
            }
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.inventory", "遍历背包槽位失败", e);
        }
        return null;
    }

    /** 兜底：扫描容器所有槽位里的 ItemStack，取第一个带 SONG_INFO 的 CD。 */
    private static Object fromAnyCdInContainer() {
        try {
            if (Minecraft.getInstance().player == null) {
                return null;
            }
            Object menu = Minecraft.getInstance().player.containerMenu;
            Object slots = menu == null ? null : fieldOf(menu, "slots");
            if (!(slots instanceof Collection<?> collection)) {
                return null;
            }
            for (Object slot : collection) {
                Object stack = slot == null ? null : fieldOf(slot, "item");
                if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) {
                    continue;
                }
                Object song = songInfo(itemStack);
                if (song != null) {
                    TaskbarJukebox.debugEvery("bp.source.anyCd", 2000,
                            "从容器槽位直接读到 NetMusic CD 的 SongInfo");
                    return song;
                }
            }
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.anyCd", "扫描容器槽位 CD 失败", e);
        }
        return null;
    }

    private static Object scanSlots(Object slots, Object uuid, Method fromStack) {
        if (!(slots instanceof Collection<?> collection)) {
            return null;
        }
        try {
            for (Object element : collection) {
                // 容器 slots 是 Slot 对象（要读 slot.item）；玩家 inventory.items 直接是 ItemStack。
                ItemStack itemStack = null;
                if (element instanceof ItemStack stack) {
                    itemStack = stack;
                } else {
                    Object stack = element == null ? null : fieldOf(element, "item");
                    if (stack instanceof ItemStack value) {
                        itemStack = value;
                    }
                }
                if (itemStack == null || itemStack.isEmpty()) {
                    continue;
                }
                Object contentsUuid = component(itemStack, "STORAGE_UUID");
                if (!uuid.equals(contentsUuid)) {
                    continue;
                }
                Object wrapper = fromStack.invoke(null, itemStack);
                if (wrapper != null) {
                    Object song = songFromWrapper(wrapper);
                    if (song != null) {
                        return song;
                    }
                }
            }
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.scanSlots", "扫描槽位失败", e);
        }
        return null;
    }

    /** wrapper → UpgradeHandler → JukeboxUpgradeWrapper → getDisc() → SongInfo。 */
    private static Object songFromWrapper(Object wrapper) {
        try {
            Object upgradeHandler = invoke(wrapper, "getUpgradeHandler");
            if (upgradeHandler == null) {
                return null;
            }
            Object slotWrappers = invoke(upgradeHandler, "getSlotWrappers");
            if (!(slotWrappers instanceof Map<?, ?> map)) {
                return null;
            }
            for (Object value : map.values()) {
                if (value != null && isInstance(JUKEBOX_WRAPPER, value)) {
                    Object disc = invoke(value, "getDisc");
                    if (disc instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                        Object song = songInfo(itemStack);
                        if (song != null) {
                            return song;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.wrapper", "从 storage wrapper 读取唱片失败", e);
        }
        return null;
    }

    /** 调 NetMusic 的 ItemMusicCD.getSongInfo(ItemStack)。 */
    private static Object songInfo(ItemStack stack) {
        try {
            Class<?> cd = Class.forName(CD_CLASS);
            Method method = cd.getMethod("getSongInfo", ItemStack.class);
            return method.invoke(null, stack);
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.songInfo", "读取 CD 的 SongInfo 失败", e);
            return null;
        }
    }

    /** 从 ItemStack 读一个 data component（按 ModCoreDataComponents 的字段名）。 */
    private static Object component(ItemStack stack, String fieldName) {
        try {
            Class<?> owner = Class.forName(SONG_INFO_COMPONENT_OWNER);
            Field field = owner.getField(fieldName);
            Object type = field.get(null);
            // STORAGE_UUID 是 DeferredHolder/Supplier，解包成 DataComponentType。
            Object resolved = type;
            try {
                Method get = resolved.getClass().getMethod("get");
                resolved = get.invoke(resolved);
            } catch (NoSuchMethodException ignored) {
                // 已经是 DataComponentType 本身
            }
            Method getComponent;
            try {
                getComponent = ItemStack.class.getMethod("get", net.minecraft.core.component.DataComponentType.class);
            } catch (NoSuchMethodException e) {
                // 生产环境方法名可能被混淆，按参数类型找唯一的无返回歧义方法
                getComponent = null;
                for (Method m : ItemStack.class.getMethods()) {
                    if (m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == net.minecraft.core.component.DataComponentType.class
                            && m.getReturnType() == Object.class) {
                        getComponent = m;
                        break;
                    }
                }
                if (getComponent == null) {
                    throw e;
                }
            }
            return getComponent.invoke(stack, resolved);
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.component." + fieldName, "读取 data component 失败: " + fieldName, e);
            return null;
        }
    }

    private static boolean isInstance(String className, Object value) {
        try {
            return Class.forName(className).isInstance(value);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 调无参 public 方法；getContentsUuid 返回 Optional 时解包成里面的值。 */
    private static Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object result = method.invoke(target);
            if (result instanceof Optional<?> optional) {
                return optional.orElse(null);
            }
            return result;
        } catch (Throwable e) {
            TaskbarJukebox.debugOnce("bp.invoke." + methodName, "调用方法失败: " + methodName, e);
            return null;
        }
    }

    private static Object fieldOf(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        TaskbarJukebox.debugOnce("bp.field." + name, "反射字段失败: " + name);
        return null;
    }
}
