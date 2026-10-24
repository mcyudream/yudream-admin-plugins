package online.yudream.base.plugin.aioffice.domain.aggregate;

import java.util.LinkedHashMap;
import java.util.List;
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
        List<CredentialKind> credentialKinds,
        String activePresetCode,
        long updatedAt
) {

    /** 凭证类型字典（数据驱动）：站点可增删改标签，code 用于归一。 */
    public record CredentialKind(String code, String label) {
        public CredentialKind {
            code = code == null ? "" : code.trim().toLowerCase(java.util.Locale.ROOT);
            if (!code.matches("[a-z][a-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("凭证类型编码只能用小写字母/数字/下划线：" + code);
            }
            label = label == null || label.isBlank() ? code : label.trim();
        }
    }

    public static List<CredentialKind> defaultCredentialKinds() {
        return List.of(new CredentialKind("invoice", "发票"),
                new CredentialKind("screenshot", "支付截图"),
                new CredentialKind("proof", "其他凭证"));
    }

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
        credentialKinds = credentialKinds == null || credentialKinds.isEmpty()
                ? defaultCredentialKinds() : List.copyOf(credentialKinds);
        activePresetCode = text(activePresetCode, "");
    }

    /** 中性默认：站点差异由预设包/配置携带，代码不预设任何行业形态。 */
    public static OfficeSettings empty() {
        return new OfficeSettings("", Map.of(), "MONTHLY", "CNY", "", "", "", null, null, "", 0);
    }

    public boolean embedImages() {
        return Boolean.TRUE.equals(wordImageEmbed);
    }

    public OfficeSettings withProfile(Map<String, Object> newProfile) {
        return new OfficeSettings(profileSchemaCode, newProfile, financeCycle, currency, styleGuide,
                aiProviderCode, aiModelCode, wordImageEmbed, credentialKinds, activePresetCode,
                System.currentTimeMillis());
    }

    public OfficeSettings withMeta(String profileSchema, String cycle, String currency, String styleGuide,
                                   String provider, String model) {
        return new OfficeSettings(profileSchema, profile, cycle, currency, styleGuide, provider, model,
                wordImageEmbed, credentialKinds, activePresetCode, System.currentTimeMillis());
    }

    public OfficeSettings withImages(Boolean embed) {
        return new OfficeSettings(profileSchemaCode, profile, financeCycle, currency, styleGuide,
                aiProviderCode, aiModelCode, embed, credentialKinds, activePresetCode, System.currentTimeMillis());
    }

    public OfficeSettings withCredentialKinds(List<CredentialKind> kinds) {
        return new OfficeSettings(profileSchemaCode, profile, financeCycle, currency, styleGuide,
                aiProviderCode, aiModelCode, wordImageEmbed, kinds, activePresetCode, System.currentTimeMillis());
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
