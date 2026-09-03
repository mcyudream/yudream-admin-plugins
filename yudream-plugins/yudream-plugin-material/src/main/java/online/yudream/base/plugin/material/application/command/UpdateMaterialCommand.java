package online.yudream.base.plugin.material.application.command;

import java.util.List;

/** 更新物料元数据。 */
public record UpdateMaterialCommand(String name, String categoryId, List<String> tags) {
}
