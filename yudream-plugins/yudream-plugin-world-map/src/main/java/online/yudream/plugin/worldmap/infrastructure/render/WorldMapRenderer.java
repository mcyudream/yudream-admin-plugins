package online.yudream.plugin.worldmap.infrastructure.render;

import java.io.IOException;

/**
 * 瓦片渲染入口：消费 infrastructure/world + infrastructure/resource，
 * 产出 CONTRACT §4 hires tile（gzip JSON）与 §5 lowres tile（PNG 金字塔）。
 * 实现见 {@link DefaultWorldMapRenderer}。
 */
public interface WorldMapRenderer {

    /**
     * 执行一次全量渲染。
     *
     * @param job      渲染任务（世界/资产/维度/tile 范围）
     * @param sink     tile 输出接收器（空 tile 不会回调）
     * @param progress 进度回调，可为 null；total = hires tile 数
     * @return 渲染统计
     */
    RenderSummary render(RenderJob job, TileSink sink, ProgressListener progress) throws IOException;
}
