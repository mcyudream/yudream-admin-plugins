package online.yudream.base.plugin.material.interfaces.request;

/**
 * 设置组合物料的预览主文件。itemId 必须是该物料下的子物料 id；
 * 留空（或 null）表示取消指定，父物料预览回退到第一个子物料。
 */
public record SetPreviewItemRequest(String itemId) {
}
