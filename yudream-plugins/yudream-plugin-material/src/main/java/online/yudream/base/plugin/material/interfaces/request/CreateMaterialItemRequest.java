package online.yudream.base.plugin.material.interfaces.request;

/** 新增子物料：fileId 来自平台文件上传端点，name 留空时取文件名去扩展名。 */
public record CreateMaterialItemRequest(String fileId, String filename, String name) {
}
