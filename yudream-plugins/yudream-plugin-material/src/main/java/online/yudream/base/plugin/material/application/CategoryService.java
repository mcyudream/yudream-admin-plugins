package online.yudream.base.plugin.material.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import online.yudream.base.plugin.material.application.dto.CategoryView;
import online.yudream.base.plugin.material.domain.MaterialCategory;
import online.yudream.base.plugin.material.infrastructure.CategoryRepository;
import online.yudream.base.plugin.material.infrastructure.Ids;
import online.yudream.base.plugin.material.infrastructure.MaterialRepository;

/** 物料分类维护：删除时若有物料引用则拒绝。 */
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

    public CategoryView update(String id, String name, int sort) {
        MaterialCategory existing = categories.findById(id)
                .orElseThrow(() -> new NotFoundException("分类不存在"));
        MaterialCategory updated = new MaterialCategory(existing.id(), normalize(name), sort, existing.createdAt());
        categories.save(updated);
        long count = materials.scanAll().stream().filter(m -> id.equals(m.categoryId())).count();
        return new CategoryView(updated.id(), updated.name(), updated.sort(), count, updated.createdAt());
    }

    public void delete(String id) {
        categories.findById(id).orElseThrow(() -> new NotFoundException("分类不存在"));
        long count = materials.scanAll().stream().filter(m -> id.equals(m.categoryId())).count();
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
