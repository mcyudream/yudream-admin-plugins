package online.yudream.base.plugin.questionbank.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuestionCategory;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;
import online.yudream.base.plugin.questionbank.infrastructure.PaperRepository;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;

/** 分类维护：增删改查、合并、引用计数与导入时的按名查找/新建。 */
public final class CategoryService {
    private static final int MAX_NAME_LENGTH = 30;

    private final CategoryRepository categories;
    private final QuestionRepository questions;
    private final PaperRepository papers;

    public CategoryService(CategoryRepository categories, QuestionRepository questions, PaperRepository papers) {
        this.categories = categories;
        this.questions = questions;
        this.papers = papers;
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

    /**
     * 合并分类：来源分类下的题目整体迁入目标分类，题单抽题规则的分类引用一并改写，随后删除来源分类。
     * 会话题目快照不受影响（抽题时已固化）。返回迁移的题目数。
     */
    public int merge(String targetId, List<String> sourceIds) {
        require(targetId);
        if (sourceIds == null || sourceIds.isEmpty()) {
            throw new IllegalArgumentException("请选择要合并的分类");
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String id : sourceIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (id.equals(targetId)) {
                throw new IllegalArgumentException("目标分类不能同时是被合并的分类");
            }
            sources.add(id);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("请选择要合并的分类");
        }
        for (String id : sources) {
            require(id);
        }
        long now = System.currentTimeMillis();
        int moved = 0;
        for (Question question : questions.listAll()) {
            if (question.categoryId() != null && sources.contains(question.categoryId())) {
                questions.save(new Question(question.id(), question.type(), targetId, question.tags(),
                        question.content(), question.options(), question.answer(), question.answers(),
                        question.blanks(), question.referenceAnswer(), question.analysis(), question.difficulty(),
                        question.status(), question.createdBy(), question.createdByName(),
                        question.createdAt(), now));
                moved++;
            }
        }
        for (Paper paper : papers.listAll()) {
            if (paper.categoryId() != null && sources.contains(paper.categoryId())) {
                papers.save(new Paper(paper.id(), paper.name(), paper.description(), paper.mode(), targetId,
                        paper.tags(), paper.types(), paper.difficulties(), paper.count(), paper.questionIds(),
                        paper.subjectiveMode(), paper.status(), paper.createdBy(), paper.createdByName(),
                        paper.createdAt(), now));
            }
        }
        for (String id : sources) {
            categories.delete(id);
        }
        return moved;
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
