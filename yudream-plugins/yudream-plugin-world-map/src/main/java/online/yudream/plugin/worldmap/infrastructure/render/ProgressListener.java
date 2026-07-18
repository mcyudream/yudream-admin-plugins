package online.yudream.plugin.worldmap.infrastructure.render;

/** 渲染进度回调。total = hires tile 数；lowres 阶段仅更新 message。 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * @param done    已完成的 hires tile 数（lowres 阶段恒为 total）
     * @param total   hires tile 总数
     * @param message 当前阶段描述
     */
    void progress(int done, int total, String message);
}
