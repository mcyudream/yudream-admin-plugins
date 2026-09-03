package online.yudream.base.plugin.material.application.command;

import java.util.List;

/** 文件夹批量导入：categoryId 优先，缺省时按 categoryName 查找或自动创建分类；name 为空时取文件名去扩展名。 */
public record FolderImportCommand(String categoryId, String categoryName, String visibility,
                                  List<String> tags, List<Item> items) {
    public record Item(String fileId, String filename, String name) {}
}
