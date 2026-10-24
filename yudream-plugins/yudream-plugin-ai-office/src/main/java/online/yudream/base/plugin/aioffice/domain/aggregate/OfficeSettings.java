package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.LinkedHashMap;
import java.util.Map;

/** 站点级设置：组织档案（schema 驱动）、财务周期、AI 配置与写作风格。单例，固定 id=default。 */
public record OfficeSettings(
        String profileSchemaCode,
        Map<String, Object> profile,
        String financeCycle,
        String currency,
        String styleGuide,
        String aiProviderCode,
        String aiModelCode,
        Boolean wordImageEmbed,
        String activePresetCode,
        long updatedAt
) {
    public static final String ID = "default";

    public OfficeSettings {
        profileSchemaCode = text(profileSchemaCode, "org-club");
        profile = profile == null ? Map.of() : new LinkedHashMap<>(profile);
        financeCycle = text(financeCycle, "MONTHLY");
        currency = text(currency, "CNY");
        styleGuide = text(styleGuide, "");
        aiProviderCode = text(aiProviderCode, "");
        aiModelCode = text(aiModelCode, "");
        // 默认开启；宿主引擎低于 2.38.0（不支持嵌图）时由站点关闭
        wordImageEmbed = wordImageEmbed == null ? Boolean.TRUE : wordImageEmbed;
        activePresetCode = text(activePresetCode, "");
    }

    public static OfficeSettings empty() {
        return new OfficeSettings("org-club", Map.of(), "MONTHLY", "CNY", "", "", "", null, "", 0);
    }

    public boolean embedImages() {
        return Boolean.TRUE.equals(wordImageEmbed);
    }

    public OfficeSettings withProfile(Map<String, Object> newProfile) {
        return new OfficeSettings(profileSchemaCode, newProfile, financeCycle, currency, styleGuide,
                aiProviderCode, aiModelCode, wordImageEmbed, activePresetCode, System.currentTimeMillis());
    }

    public OfficeSettings withMeta(String profileSchema, String cycle, String currency, String styleGuide,
                                   String provider, String model) {
        return new OfficeSettings(profileSchema, profile, cycle, currency, styleGuide, provider, model,
                wordImageEmbed, activePresetCode, System.currentTimeMillis());
    }

    public OfficeSettings withImages(Boolean embed) {
        return new OfficeSettings(profileSchemaCode, profile, financeCycle, currency, styleGuide,
                aiProviderCode, aiModelCode, embed, activePresetCode, System.currentTimeMillis());
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
