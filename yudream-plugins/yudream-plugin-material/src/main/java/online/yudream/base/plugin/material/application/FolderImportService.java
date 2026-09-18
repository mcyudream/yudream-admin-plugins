package online.yudream.base.plugin.material.application;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import online.yudream.base.plugin.material.application.command.CreateMaterialCommand;
import online.yudream.base.plugin.material.application.command.CreateMaterialItemCommand;
import online.yudream.base.plugin.material.application.command.FolderImportCommand;
import online.yudream.base.plugin.material.application.dto.CategoryView;
import online.yudream.base.plugin.material.application.dto.MaterialDetail;

/**
 * 文件夹导入用例，两种形态共用分类解析、命名与逐文件容错。
 *
 * <ul>
 *   <li>FILES（缺省）：逐个文件走单文件创建链路（平台文件落户、版本一、可见性解析），每个文件一个物料。
 *       嵌套子文件夹以逐文件附加标签保留结构（中间目录名并入该文件的标签），分类统一用根文件夹名。</li>
 *   <li>BUNDLE：整批合并为一个组合物料（无主文件），每个文件成为一个子物料；
 *       中间目录名同时写进该子物料首个版本的备注，目录来源不在合并过程中静默丢失。</li>
 * </ul>
 *
 * <p>两种形态的命名规则一致：文件位于嵌套子文件夹时，物料名（BUNDLE 下是子物料名）由
 * 「中间子目录 - 文件名」用 '-' 拼接，如 {@code 明信片/海报/横版/封面.png → 横版-封面}；
 * 根文件夹名不进名称（它已经是分类名或组合物料名）。显式给出的 name 优先、不加目录前缀。
 * 名称超上限时先丢最外层目录、只保留文件名，保证深层目录不会让整项导入失败。
 *
 * <p>两种形态都保持「单个文件失败不中断整批」，按文件收集失败原因返回。
 */
public final class FolderImportService {
    public static final int MAX_ITEMS = 200;
    /** 组合物料形态（BUNDLE）下的子物料上限，与 {@link MaterialItemService#MAX_ITEMS} 一致。 */
    public static final int MAX_BUNDLE_ITEMS = MaterialItemService.MAX_ITEMS;
    private static final String DEFAULT_BUNDLE_NAME = "未命名组合物料";
    private static final String SOURCE_NOTE_PREFIX = "来源目录：";
    /** 目录名与文件名、目录名与目录名之间的连接符。 */
    private static final String NAME_SEPARATOR = "-";

    private final MaterialService materialService;
    private final MaterialItemService itemService;
    private final CategoryService categoryService;

    public FolderImportService(MaterialService materialService, MaterialItemService itemService,
                               CategoryService categoryService) {
        this.materialService = materialService;
        this.itemService = itemService;
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
        ResolvedCategory category = resolveCategory(command);
        if (!command.bundle()) {
            return importAsFiles(ownerId, command, items, category);
        }
        if (items.size() > MAX_BUNDLE_ITEMS) {
            throw new IllegalArgumentException("组合物料最多 " + MAX_BUNDLE_ITEMS + " 个子物料，当前 "
                    + items.size() + " 个文件，请改用逐个文件导入");
        }
        return importAsBundle(ownerId, command, items, category);
    }

    /** 逐个文件导入：每个文件一个单文件物料，子文件夹名作为逐文件附加标签。 */
    private FolderImportResult importAsFiles(String ownerId, FolderImportCommand command,
                                             List<FolderImportCommand.Item> items, ResolvedCategory category) {
        int created = 0;
        List<FolderImportFailure> failures = new ArrayList<>();
        for (FolderImportCommand.Item item : items) {
            try {
                materialService.create(ownerId, new CreateMaterialCommand(item.fileId(), item.filename(),
                        importName(item, MaterialService.MAX_NAME_LENGTH), category.id(),
                        mergedTags(command.tags(), item.tags()), command.visibility(), command.deptIds()));
                created++;
            }
            catch (RuntimeException failure) {
                failures.add(new FolderImportFailure(item.filename(), failure.getMessage()));
            }
        }
        return new FolderImportResult(FolderImportCommand.MODE_FILES, items.size(), created, category.id(),
                category.name(), null, null, List.copyOf(failures));
    }

