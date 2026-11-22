package com.ganhaoton.Taskbar_Jukebox.media;

import com.ganhaoton.Taskbar_Jukebox.TaskbarJukebox;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 媒体会话封面图。
 *
 * <p>jar 内自带一张透明占位图 {@code assets/taskbar_jukebox/cover.png}。首次启动时
 * 释放到游戏目录下的 {@code config/taskbar_jukebox/cover.png}；已有文件绝不覆盖。
 * 用户直接替换这个外部 PNG 就能换图标，无需重新编译模组。</p>
 *
 * <p>Windows 侧不把 File 交给 JMTC 的 {@code setMediaProperties}：那个实现把路径转成
 * {@code file:/...} URI 传给 DLL，且对 {@code thumbnailLoaded()} 无限等待，一旦 DLL
 * 加载失败就会永久卡住媒体线程。这里改为反射直调 {@code SMTCAdapter.setThumbnail}，
 * 传原生绝对路径，并把等待限制在 3 秒内。</p>
 *
 * <p>本类所有方法都由媒体线程调用；{@link #ensure()} 与 {@link #stampChanged()} 内部
 * 额外做了同步，保证极端情况下也只有一个线程在动文件。</p>
 */
public final class CoverArt {
    private static final String RESOURCE = "/assets/taskbar_jukebox/cover.png";
    private static final Path FILE = Path.of("config", "taskbar_jukebox", "cover.png");

    private static boolean ensured;
    private static long appliedStamp = -1L;

    private CoverArt() {
    }

    /** 外部封面文件路径（相对游戏运行目录）。 */
    public static Path file() {
        return FILE;
    }

    /** 供 MPRIS 使用的 file URI；文件不可用时返回空串。 */
    public static String artUrl() {
        ensure();
        if (!Files.isRegularFile(FILE)) {
            return "";
        }
        return FILE.toAbsolutePath().toUri().toString();
    }

    /** 确保外部封面存在；用户放好的文件不会被覆盖。 */
    public static synchronized void ensure() {
        if (ensured) {
            return;
        }
        try {
            if (Files.isRegularFile(FILE)) {
                TaskbarJukebox.debug("封面文件已存在: {}", FILE.toAbsolutePath());
            } else {
                Files.createDirectories(FILE.getParent());
                try (InputStream in = CoverArt.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) {
                        TaskbarJukebox.LOGGER.warn("jar 内缺少默认封面资源 {}", RESOURCE);
                    } else {
                        Files.copy(in, FILE, StandardCopyOption.REPLACE_EXISTING);
                        TaskbarJukebox.debug("已生成空白封面: {}", FILE.toAbsolutePath());
                    }
                }
            }
            ensured = true;
        } catch (Throwable t) {
            TaskbarJukebox.debugOnce("cover.ensure", "准备封面文件失败", t);
        }
    }

    /**
     * 封面文件被替换过后返回 {@code true}（首次调用也算变化）。
     * 后端据此重新推送一次图标，避免改了文件却要等换歌才生效。
     */
    public static synchronized boolean stampChanged() {
        if (!ensured) {
            return false;
        }
        long stamp = FILE.toFile().lastModified();
        if (stamp == appliedStamp) {
            return false;
        }
        appliedStamp = stamp;
        return true;
    }

    /**
     * 把封面推给 Windows SMTC。失败只记 debug，不让媒体线程降级。
     */
    public static void pushThumbnail(Object jmtc) {
        ensure();
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        String path = FILE.toAbsolutePath().toString();
        try {
            Object lib = field(jmtc, "lib");
            if (lib == null) {
                TaskbarJukebox.debugOnce("cover.lib", "JMTC 实例缺少 lib 字段，跳过封面推送");
                return;
            }

            Class<?> wstringClass = Class.forName("com.sun.jna.WString");
            Constructor<?> ctor = wstringClass.getConstructor(String.class);
            Object wstring = ctor.newInstance(path);

            Method setter = method(lib.getClass(), "setThumbnail", wstringClass);
            if (setter == null) {
                TaskbarJukebox.debugOnce("cover.setter", "SMTCAdapter 没有 setThumbnail 方法");
                return;
            }
            setter.invoke(lib, wstring);

            // 上游库这里是无限等待；我们限 3 秒，DLL 有问题也不能拖死媒体线程。
            Method loadedMethod = method(lib.getClass(), "thumbnailLoaded");
            boolean loaded = false;
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                Object result = loadedMethod == null ? null : loadedMethod.invoke(lib);
                if (result instanceof Boolean value && value) {
                    loaded = true;
                    break;
                }
                Thread.sleep(20L);
            }
            appliedStamp = FILE.toFile().lastModified();
            TaskbarJukebox.debug("SMTC 封面已推送: path={}, loaded={}, thread={}",
                    path, loaded, Thread.currentThread().getName());
        } catch (Throwable t) {
            TaskbarJukebox.debugOnce("cover.push", "推送 SMTC 封面失败", t);
        }
    }

    private static Object field(Object target, String name) {
        Class<?> type = target == null ? null : target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getMethod(name, parameters);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
