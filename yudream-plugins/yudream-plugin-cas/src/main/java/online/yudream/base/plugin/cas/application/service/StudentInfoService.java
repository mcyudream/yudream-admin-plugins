package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.cas.domain.aggregate.StudentArchive;
import online.yudream.base.plugin.cas.domain.aggregate.StudentProfile;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.repo.StudentProfileRepository;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.domain.service.StudentArchiveQuery;
import online.yudream.base.plugin.cas.infrastructure.repository.StudentProfileDocumentRepository;
import online.yudream.base.plugin.cas.infrastructure.support.JsonSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 认证账号档案：CAS/OIDC 登录交换时把认证身份 upsert 成档案（以认证账号为主键）。
 *
 * <p>本插件不向 yudream-student-info 插件写入任何信息，也不再把认证属性映射成学院/班级：
 * 管理端展示的学院与班级一律从学生档案插件按学号读取（只读）。记录动作 best-effort，
 * 档案写入失败不影响登录主流程。</p>
 */
public final class StudentInfoService {

    /** 姓名候选键名（学校端字段未知时按序探测）。 */
    private static final List<String> NAME_KEYS = List.of("name", "cn", "displayName");
    private static final List<String> EMAIL_KEYS = List.of("email", "mail");
    private static final List<String> PHONE_KEYS = List.of("phone", "mobile", "telephone");

    private final StudentProfileDocumentRepository profiles;
    /** 宿主绑定查询，可为 null（宿主不支持时降级）。 */
    private final BindingQueryService bindings;
    /** 学生档案插件只读查询，可为 null（等同不可用）。 */
    private final StudentArchiveQuery archives;

    public StudentInfoService(StudentProfileRepository profiles) {
        this(profiles, null, null);
    }

    public StudentInfoService(
            StudentProfileRepository profiles,
            BindingQueryService bindings,
            StudentArchiveQuery archives
    ) {
        // 搜索能力是文档仓库实现细节，接口不暴露全量扫描
        if (!(profiles instanceof StudentProfileDocumentRepository documentRepository)) {
            throw new IllegalArgumentException("学生档案仓库必须基于文档存储实现");
        }
        this.profiles = documentRepository;
        this.bindings = bindings;
        this.archives = archives;
    }

    /** 登录交换时调用：upsert 认证档案。任何异常都不应中断登录。 */
    public void record(SsoProtocolClient.ExternalIdentity identity, SsoProtocol protocol) {
        try {
            Map<String, String> attributes = identity.attributes();
            Optional<StudentProfile> existing = profiles.find(identity.socialUid());
            long now = System.currentTimeMillis();
            StudentProfile profile = new StudentProfile(
                    identity.socialUid(),
                    pick(NAME_KEYS, attributes, existing.map(StudentProfile::name).orElse(""), identity.nickname(), identity.socialUid()),
                    pick(EMAIL_KEYS, attributes, existing.map(StudentProfile::email).orElse("")),
                    pick(PHONE_KEYS, attributes, existing.map(StudentProfile::phone).orElse("")),
                    protocol == null ? "" : protocol.name(),
                    attributes.isEmpty() ? existing.map(StudentProfile::rawAttributes).orElse("") : JsonSupport.write(attributes),
                    existing.map(StudentProfile::firstSeenAt).orElse(now),
                    now,
                    existing.map(p -> p.loginCount() + 1L).orElse(1L)
            );
            profiles.save(profile);
        } catch (RuntimeException e) {
            System.err.println("[cas] 学生信息记录失败（不影响登录）: " + e.getMessage());
        }
    }

    public Map<String, Object> page(int page, int size, String keyword) {
        List<StudentProfile> items;
        long total;
        if (keyword != null && !keyword.isBlank()) {
            items = profiles.search(keyword);
            total = items.size();
            int fromIndex = Math.min((Math.max(page, 1) - 1) * Math.max(size, 1), items.size());
            int toIndex = Math.min(fromIndex + Math.max(size, 1), items.size());
            items = items.subList(fromIndex, toIndex);
        } else {
            items = profiles.page(page, size);
            total = profiles.count();
        }
        List<Map<String, Object>> view = items.stream().map(StudentInfoService::toMap).toList();
        for (Map<String, Object> item : view) {
            attachExternal(item);
        }
        return Map.of(
                "items", view,
                "total", total,
                "page", Math.max(page, 1),
                "size", Math.max(size, 1)
        );
    }

    public Optional<Map<String, Object>> detail(String socialUid) {
        return profiles.find(socialUid).map(profile -> {
            Map<String, Object> view = toMap(profile);
            attachExternal(view);
            return view;
        });
    }

    /** 学生档案插件是否可用（管理端「学院 / 班级」列能否取值）。 */
    public boolean archiveAvailable() {
        return archives != null && archives.available();
    }

    private static Map<String, Object> toMap(StudentProfile profile) {
        return profile.toMap();
    }

    /**
     * 附加外部信息：本站在账号绑定（学工号 → 本站账号）与学生档案插件里的学院/班级。
     * 两项都是 best-effort，缺失只影响该条数据的展示。
     */
    private void attachExternal(Map<String, Object> item) {
        String socialUid = stringOf(item.get("socialUid"));
        if (bindings != null) {
            item.put("binding", bindings.lookup(platformType(item.get("protocol")), socialUid));
        }
        item.put("archive", archiveView(socialUid));
    }

    private Map<String, Object> archiveView(String socialUid) {
        Map<String, Object> view = new LinkedHashMap<>();
        boolean available = archives != null && archives.available();
        view.put("available", available);
        if (!available) {
            view.put("filled", false);
            view.put("message", "未安装或未启用学生档案插件（yudream-student-info）");
            return view;
        }
        Optional<StudentArchive> archive = socialUid.isBlank()
                ? Optional.empty()
                : archives.findByStudentNo(socialUid);
        view.put("filled", archive.isPresent());
        archive.ifPresent(value -> {
            view.put("studentName", value.name());
            view.put("className", value.className());
            view.put("college", value.college());
        });
        if (archive.isEmpty()) {
            view.put("message", "该学号尚未在学生档案插件里填写");
        }
        return view;
    }

    /**
     * 绑定查询的 platformType = 宿主登记该绑定时使用的协议类型码。
     * 档案按登录当时的协议存储（CAS/OIDC），因此优先用档案自身的协议，避免管理员中途切换协议后查不到历史绑定。
     */
    private static String platformType(Object protocol) {
        String value = stringOf(protocol);
        return value.isBlank() ? SsoProtocol.CAS.typeCode() : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 取属性值：按候选键名探测；都没有则回退旧值/兜底值。 */
    private static String pick(List<String> candidates, Map<String, String> attributes, String... fallbacks) {
        for (String candidate : candidates) {
            String value = attributes.get(candidate);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        for (String fallback : fallbacks) {
            if (fallback != null && !fallback.isBlank()) {
                return fallback.trim();
            }
        }
        return "";
    }
}
