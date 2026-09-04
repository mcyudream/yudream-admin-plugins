package online.yudream.base.plugin.material.application;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.dto.CategoryView;

/**
 * 文件夹批量导入用例：逐个走单文件创建链路（平台文件落户、版本一、可见性解析），
 * 单个文件失败不中断整批，按文件收集失败原因返回。
 * 嵌套子文件夹以逐文件附加标签保留结构：中间目录名并入该文件的标签，分类统一用根文件夹名。
 */
public final class FolderImportService {
    public static final int MAX_ITEMS = 200;

    private final MaterialService materialService;
    private final CategoryService categoryService;

    public FolderImportService(MaterialService materialService, CategoryService categoryService) {
        this.materialService = materialService;
        this.categoryService = categoryService;
    }

    public FolderImportResult importFolder(String ownerId, FolderImportCommand command) {
        List<FolderImportCommand.Item> items = command.items() == null ? List.of() : command.items();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的文件");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("单次最多导入 " + MAX_ITEMS + " 个文件");
        }
        String categoryId = blankToNull(command.categoryId());
        String categoryName = null;
        if (categoryId == null && command.categoryName() != null && !command.categoryName().isBlank()) {
            CategoryView category = categoryService.findOrCreateByName(command.categoryName());
            categoryId = category.id();
            categoryName = category.name();
        } else if (categoryId != null) {
            categoryName = categoryService.nameMap().get(categoryId);
        }
        int created = 0;
        List<FolderImportFailure> failures = new ArrayList<>();
        for (FolderImportCommand.Item item : items) {
            try {
                materialService.create(ownerId, new CreateMaterialCommand(item.fileId(), item.filename(),
                        item.name(), categoryId, mergedTags(command.tags(), item.tags()), command.visibility(),
                        command.deptIds()));
                created++;
            } catch (RuntimeException failure) {
                failures.add(new FolderImportFailure(item.filename(), failure.getMessage()));
            }
        }
        return new FolderImportResult(items.size(), created, categoryId, categoryName, List.copyOf(failures));
    }

    /** 批次标签在前、子文件夹附加标签在后合并：去重与 8 个上限由 create 链路统一归一化，用户自选标签优先保留。 */
    private static List<String> mergedTags(List<String> batchTags, List<String> itemTags) {
        if (itemTags == null || itemTags.isEmpty()) {
            return batchTags;
        }
        Stream<String> batch = batchTags == null ? Stream.empty() : batchTags.stream();
        return Stream.concat(batch, itemTags.stream()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record FolderImportResult(int total, int created, String categoryId, String categoryName,
                                     List<FolderImportFailure> failures) {}

    public record FolderImportFailure(String filename, String message) {}
}
