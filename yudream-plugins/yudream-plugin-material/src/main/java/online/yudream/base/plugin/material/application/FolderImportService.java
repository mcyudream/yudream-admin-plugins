package online.yudream.base.plugin.material.application;

import java.util.ArrayList;
import java.util.List;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.dto.CategoryView;

/**
 * 文件夹批量导入用例：逐个走单文件创建链路（平台文件落户、版本一、可见性解析），
 * 单个文件失败不中断整批，按文件收集失败原因返回。
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
                        item.name(), categoryId, command.tags(), command.visibility()));
                created++;
            } catch (RuntimeException failure) {
                failures.add(new FolderImportFailure(item.filename(), failure.getMessage()));
            }
        }
        return new FolderImportResult(items.size(), created, categoryId, categoryName, List.copyOf(failures));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record FolderImportResult(int total, int created, String categoryId, String categoryName,
                                     List<FolderImportFailure> failures) {}

    public record FolderImportFailure(String filename, String message) {}
}