    /**
     * 整批合并为一个组合物料：先建无主文件的父物料，再逐文件建子物料（各子物料独立版本链，均从 v1 起编号）。
     * 一个子物料都没建成时删除父物料，避免库里留下打不开的空壳。
     */
    private FolderImportResult importAsBundle(String ownerId, FolderImportCommand command,
                                              List<FolderImportCommand.Item> items, ResolvedCategory category) {
        MaterialDetail material = materialService.create(ownerId, new CreateMaterialCommand(null, null,
                bundleName(command.name(), category), category.id(), command.tags(), command.visibility(),
                command.deptIds()));
        String materialId = material.material().id();
        int created = 0;
        List<FolderImportFailure> failures = new ArrayList<>();
        for (FolderImportCommand.Item item : items) {
            try {
                itemService.createItem(ownerId, materialId, new CreateMaterialItemCommand(item.fileId(),
                        item.filename(), importName(item, MaterialItemService.MAX_NAME_LENGTH), sourceNote(item.tags())));
                created++;
            }
            catch (RuntimeException failure) {
                failures.add(new FolderImportFailure(item.filename(), failure.getMessage()));
            }
        }
        if (created == 0) {
            materialService.deleteMine(ownerId, materialId);
            return new FolderImportResult(FolderImportCommand.MODE_BUNDLE, items.size(), 0, category.id(),
                    category.name(), null, null, List.copyOf(failures));
        }
        return new FolderImportResult(FolderImportCommand.MODE_BUNDLE, items.size(), created, category.id(),
                category.name(), materialId, material.material().name(), List.copyOf(failures));
    }

    /** 分类解析：categoryId 优先，缺省按 categoryName 查找（忽略大小写）或自动创建；都空则不带分类。 */
    private ResolvedCategory resolveCategory(FolderImportCommand command) {
        String categoryId = blankToNull(command.categoryId());
        if (categoryId != null) {
            return new ResolvedCategory(categoryId, categoryService.nameMap().get(categoryId));
        }
        String categoryName = blankToNull(command.categoryName());
        if (categoryName == null) {
            return new ResolvedCategory(null, null);
        }
        CategoryView category = categoryService.findOrCreateByName(categoryName);
        return new ResolvedCategory(category.id(), category.name());
    }

    /** 组合物料名称：显式 name 优先，其次分类名（根文件夹名），最后兜底常量；长度上限由创建链路校验。 */
    private static String bundleName(String name, ResolvedCategory category) {
        String explicit = blankToNull(name);
        if (explicit != null) {
            return explicit;
        }
        String fallback = blankToNull(category.name());
        return fallback != null ? fallback : DEFAULT_BUNDLE_NAME;
    }

    /**
     * 条目名称：显式 name 优先（不加目录前缀）；否则按「中间子目录 - 文件名」拼接，见类注释。
     * 目录路径段来自 item.tags（文件夹导入时由前端按相对路径拆出，见 FolderImportCommand.Item）。
     */
    private static String importName(FolderImportCommand.Item item, int maxLength) {
        String explicit = blankToNull(item.name());
        return explicit != null ? explicit : composedName(item.tags(), item.filename(), maxLength);
    }

    /**
     * 拼接「中间子目录 - 文件名」，如 海报/横版/封面.png → 「海报-横版-封面」。
     * 超上限时从最外层目录开始丢弃：越靠近文件的目录越具体、文件名最不可替代，先丢外侧信息；
     * 目录全丢完仍超长只剩文件名时按上限硬截断（极端长文件名此前会让整项导入失败）。
     */
    static String composedName(List<String> folderSegments, String filename, int maxLength) {
        String base = MaterialService.displayName(null, MaterialService.sanitizeFilename(filename));
        List<String> segments = cleanSegments(folderSegments);
        for (int from = 0; from <= segments.size(); from++) {
            List<String> kept = segments.subList(from, segments.size());
            String candidate = kept.isEmpty() ? base : String.join(NAME_SEPARATOR, kept) + NAME_SEPARATOR + base;
            if (candidate.length() <= maxLength) {
                return candidate;
            }
        }
        return base.substring(0, Math.min(base.length(), maxLength));
    }

    /** 目录路径段清洗：去空、去首尾空格，保持原有顺序（由外到内）。 */
    private static List<String> cleanSegments(List<String> folderSegments) {
        if (folderSegments == null || folderSegments.isEmpty()) {
            return List.of();
        }
        return folderSegments.stream()
                .filter(segment -> segment != null && !segment.isBlank())
                .map(String::trim)
                .toList();
    }

    /** 组合物料不保留目录层级，把中间子目录写进子物料首个版本的备注；超备注上限时截断，不让深层目录拖垮整项。 */
    private static String sourceNote(List<String> folderSegments) {
        if (folderSegments == null || folderSegments.isEmpty()) {
            return null;
        }
        String path = folderSegments.stream()
                .filter(segment -> segment != null && !segment.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("/"));
        if (path.isEmpty()) {
            return null;
        }
        String note = SOURCE_NOTE_PREFIX + path;
        return note.length() > MaterialService.MAX_NOTE_LENGTH
                ? note.substring(0, MaterialService.MAX_NOTE_LENGTH) : note;
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

    /** 已解析的分类：id 与展示名（未指定分类时两者皆为 null）。 */
    private record ResolvedCategory(String id, String name) {}

    /**
     * 导入结果。bundle 形态额外给出创建的组合物料 id 与名称，files 形态两者为 null；
     * total/created 在 files 形态是物料数，在 bundle 形态是文件（子物料）数。
     */
    public record FolderImportResult(String mode, int total, int created, String categoryId, String categoryName,
                                     String materialId, String materialName, List<FolderImportFailure> failures) {}

    public record FolderImportFailure(String filename, String message) {}
}
