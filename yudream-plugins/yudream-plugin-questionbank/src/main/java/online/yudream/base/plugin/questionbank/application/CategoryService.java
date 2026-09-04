package online.yudream.base.plugin.questionbank.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.domain.QuestionCategory;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;

/** 分类维护：增删改查、引用计数与导入时的按名查找/新建。 */
public final class CategoryService {
    private static final int MAX_NAME_LENGTH = 30;

    private final CategoryRepository categories;
    private final QuestionRepository questions;

    public CategoryService(CategoryRepository categories, QuestionRepository questions) {
        this.categories = categories;
        this.questions = questions;
    }

    public List<Map<String, Object>> listWithCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        questions.listAll().forEach(question -> {
            if (question.categoryId() != null) {
                counts.merge(question.categoryId(), 1L, Long::sum);
            }
        });
        return categories.listAll().stream()
                .map(category -> {
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("id", category.id());
                    view.put("name", category.name());
                    view.put("sort", category.sort());
                    view.put("questionCount", counts.getOrDefault(category.id(), 0L));
                    view.put("createdAt", category.createdAt());
                    return view;
                })
                .map(view -> (Map<String, Object>) view)
                .toList();
    }

    public QuestionCategory require(String id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("分类不存在"));
    }

    public QuestionCategory create(String name, Integer sort) {
        String normalized = validateName(name);
        long now = System.currentTimeMillis();
        QuestionCategory category = new QuestionCategory(Ids.newId(), normalized, sort == null ? 0 : sort, now, now);
        categories.save(category);
        return category;
    }

    public QuestionCategory update(String id, String name, Integer sort) {
        QuestionCategory existing = require(id);
        String normalized = validateName(name, id);
        QuestionCategory updated = new QuestionCategory(id, normalized,
                sort == null ? existing.sort() : sort, existing.createdAt(), System.currentTimeMillis());
        categories.save(updated);
        return updated;
    }

    public void delete(String id) {
        require(id);
        long referenced = questions.listAll().stream().filter(question -> id.equals(question.categoryId())).count();
        if (referenced > 0) {
            throw new IllegalArgumentException("该分类下还有 " + referenced + " 道题，请先移出后再删除");
        }
        categories.delete(id);
    }

    /** 导入用：按名查找，不存在则新建。名称为空返回 null。 */
    public String findOrCreateByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        return categories.listAll().stream()
                .filter(category -> category.name().equalsIgnoreCase(normalized))
                .findFirst()
                .map(QuestionCategory::id)
                .orElseGet(() -> create(normalized, 0).id());
    }

    public String categoryName(String categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categories.findById(categoryId).map(QuestionCategory::name).orElse(null);
    }

    private String validateName(String name) {
        return validateName(name, null);
    }

    private String validateName(String name, String excludeId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("分类名称不能超过 " + MAX_NAME_LENGTH + " 字");
        }
        boolean duplicated = categories.listAll().stream()
                .anyMatch(category -> category.name().equalsIgnoreCase(normalized)
                        && (excludeId == null || !excludeId.equals(category.id())));
        if (duplicated) {
            throw new IllegalArgumentException("分类名称已存在");
        }
        return normalized;
    }
}
