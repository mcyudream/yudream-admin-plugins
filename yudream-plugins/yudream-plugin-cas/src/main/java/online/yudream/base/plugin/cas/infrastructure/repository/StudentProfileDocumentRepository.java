package online.yudream.base.plugin.cas.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.cas.domain.aggregate.StudentProfile;
import online.yudream.base.plugin.cas.domain.repo.StudentProfileRepository;
import online.yudream.base.plugin.cas.infrastructure.support.DocValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StudentProfileDocumentRepository implements StudentProfileRepository {

    static final String COLLECTION = "cas-students";
    /** 关键词搜索时的最大扫描页数，防止全量遍历拖垮存储。 */
    static final int MAX_SCAN_PAGES = 50;
    static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public StudentProfileDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public Optional<StudentProfile> find(String socialUid) {
        if (socialUid == null || socialUid.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, socialUid.trim()).map(this::toProfile);
    }

    @Override
    public StudentProfile save(StudentProfile profile) {
        Map<String, Object> saved = documents.save(COLLECTION, profile.socialUid(), DocValues.stripNulls(profile.toMap()));
        return toProfile(saved);
    }

    @Override
    public List<StudentProfile> page(int page, int size) {
        List<StudentProfile> result = new ArrayList<>();
        for (Map<String, Object> document : documents.findAll(COLLECTION, Math.max(page, 1), Math.max(size, 1))) {
            result.add(toProfile(document));
        }
        return result;
    }

    @Override
    public List<StudentProfile> findBySocialUid(String socialUid) {
        List<StudentProfile> result = new ArrayList<>();
        if (socialUid == null || socialUid.isBlank()) {
            return result;
        }
        for (Map<String, Object> document : documents.findByField(COLLECTION, "socialUid", socialUid.trim(), 1, 50)) {
            result.add(toProfile(document));
        }
        return result;
    }

    @Override
    public long count() {
        return documents.count(COLLECTION);
    }

    /**
     * 关键词搜索：分页扫描集合并做内存过滤（学号/姓名/邮箱/电话包含匹配）。
     * 上限 MAX_SCAN_PAGES * SCAN_PAGE_SIZE 条，超出部分不参与搜索。
     */
    @Override
    public List<StudentProfile> search(String keyword) {
        List<StudentProfile> result = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return result;
        }
        String needle = keyword.trim().toLowerCase();
        for (int pageIndex = 1; pageIndex <= MAX_SCAN_PAGES; pageIndex++) {
            List<Map<String, Object>> batch = documents.findAll(COLLECTION, pageIndex, SCAN_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> document : batch) {
                StudentProfile profile = toProfile(document);
                if (matches(profile, needle)) {
                    result.add(profile);
                }
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return result;
    }

    private static boolean matches(StudentProfile profile, String needle) {
        return contains(profile.socialUid(), needle)
                || contains(profile.name(), needle)
                || contains(profile.email(), needle)
                || contains(profile.phone(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private StudentProfile toProfile(Map<String, Object> document) {
        long loginCount = 0;
        Object raw = document.get("loginCount");
        if (raw instanceof Number number) {
            loginCount = number.longValue();
        } else if (raw != null) {
            loginCount = Long.parseLong(String.valueOf(raw));
        }
        return new StudentProfile(
                orEmpty(DocValues.string(document, "socialUid")),
                orEmpty(DocValues.string(document, "name")),
                orEmpty(DocValues.string(document, "email")),
                orEmpty(DocValues.string(document, "phone")),
                orEmpty(DocValues.string(document, "protocol")),
                orEmpty(DocValues.string(document, "rawAttributes")),
                longValue(document, "firstSeenAt"),
                longValue(document, "lastSeenAt"),
                loginCount
        );
    }

    private static long longValue(Map<String, Object> document, String key) {
        Object raw = document.get(key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw != null) {
            try {
                return Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
