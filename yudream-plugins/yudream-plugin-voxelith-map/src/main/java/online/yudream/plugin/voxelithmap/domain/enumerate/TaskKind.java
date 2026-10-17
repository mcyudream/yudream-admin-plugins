package online.yudream.plugin.voxelithmap.domain.enumerate;

/**
 * 插件任务类型。
 *
 * <p>渲染与导入这类长任务共用同一套任务机制（进度、日志、诊断、取消、SSE 推送），
 * 靠这个字段区分语义：渲染任务绑定地图与代，导入任务绑定来源实例。</p>
 */
public enum TaskKind {
    /** 渲染地图（绑定 mapId + generationId）。 */
    RENDER("渲染"),
    /** 从面板实例导入世界与模组（创建一张新地图）。 */
    IMPORT("实例导入"),
    /** 从来源实例同步最新存档覆盖已有地图。 */
    SYNC("存档同步"),
    /** 删除地图（回收产物与存储，异步执行）。 */
    DELETE("删除地图");

    private final String label;

    TaskKind(String label) {
        this.label = label;
    }

    /** 中文展示名。 */
    public String label() {
        return label;
    }
}
