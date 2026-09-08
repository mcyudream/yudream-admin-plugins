package online.yudream.base.plugin.eduverify.interfaces.request;

public record DomainSaveRequest(
        String domain,
        String chineseName,
        String englishName,
        Boolean enabled,
        String source
) {
}
