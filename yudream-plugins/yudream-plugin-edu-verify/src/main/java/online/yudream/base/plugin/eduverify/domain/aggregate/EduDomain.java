package online.yudream.base.plugin.eduverify.domain.aggregate;

/** 教育邮箱域名白名单与学校映射。匹配规则：等于条目或以其为后缀。 */
public record EduDomain(
        String domain,
        String chineseName,
        String englishName,
        boolean enabled,
        String source,
        long createdAt,
        long updatedAt
) {

    public boolean matches(String emailDomain) {
        if (!enabled || emailDomain == null) {
            return false;
        }
        return emailDomain.equals(domain) || emailDomain.endsWith("." + domain);
    }

    public boolean manuallyMaintained() {
        return "MANUAL".equalsIgnoreCase(source);
    }
}
