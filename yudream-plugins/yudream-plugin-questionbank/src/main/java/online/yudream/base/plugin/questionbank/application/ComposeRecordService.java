package online.yudream.base.plugin.questionbank.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import online.yudream.base.plugin.questionbank.domain.ComposeRecord;
import online.yudream.base.plugin.questionbank.infrastructure.ComposeRecordRepository;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;

/**
 * 组卷记录用例：保存/归属分页/删除/分享。
 * 归属隔离：COMPOSE 用户仅能访问自己的记录；MANAGE 可跨用户列表与管理。
 */
public final class ComposeRecordService {
    private static final int MAX_QUESTION_IDS = 200;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ComposeRecordRepository records;

    public ComposeRecordService(ComposeRecordRepository records) {
        this.records = records;
    }

    public ComposeRecord save(String ownerId, String ownerName, String title, String description,
                              boolean withAnswers, List<String> questionIds) {
        List<String> ids = normalizeQuestionIds(questionIds);
        long now = System.currentTimeMillis();
        ComposeRecord record = new ComposeRecord(Ids.newId(), normalizeTitle(title),
                normalizeDescription(description), withAnswers, ids, ownerId, ownerName, null, 0L, now, now);
        records.save(record);
        return record;
    }

    /** 我的组卷记录（COMPOSE 用户数据面，ownerId 只能来自 principal）。 */
    public PageResult<ComposeRecord> listMine(String ownerId, String keyword, int page, int size) {
        return page(filter(records.listAll().stream()
                .filter(record -> ownerId.equals(record.ownerId()))
                .toList(), keyword), page, size);
    }

    /** 全部组卷记录（仅 MANAGE 调用方进入）。 */
    public PageResult<ComposeRecord> listAll(String keyword, String ownerId, int page, int size) {
        List<ComposeRecord> all = records.listAll().stream()
                .filter(record -> ownerId == null || ownerId.isBlank() || ownerId.equals(record.ownerId()))
                .toList();
        return page(filter(all, keyword), page, size);
    }

    public ComposeRecord require(String id) {
        return records.findById(id).orElseThrow(() -> new NotFoundException("组卷记录不存在"));
    }

    /** 归属校验：owner 本人或可管理（manage=true 由调用方按 MANAGE 权限判定）。 */
    public ComposeRecord requireAccessible(String id, String operatorId, boolean manage) {
        ComposeRecord record = require(id);
        if (!manage && !record.ownerId().equals(operatorId)) {
            throw new IllegalStateException("只能操作自己的组卷记录");
        }
        return record;
    }

    public void delete(String id, String operatorId, boolean manage) {
        requireAccessible(id, operatorId, manage);
        records.delete(id);
    }

    public ComposeRecord share(String id, String operatorId, boolean manage) {
        ComposeRecord record = requireAccessible(id, operatorId, manage);
        String token = record.shared() ? record.shareToken()
                : UUID.randomUUID().toString().replace("-", "");
        ComposeRecord shared = record.withShare(token, System.currentTimeMillis());
        records.save(shared);
        return shared;
    }

    public ComposeRecord unshare(String id, String operatorId, boolean manage) {
        ComposeRecord record = requireAccessible(id, operatorId, manage);
        ComposeRecord unshared = record.withoutShare();
        records.save(unshared);
        return unshared;
    }

    /** 分享公开页：凭 token 查找，未分享或 token 失效返回 null。 */
    public ComposeRecord findByShareToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim();
        return records.listAll().stream()
                .filter(ComposeRecord::shared)
                .filter(record -> record.shareToken().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private List<ComposeRecord> filter(List<ComposeRecord> source, String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return source;
        }
        return source.stream()
                .filter(record -> record.title().toLowerCase(Locale.ROOT).contains(normalized)
                        || (record.description() != null
                                && record.description().toLowerCase(Locale.ROOT).contains(normalized))
                        || (record.ownerName() != null
                                && record.ownerName().toLowerCase(Locale.ROOT).contains(normalized)))
                .toList();
    }

    private PageResult<ComposeRecord> page(List<ComposeRecord> filtered, int page, int size) {
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size());
    }

    private List<String> normalizeQuestionIds(List<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            throw new IllegalArgumentException("组卷记录至少需要 1 道题");
        }
        List<String> ids = new LinkedHashSet<>(questionIds).stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("组卷记录至少需要 1 道题");
        }
        if (ids.size() > MAX_QUESTION_IDS) {
            throw new IllegalArgumentException("一次组卷最多 " + MAX_QUESTION_IDS + " 道题");
        }
        return ids;
    }

    private String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("试卷标题不能为空");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("试卷标题不能超过 " + MAX_TITLE_LENGTH + " 字");
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalized = description.trim();
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("试卷说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 字");
        }
        return normalized;
    }
}
