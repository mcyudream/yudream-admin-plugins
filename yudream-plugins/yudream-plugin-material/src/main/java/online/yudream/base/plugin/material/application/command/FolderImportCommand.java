package online.yudream.base.plugin.material.application.command;

import java.util.List;

/**
 * 文件夹批量导入：分类统一用所选根文件夹名（categoryId 优先，缺省按 categoryName 查找或自动创建）；
 * item.tags 携带所在子文件夹名等逐文件附加标签，与批次 tags 合并后统一归一化（去重、上限 8 个、单签 20 字）；
 * name 为空时取文件名去扩展名。DEPT 可见性时 deptIds 为显式选择的可见部门。
 */
public record FolderImportCommand(String categoryId, String categoryName, String visibility,
                                  List<String> deptIds, List<String> tags, List<Item> items) {
    public record Item(String fileId, String filename, String name, List<String> tags) {}
}
