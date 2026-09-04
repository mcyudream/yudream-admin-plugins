package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;

/**
 * 题目管理用例：CRUD、筛选分页、标签聚合、JSON/Markdown 导出与 JSON 导入。
 * 所有写操作先经 validateAndNormalize 做题型相关的答案校验与规范化。
 */
public final class QuestionService {
    private static final int MAX_CONTENT_LENGTH = 20000;
    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 30;
    private static final int MAX_OPTIONS = 26;
    private static final int MAX_BLANKS = 20;
    private static final int MAX_IMPORT = 500;

    private final QuestionRepository questions;
    private final CategoryService categoryService;
    private final JsonSupport json;
    private final FrameworkServices framework;

    public QuestionService(QuestionRepository questions, CategoryService categoryService, JsonSupport json,
                           FrameworkServices framework) {
        this.questions = questions;
        this.categoryService = categoryService;
        this.json = json;
        this.framework = framework;
    }

    /** 解析用户显示名（昵称优先），失败返回 null。 */
    public String resolveUserName(String userId) {
        try {
            Optional<PluginUserProfile> profile = framework.users().findById(Long.parseLong(userId));
            return profile.map(user -> user.nickname() != null && !user.nickname().isBlank()
                    ? user.nickname() : user.username()).orElse(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    public PageResult<Question> query(QuestionQuery query) {
        List<Question> filtered = filter(query);
        int from = Math.min((query.page() - 1) * query.size(), filtered.size());
        int to = Math.min(from + query.size(), filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size());
    }

    private List<Question> filter(QuestionQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase(Locale.ROOT);
        List<String> ids = query.ids() == null ? List.of() : query.ids();
        return questions.listAll().stream()
                .filter(question -> ids.isEmpty() || ids.contains(question.id()))
                .filter(question -> query.categoryId() == null || query.categoryId().isBlank()
                        || query.categoryId().equals(question.categoryId()))
                .filter(question -> query.tag() == null || query.tag().isBlank()
                        || (question.tags() != null && question.tags().contains(query.tag())))
                .filter(question -> query.type() == null || query.type().isBlank()
                        || question.type().name().equalsIgnoreCase(query.type()))
                .filter(question -> query.difficulty() == null || question.difficulty() == query.difficulty())
                .filter(question -> query.status() == null || query.status().isBlank()
                        || question.status().equalsIgnoreCase(query.status()))
                .filter(question -> keyword.isEmpty()
                        || question.content().toLowerCase(Locale.ROOT).contains(keyword)
                        || (question.tags() != null && question.tags().stream()
                                .anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(keyword))))
                .toList();
    }

    public Question require(String id) {
        return questions.findById(id).orElseThrow(() -> new NotFoundException("题目不存在"));
    }

    public Question create(QuestionPayload payload, String operatorId, String operatorName) {
        QuestionPayload normalized = validateAndNormalize(payload);
        long now = System.currentTimeMillis();
        Question question = new Question(
                Ids.newId(),
                QuestionType.from(normalized.type()),
                normalized.categoryId(),
                normalized.tags(),
                normalized.content().trim(),
                normalized.options(),
                normalized.answer(),
                normalized.answers(),
                normalized.blanks(),
                normalized.referenceAnswer(),
                normalized.analysis(),
                normalized.difficulty(),
                normalized.status(),
                operatorId,
                operatorName,
                now,
                now
        );
        questions.save(question);
        return question;
    }

    public Question update(String id, QuestionPayload payload) {
        Question existing = require(id);
        QuestionPayload normalized = validateAndNormalize(payload);
        Question updated = new Question(
                id,
                QuestionType.from(normalized.type()),
                normalized.categoryId(),
                normalized.tags(),
                normalized.content().trim(),
                normalized.options(),
                normalized.answer(),
                normalized.answers(),
                normalized.blanks(),
                normalized.referenceAnswer(),
                normalized.analysis(),
                normalized.difficulty(),
                normalized.status(),
                existing.createdBy(),
                existing.createdByName(),
                existing.createdAt(),
                System.currentTimeMillis()
        );
        questions.save(updated);
        return updated;
    }

    public void delete(String id) {
        require(id);
        questions.delete(id);
    }

    public int batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请选择要删除的题目");
        }
        int deleted = 0;
        for (String id : ids) {
            if (id != null && questions.findById(id).isPresent()) {
                questions.delete(id);
                deleted++;
            }
        }
        return deleted;
    }

