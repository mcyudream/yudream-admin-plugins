package online.yudream.base.plugin.material.application.command;

import java.util.List;

/** 从平台上传创建物料。visibility 缺省为 PRIVATE。 */
public record CreateMaterialCommand(String fileId, String filename, String name, String categoryId, List<String> tags,
                                    String visibility) {
}
