package online.yudream.base.plugin.material.application.command;

import java.util.List;

/** 更新物料元数据。visibility 为 null 表示不调整。 */
public record UpdateMaterialCommand(String name, String categoryId, List<String> tags, String visibility) {
}
