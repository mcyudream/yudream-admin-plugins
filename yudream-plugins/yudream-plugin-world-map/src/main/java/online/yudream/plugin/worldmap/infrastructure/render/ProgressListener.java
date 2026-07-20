package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.domain.enumerate.RenderPhase;

/** 渲染进度回调。total = hires tile 数；lowres 阶段仅更新 message。 */
@FunctionalInterface
public interface ProgressListener {

    /** Notifies callers when the renderer moves between durable render phases. */
    default void phase(RenderPhase phase, String message) {
        // The legacy tile-only progress callback remains supported.
    }

    /**
     * @param done    已完成的 hires tile 数（lowres 阶段恒为 total）
     * @param total   hires tile 总数
     * @param message 当前阶段描述
     */
    void progress(int done, int total, String message);
}
