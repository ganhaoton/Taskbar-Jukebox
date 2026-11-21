package com.ganhaoton.Taskbar_Jukebox.media;

import com.ganhaoton.Taskbar_Jukebox.TrackInfo;

/**
 * 系统媒体会话后端的最小接口。
 *
 * <p><b>线程约定：</b>除 {@link #isAvailable()} 外，所有方法只允许在
 * {@link MediaSessionService} 的专用守护线程上调用。渲染线程不得直接调用；
 * 后端内部也不得把异常抛回游戏主线程。</p>
 *
 * <p>每次曲目快照按 {@link #updateMetadata(TrackInfo)}、
 * {@link #updatePlayback(PlaybackState, long)} 的顺序调用。没有曲目时调用
 * {@link #clear()}。{@link #initialize()} 只会成功执行一次，重复调用应保持幂等。</p>
 */
public interface MediaSessionBackend extends AutoCloseable {
    /** 后端是否已经成功连接系统媒体服务。 */
    boolean isAvailable();

    /**
     * 在专用线程上初始化后端。
     *
     * @return 初始化成功返回 {@code true}；失败返回 {@code false}，由服务降级到 Noop。
     */
    boolean initialize();

    /** 更新曲目元数据。不应在此方法中高频发送系统信号。 */
    void updateMetadata(TrackInfo track);

    /** 更新播放状态与播放位置；位置单位为毫秒。 */
    void updatePlayback(PlaybackState state, long positionMs);

    /** 没有活动曲目：清空并停止显示，但保留后端生命周期。 */
    void clear();

    /** 释放系统资源；由服务在停止、卸载或降级时调用。 */
    @Override
    void close();
}
