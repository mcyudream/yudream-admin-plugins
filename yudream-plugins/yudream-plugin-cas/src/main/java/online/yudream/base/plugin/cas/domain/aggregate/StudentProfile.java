package online.yudream.base.plugin.cas.domain.aggregate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学生信息档案：以学工号（socialUid，即认证中心返回的账号）为主键，在每次 CAS/OIDC 登录交换时 upsert。
 *
 * <p>学院 / 班级不再由本插件从认证属性映射或保存：本插件不向 yudream-student-info 插件写入任何信息，
 * 学院与班级统一从学生档案插件按学号读取；档案只保留认证身份（姓名、邮箱、电话）与登录统计，
 * rawAttributes 保留原始属性 JSON，便于管理员核对学校到底返回了什么。</p>
 */
public final class StudentProfile {

    private final String socialUid;
    private final String name;
    private final String email;
    private final String phone;
    private final String protocol;
    private final String rawAttributes;
    private final long firstSeenAt;
    private final long lastSeenAt;
    private final long loginCount;

    public StudentProfile(
            String socialUid,
            String name,
            String email,
            String phone,
            String protocol,
            String rawAttributes,
            long firstSeenAt,
            long lastSeenAt,
            long loginCount
    ) {
        this.socialUid = socialUid;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.protocol = protocol;
        this.rawAttributes = rawAttributes;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.loginCount = loginCount;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("socialUid", socialUid);
        document.put("name", name);
        document.put("email", email);
        document.put("phone", phone);
        document.put("protocol", protocol);
        document.put("rawAttributes", rawAttributes);
        document.put("firstSeenAt", firstSeenAt);
        document.put("lastSeenAt", lastSeenAt);
        document.put("loginCount", loginCount);
        return document;
    }

    public String socialUid() {
        return socialUid;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public String protocol() {
        return protocol;
    }

    public String rawAttributes() {
        return rawAttributes;
    }

    public long firstSeenAt() {
        return firstSeenAt;
    }

    public long lastSeenAt() {
        return lastSeenAt;
    }

    public long loginCount() {
        return loginCount;
    }
}
