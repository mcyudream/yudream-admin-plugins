package online.yudream.base.plugin.feedback.infrastructure.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.feedback.domain.aggregate.Feedback;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackActivityAction;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackCommentRole;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackType;
import online.yudream.base.plugin.feedback.domain.repo.FeedbackRepository;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackActivity;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackComment;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackQuery;
import online.yudream.base.plugin.feedback.infrastructure.Ids;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/**
 * 反馈文档仓储。
 *
 * <p>宿主 {@code findByField} 存在「先分页后过滤」的回归，因此带筛选的查询统一走全量扫描
 * （每页 200 条）后在内存中过滤、排序与分页，保证 total 与实际命中一致。
 * 评论与活动内嵌在反馈文档中，随聚合一起读写，避免跨集合拼装。
 */
public class FeedbackDocumentRepository implements FeedbackRepository {

    private static final String COLLECTION = "feedback-items";
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_SCAN_PAGES = 500;

    private final PluginDocumentStore documents;

    public FeedbackDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public Feedback save(Feedback feedback) {
        return toFeedback(documents.save(COLLECTION, feedback.id(), document(feedback)));
    }

    @Override
    public Optional<Feedback> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, id).map(this::toFeedback);
    }

    @Override
    public Optional<Feedback> findByIdAndAuthorId(String id, String authorId) {
        if (authorId == null || authorId.isBlank()) {
            return Optional.empty();
        }
        return findById(id).filter(feedback -> feedback.ownedBy(authorId));
    }

    @Override
    public List<Feedback> findByQuery(FeedbackQuery query) {
        return paged(matched(query), query.page(), query.size());
    }

    @Override
    public long countByQuery(FeedbackQuery query) {
        return matched(query).size();
    }

    @Override
    public List<Feedback> findAll() {
        return scan();
    }

    @Override
    public void delete(Feedback feedback) {
        if (feedback == null) {
            return;
        }
        documents.delete(COLLECTION, feedback.id());
    }

    /** 过滤 + 排序的单一入口，保证列表与总数使用完全相同的判定口径。 */
    private List<Feedback> matched(FeedbackQuery query) {
        return scan().stream()
                .filter(feedback -> matchesScope(feedback, query))
                .filter(feedback -> query.allows(feedback.status()))
                .filter(feedback -> query.openOnly() == null || feedback.open() == query.openOnly())
                .filter(feedback -> query.type() == null || feedback.type() == query.type())
                .filter(feedback -> query.severity() == null || feedback.severity() == query.severity())
                .filter(feedback -> matchesAssignee(feedback, query))
                .filter(feedback -> matchesKeyword(feedback, query))
                .filter(feedback -> matchesCreatedRange(feedback, query))
                .sorted(FEEDBACK_ORDER)
                .toList();
    }

    /** 数据范围：用户端由用例强制传入本人；管理端为空表示跨用户全量。 */
    private boolean matchesScope(Feedback feedback, FeedbackQuery query) {
        return query.authorId().isEmpty() || query.authorId().equals(feedback.authorId());
    }

    /** 处理人是集合：按处理人筛选取「在名单里」，待指派取「名单为空」。 */
    private boolean matchesAssignee(Feedback feedback, FeedbackQuery query) {
        if (Boolean.TRUE.equals(query.unassignedOnly())) {
            return feedback.assigneeIds().isEmpty();
        }
        return query.assigneeId().isEmpty() || feedback.assigneeIds().contains(query.assigneeId());
    }

    /** 关键词只覆盖用户可感知的文本：标题、描述与评论内容。 */
    private boolean matchesKeyword(Feedback feedback, FeedbackQuery query) {
        if (!query.hasKeyword()) {
            return true;
        }
        String keyword = query.keyword().toLowerCase();
        if (feedback.title().toLowerCase().contains(keyword)
                || feedback.description().toLowerCase().contains(keyword)) {
            return true;
        }
        return feedback.comments().stream()
                .anyMatch(comment -> comment.content().toLowerCase().contains(keyword));
    }

    private boolean matchesCreatedRange(Feedback feedback, FeedbackQuery query) {
        if (query.from() != null && feedback.createdAt() < query.from()) {
            return false;
        }
        return query.to() == null || feedback.createdAt() <= query.to();
    }

    private static <T> List<T> paged(List<T> source, int page, int size) {
        int from = Math.min(source.size(), (page - 1) * size);
        int to = Math.min(source.size(), from + size);
        if (from >= to) {
            return List.of();
        }
        return source.subList(from, to);
    }

    private List<Feedback> scan() {
        List<Feedback> all = new ArrayList<>();
        for (int page = 1; page <= MAX_SCAN_PAGES; page++) {
            List<Map<String, Object>> batch = documents.findAll(COLLECTION, page, SCAN_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> document : batch) {
                all.add(toFeedback(document));
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /** 未完结优先，其次严重程度降序，最后按更新时间倒序：让待受理的高危反馈浮到最前。 */
    private static final Comparator<Feedback> FEEDBACK_ORDER =
            Comparator.comparing((Feedback feedback) -> feedback.open() ? 0 : 1)
                    .thenComparing(feedback -> -feedback.severity().weight())
                    .thenComparing(Comparator.comparingLong(Feedback::updatedAt).reversed());

    private Map<String, Object> document(Feedback feedback) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", feedback.id());
        document.put("authorId", feedback.authorId());
        document.put("title", feedback.title());
        document.put("description", feedback.description());
        document.put("type", feedback.type().code());
        document.put("severity", feedback.severity().code());
        document.put("status", feedback.status().code());
        document.put("assigneeIds", new ArrayList<>(feedback.assigneeIds()));
        document.put("attachments", new ArrayList<>(feedback.attachments()));
        List<Map<String, Object>> comments = new ArrayList<>();
        for (FeedbackComment comment : feedback.comments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", comment.id());
            item.put("authorId", comment.authorId());
            item.put("authorRole", comment.authorRole().code());
            item.put("content", comment.content());
            item.put("internal", comment.internal());
            item.put("createdAt", comment.createdAt());
            comments.add(item);
        }
        document.put("comments", comments);
        List<Map<String, Object>> activities = new ArrayList<>();
        for (FeedbackActivity activity : feedback.activities()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("actorId", activity.actorId());
            item.put("action", activity.action().code());
            item.put("detail", activity.detail());
            item.put("createdAt", activity.createdAt());
            activities.add(item);
        }
        document.put("activities", activities);
        document.put("createdAt", feedback.createdAt());
        document.put("updatedAt", feedback.updatedAt());
        document.put("closedAt", feedback.closedAt());
        return document;
    }

    private Feedback toFeedback(Map<String, Object> document) {
        return new Feedback(
                string(document, "id"),
                string(document, "authorId"),
                string(document, "title"),
                string(document, "description"),
                FeedbackType.lenient(string(document, "type")),
                FeedbackSeverity.lenient(string(document, "severity")),
                FeedbackStatus.lenient(string(document, "status")),
                assignees(document),
                stringList(document, "attachments"),
                comments(document),
                activities(document),
                longValue(document, "createdAt") == null ? 0L : longValue(document, "createdAt"),
                longValue(document, "updatedAt") == null ? 0L : longValue(document, "updatedAt"),
                longValue(document, "closedAt")
        );
    }

    /**
     * 读取处理人名单。
     *
     * <p>兼容开发期留下的单处理人文档：只有新的 {@code assigneeIds} 字段缺失时，
     * 才回退读取旧的 {@code assigneeId}，避免历史数据在升级后「处理人凭空消失」。
     */
    private static List<String> assignees(Map<String, Object> document) {
        List<String> ids = stringList(document, "assigneeIds");
        if (!ids.isEmpty() || document.containsKey("assigneeIds")) {
            return ids;
        }
        String legacy = string(document, "assigneeId");
        if (legacy == null || legacy.isBlank() || "null".equals(legacy.trim())) {
            return List.of();
        }
        return List.of(legacy.trim());
    }

    private static List<FeedbackComment> comments(Map<String, Object> document) {
        List<FeedbackComment> result = new ArrayList<>();
        for (Map<String, Object> item : maps(document, "comments")) {
            String content = string(item, "content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String id = string(item, "id");
            result.add(new FeedbackComment(
                    id == null || id.isBlank() ? Ids.next() : id,
                    string(item, "authorId"),
                    FeedbackCommentRole.of(string(item, "authorRole")),
                    content,
                    Boolean.TRUE.equals(item.get("internal")),
                    longValue(item, "createdAt") == null ? 0L : longValue(item, "createdAt")
            ));
        }
        return result;
    }

    private static List<FeedbackActivity> activities(Map<String, Object> document) {
        List<FeedbackActivity> result = new ArrayList<>();
        for (Map<String, Object> item : maps(document, "activities")) {
            result.add(new FeedbackActivity(
                    string(item, "actorId"),
                    FeedbackActivityAction.of(string(item, "action")),
                    string(item, "detail") == null ? "" : string(item, "detail"),
                    longValue(item, "createdAt") == null ? 0L : longValue(item, "createdAt")
            ));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> document, String key) {
        Object raw = document.get(key);
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /** 读取字符串列表字段；缺字段或类型不符时按空列表处理，保证旧数据兼容。 */
    private static List<String> stringList(Map<String, Object> document, String key) {
        Object raw = document.get(key);
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            String value = item == null ? "" : String.valueOf(item).trim();
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Map<String, Object> document, String key) {
        Object value = document.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}
