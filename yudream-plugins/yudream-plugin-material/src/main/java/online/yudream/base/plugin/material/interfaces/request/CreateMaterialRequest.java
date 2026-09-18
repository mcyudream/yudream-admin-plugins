package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

/**
 * 创建物料。fileId 留空表示创建不带主文件的组合物料（文件由后续上传的子物料承载）；
 * 非空时必须来自平台文件上传端点。
 */
public record CreateMaterialRequest(String fileId, String filename, String name, String categoryId, List<String> tags,
                                    String visibility, List<String> deptIds) {
}