    /** 全库标签聚合（含停用题），按题目数降序。 */
    public List<Map<String, Object>> listTags() {
        Map<String, Long> counts = new LinkedHashMap<>();
        questions.listAll().forEach(question -> {
            for (String tag : question.tags() == null ? List.<String>of() : question.tags()) {
                counts.merge(tag, 1L, Long::sum);
            }
        });
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("name", entry.getKey());
                    view.put("count", entry.getValue());
                    return view;
                })
                .map(view -> (Map<String, Object>) view)
                .toList();
    }

    public String exportJson(QuestionQuery query) {
        List<Map<String, Object>> items = filter(query).stream().map(this::toExportMap).toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("questions", items);
        return json.write(root);
    }

    public String exportMarkdown(QuestionQuery query) {
        List<Question> items = filter(query);
        StringBuilder sb = new StringBuilder();
        sb.append("# 题库导出\n\n共 ").append(items.size()).append(" 道题\n\n");
        int index = 1;
        for (Question question : items) {
            sb.append("## ").append(index++).append(". 【").append(question.type().label()).append("】\n\n");
            sb.append(question.content()).append("\n\n");
            if (question.type().isChoice()) {
                List<String> options = question.options() == null ? List.of() : question.options();
                for (int i = 0; i < options.size(); i++) {
                    sb.append("- ").append(Question.optionKey(i)).append(". ").append(options.get(i)).append("\n");
                }
                sb.append("\n");
            }
            switch (question.type()) {
                case SINGLE, TRUE_FALSE -> sb.append("**答案：** ").append(question.answer()).append("\n\n");
                case MULTIPLE -> sb.append("**答案：** ").append(String.join("、", question.answers())).append("\n\n");
                case FILL -> {
                    sb.append("**答案：**\n\n");
                    List<List<String>> blanks = question.blanks() == null ? List.of() : question.blanks();
                    for (int i = 0; i < blanks.size(); i++) {
                        sb.append("- 第 ").append(i + 1).append(" 空：").append(String.join(" / ", blanks.get(i))).append("\n");
                    }
                    sb.append("\n");
                }
                case SHORT -> {
                    if (question.referenceAnswer() != null && !question.referenceAnswer().isBlank()) {
                        sb.append("**参考答案：**\n\n").append(question.referenceAnswer()).append("\n\n");
                    }
                }
            }
            if (question.analysis() != null && !question.analysis().isBlank()) {
                sb.append("**解析：**\n\n").append(question.analysis()).append("\n\n");
            }
            sb.append("---\n\n");
        }
        return sb.toString();
    }

    private Map<String, Object> toExportMap(Question question) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", question.type().name());
        item.put("categoryName", categoryService.categoryName(question.categoryId()));
        item.put("tags", question.tags() == null ? List.of() : question.tags());
        item.put("content", question.content());
        item.put("options", question.options() == null ? List.of() : question.options());
        item.put("answer", question.answer());
        item.put("answers", question.answers() == null ? List.of() : question.answers());
        item.put("blanks", question.blanks() == null ? List.of() : question.blanks());
        item.put("referenceAnswer", question.referenceAnswer());
        item.put("analysis", question.analysis());
        item.put("difficulty", question.difficulty());
        item.put("status", question.status());
        return item;
    }

    public record ImportFailure(int index, String reason) {
    }

    public record ImportResult(int imported, List<ImportFailure> failures) {
    }

    /** 批量导入：逐条校验，失败条目记录原因不中断整体导入；分类按名查找或新建。 */
    public ImportResult importQuestions(List<QuestionPayload> payloads, String operatorId, String operatorName) {
        if (payloads == null || payloads.isEmpty()) {
            throw new IllegalArgumentException("导入内容为空");
        }
        if (payloads.size() > MAX_IMPORT) {
            throw new IllegalArgumentException("单次最多导入 " + MAX_IMPORT + " 道题");
        }
        int imported = 0;
        List<ImportFailure> failures = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            QuestionPayload payload = payloads.get(i);
            try {
                QuestionPayload withCategory = payload;
                if ((payload.categoryId() == null || payload.categoryId().isBlank())
                        && payload.categoryName() != null && !payload.categoryName().isBlank()) {
                    String categoryId = categoryService.findOrCreateByName(payload.categoryName());
                    withCategory = new QuestionPayload(payload.type(), categoryId, payload.categoryName(),
                            payload.tags(), payload.content(), payload.options(), payload.answer(), payload.answers(),
                            payload.blanks(), payload.referenceAnswer(), payload.analysis(), payload.difficulty(),
                            payload.status());
                }
                create(withCategory, operatorId, operatorName);
                imported++;
            }
            catch (IllegalArgumentException e) {
                failures.add(new ImportFailure(i + 1, e.getMessage()));
            }
        }
        return new ImportResult(imported, failures);
    }

    /** 题型相关校验与答案规范化，返回规范化后的 payload。 */
    private QuestionPayload validateAndNormalize(QuestionPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("题目内容不能为空");
        }
        QuestionType type = QuestionType.from(payload.type());
        if (payload.content() == null || payload.content().isBlank()) {
            throw new IllegalArgumentException("题干不能为空");
        }
        if (payload.content().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("题干不能超过 " + MAX_CONTENT_LENGTH + " 字符");
        }
        int difficulty = payload.difficulty() == null ? 3 : payload.difficulty();
        if (difficulty < 1 || difficulty > 5) {
            throw new IllegalArgumentException("难度必须在 1~5 之间");
        }
        List<String> tags = normalizeTags(payload.tags());
        String categoryId = payload.categoryId();
        if (categoryId != null && !categoryId.isBlank()) {
            categoryService.require(categoryId);
        }
        else {
            categoryId = null;
        }
        String status = payload.status() == null || payload.status().isBlank()
                ? Question.STATUS_ENABLED : payload.status().trim().toUpperCase(Locale.ROOT);
        if (!Question.STATUS_ENABLED.equals(status) && !Question.STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("未知状态：" + payload.status());
        }

        List<String> options = List.of();
        String answer = null;
        List<String> answers = List.of();
        List<List<String>> blanks = List.of();
        String referenceAnswer = null;
        switch (type) {
            case SINGLE -> {
                options = normalizeOptions(payload.options());
                answer = normalizeOptionKey(payload.answer(), options.size());
            }
            case MULTIPLE -> {
                options = normalizeOptions(payload.options());
                answers = normalizeOptionKeys(payload.answers(), options.size());
            }
            case TRUE_FALSE -> answer = normalizeTrueFalse(payload.answer());
            case FILL -> blanks = normalizeBlanks(payload.blanks());
            case SHORT -> {
                if (payload.referenceAnswer() == null || payload.referenceAnswer().isBlank()) {
                    throw new IllegalArgumentException("简答题需要参考答案（供作答后对照自评）");
                }
                referenceAnswer = payload.referenceAnswer().trim();
            }
        }
        String analysis = payload.analysis() == null || payload.analysis().isBlank() ? null : payload.analysis().trim();
        // AI/批量导入常把选项文本混进选择题题干，入库前统一剥离（清洗失败则保留原文）
        String content = OptionStripper.strip(payload.content(), type, options.size());
        return new QuestionPayload(type.name(), categoryId, null, tags, content, options,
                answer, answers, blanks, referenceAnswer, analysis, difficulty, status);
    }

    private List<String> normalizeOptions(List<String> options) {
        if (options == null) {
            throw new IllegalArgumentException("选择题至少需要 2 个选项");
        }
        List<String> normalized = options.stream()
                .map(option -> option == null ? "" : option.trim())
                .filter(option -> !option.isEmpty())
                .toList();
        if (normalized.size() < 2) {
            throw new IllegalArgumentException("选择题至少需要 2 个选项");
        }
        if (normalized.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("选项最多 " + MAX_OPTIONS + " 个");
        }
        return normalized;
    }

    private String normalizeOptionKey(String answer, int optionCount) {
        String key = answer == null ? "" : answer.trim().toUpperCase(Locale.ROOT);
        if (key.length() != 1 || key.charAt(0) < 'A' || key.charAt(0) >= 'A' + optionCount) {
            throw new IllegalArgumentException("单选答案必须是 A~" + Question.optionKey(optionCount - 1) + " 之间的选项");
        }
        return key;
    }

    private List<String> normalizeOptionKeys(List<String> answers, int optionCount) {
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("多选题至少选择 1 个正确选项");
        }
        TreeSet<String> keys = new TreeSet<>();
        for (String raw : answers) {
            String key = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (key.length() != 1 || key.charAt(0) < 'A' || key.charAt(0) >= 'A' + optionCount) {
                throw new IllegalArgumentException("多选答案必须是 A~" + Question.optionKey(optionCount - 1) + " 之间的选项");
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    private String normalizeTrueFalse(String answer) {
        String key = answer == null ? "" : answer.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "TRUE", "T", "对", "正确", "√" -> "TRUE";
            case "FALSE", "F", "错", "错误", "×" -> "FALSE";
            default -> throw new IllegalArgumentException("判断题答案必须是 TRUE（对）或 FALSE（错）");
        };
    }

    private List<List<String>> normalizeBlanks(List<List<String>> blanks) {
        if (blanks == null || blanks.isEmpty()) {
            throw new IllegalArgumentException("填空题至少需要 1 个空");
        }
        if (blanks.size() > MAX_BLANKS) {
            throw new IllegalArgumentException("填空题最多 " + MAX_BLANKS + " 个空");
        }
        List<List<String>> normalized = new ArrayList<>();
        for (List<String> blank : blanks) {
            List<String> accepted = blank == null ? List.of() : blank.stream()
                    .map(item -> item == null ? "" : item.trim())
                    .filter(item -> !item.isEmpty())
                    .distinct()
                    .toList();
            if (accepted.isEmpty()) {
                throw new IllegalArgumentException("每个空至少需要 1 个可接受答案");
            }
            normalized.add(accepted);
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> normalized = tags.stream()
                .map(tag -> tag == null ? "" : tag.trim())
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
        if (normalized.size() > MAX_TAGS) {
            throw new IllegalArgumentException("标签最多 " + MAX_TAGS + " 个");
        }
        if (normalized.stream().anyMatch(tag -> tag.length() > MAX_TAG_LENGTH)) {
            throw new IllegalArgumentException("单个标签不能超过 " + MAX_TAG_LENGTH + " 字");
        }
        return normalized;
    }
}
