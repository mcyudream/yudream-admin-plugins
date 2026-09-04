package online.yudream.base.plugin.material.application.command;

import java.util.List;

/** 从平台上传创建物料。visibility 缺省为 PRIVATE；DEPT 时 deptIds 为显式选择的可见部门。 */
public record CreateMaterialCommand(String fileId, String filename, String name, String categoryId, List<String> tags,
                                    String visibility, List<String> deptIds) {
}
