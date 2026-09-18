package online.yudream.base.plugin.material.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import online.yudream.base.plugin.material.application.dto.CategoryView;
import online.yudream.base.plugin.material.domain.MaterialCategory;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.Ids;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;

/**
 * 物料分类维护：删除时若有物料引用则拒绝。
 *
 * <p>分类是全库共享的一套分类表，因此「新增」与「重命名/排序/删除」的开放程度不同：
 * 新增（{@link #create} / {@link #findOrCreateByName}）对任何能使用物料库的人开放，
 * 让上传者在选择分类时能就地补一个；重命名/排序/删除仍只走管理端 {@code MANAGE_PERMISSION}。
 */
public final class CategoryService {
    private final CategoryRepository categories;
    private final MaterialRepository materials;

    public CategoryService(CategoryRepository categories, MaterialRepository materials) {
        this.categories = categories;
        this.materials = materials;
    }

    public List<CategoryView> list() {
        Map<String, Long> counts = materials.scanAll().stream()
                .filter(material -> material.categoryId() != null)
                .collect(Collectors.groupingBy(material -> material.categoryId(), Collectors.counting()));
        return categories.listAll().stream()
                .map(category -> new CategoryView(category.id(), category.name(), category.sort(),
                        counts.getOrDefault(category.id(), 0L), category.createdAt()))
                .toList();
    }

    public Map<String, String> nameMap() {
        return categories.listAll().stream()
                .collect(Collectors.toMap(MaterialCategory::id, MaterialCategory::name));
    }

    public CategoryView create(String name, int sort) {
        String trimmed = normalize(name);
        MaterialCategory category = new MaterialCategory(Ids.newId(), trimmed, sort, System.currentTimeMillis());
        categories.save(category);
        return new CategoryView(category.id(), category.name(), category.sort(), 0L, category.createdAt());
    }

    /**
     * 按名称查找分类（忽略大小写），不存在则以追加排序自动创建——文件夹导入、上传时快捷新增分类等场景使用。
     * 命中已有分类时返回带**真实物料数**的视图，避免调用方拿去展示时显示成 0。
     */
    public CategoryView findOrCreateByName(String name) {
        String trimmed = normalize(name);
        return categories.listAll().stream()
                .filter(category -> category.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .map(category -> new CategoryView(category.id(), category.name(), category.sort(),
                        countMaterials(category.id()), category.createdAt()))
                .orElseGet(() -> create(trimmed, nextSort()));
    }

    private long countMaterials(String categoryId) {
        return materials.scanAll().stream()
                .filter(material -> categoryId.equals(material.categoryId()))
                .count();
    }

    private int nextSort() {
        return categories.listAll().stream().mapToInt(MaterialCategory::sort).max().orElse(-1) + 1;
    }

    public CategoryView update(String id, String name, int sort) {
        MaterialCategory existing = categories.findById(id)
                .orElseThrow(() -> new NotFoundException("分类不存在"));
        MaterialCategory updated = new MaterialCategory(existing.id(), normalize(name), sort, existing.createdAt());
        categories.save(updated);
        return new CategoryView(updated.id(), updated.name(), updated.sort(),
                countMaterials(updated.id()), updated.createdAt());
    }

    public void delete(String id) {
        categories.findById(id).orElseThrow(() -> new NotFoundException("分类不存在"));
        long count = countMaterials(id);
        if (count > 0) {
            throw new IllegalStateException("该分类下还有 " + count + " 个物料，请先移出后再删除");
        }
        categories.delete(id);
    }

    private String normalize(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        if (trimmed.length() > 30) {
            throw new IllegalArgumentException("分类名称不能超过 30 字");
        }
        return trimmed;
    }
}
