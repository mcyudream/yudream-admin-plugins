package online.yudream.base.plugin.eduverify.infrastructure.repository;

import online.yudream.base.plugin.eduverify.domain.aggregate.VerifySettings;
import online.yudream.base.plugin.eduverify.domain.repo.VerifySettingsRepository;
import online.yudream.base.plugin.eduverify.infrastructure.support.DocValues;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.Map;

public class VerifySettingsDocumentRepository implements VerifySettingsRepository {

    private static final String COLLECTION = "settings";
    private static final String ID = "global";

    private final PluginDocumentStore documents;

    public VerifySettingsDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public VerifySettings get() {
        return documents.findById(COLLECTION, ID).map(this::toSettings).orElseGet(VerifySettings::defaults);
    }

    @Override
    public VerifySettings save(VerifySettings settings) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("emailEnabled", settings.emailEnabled());
        document.put("chsiEnabled", settings.chsiEnabled());
        document.put("manualEnabled", settings.manualEnabled());
        document.put("codeTtlMinutes", settings.codeTtlMinutes());
        document.put("codeResendSeconds", settings.codeResendSeconds());
        document.put("codeDailyLimit", settings.codeDailyLimit());
        document.put("validityDays", settings.validityDays());
        document.put("retentionDays", settings.retentionDays());
        document.put("chsiDailyLimit", settings.chsiDailyLimit());
        document.put("chsiReportUrlTemplate", settings.chsiReportUrlTemplate());
        document.put("chsiSelectors", settings.chsiSelectors() == null ? Map.of() : settings.chsiSelectors());
        document.put("emailTutorialMarkdown", settings.emailTutorialMarkdown());
        document.put("chsiTutorialMarkdown", settings.chsiTutorialMarkdown());
        document.put("manualTutorialMarkdown", settings.manualTutorialMarkdown());
        document.put("manualNotifyEnabled", settings.manualNotifyEnabled());
        document.put("manualNotifyGroups", settings.manualNotifyGroups() == null ? java.util.List.of() : settings.manualNotifyGroups());
        document.put("manualNotifyTemplate", settings.manualNotifyTemplate());
        document.put("chsiMailConfirmationEnabled", settings.chsiMailConfirmationEnabled());
        document.put("chsiMailboxId", settings.chsiMailboxId());
        document.put("chsiAllowedFromDomains", settings.chsiAllowedFromDomains());
        document.put("chsiMailKeywords", settings.chsiMailKeywords());
        document.put("chsiMailWaitMinutes", settings.chsiMailWaitMinutes());
        return toSettings(documents.save(COLLECTION, ID, document));
    }

    private VerifySettings toSettings(Map<String, Object> document) {
        VerifySettings defaults = VerifySettings.defaults();
        return new VerifySettings(
                DocValues.bool(document, "emailEnabled", defaults.emailEnabled()),
                DocValues.bool(document, "chsiEnabled", defaults.chsiEnabled()),
                DocValues.bool(document, "manualEnabled", defaults.manualEnabled()),
                DocValues.integer(document, "codeTtlMinutes", defaults.codeTtlMinutes()),
                DocValues.integer(document, "codeResendSeconds", defaults.codeResendSeconds()),
                DocValues.integer(document, "codeDailyLimit", defaults.codeDailyLimit()),
                DocValues.integer(document, "validityDays", defaults.validityDays()),
                DocValues.integer(document, "retentionDays", defaults.retentionDays()),
                DocValues.integer(document, "chsiDailyLimit", defaults.chsiDailyLimit()),
                DocValues.string(document, "chsiReportUrlTemplate"),
                DocValues.stringMap(document, "chsiSelectors"),
                stringOrDefault(document, "emailTutorialMarkdown", defaults.emailTutorialMarkdown()),
                stringOrDefault(document, "chsiTutorialMarkdown", defaults.chsiTutorialMarkdown()),
                stringOrDefault(document, "manualTutorialMarkdown", defaults.manualTutorialMarkdown()),
                DocValues.bool(document, "manualNotifyEnabled", defaults.manualNotifyEnabled()),
                notifyGroups(document),
                stringOrDefault(document, "manualNotifyTemplate", defaults.manualNotifyTemplate()),
                DocValues.bool(document, "chsiMailConfirmationEnabled", defaults.chsiMailConfirmationEnabled()),
                stringOrDefault(document, "chsiMailboxId", defaults.chsiMailboxId()),
                stringList(document, "chsiAllowedFromDomains", defaults.chsiAllowedFromDomains()),
                stringList(document, "chsiMailKeywords", defaults.chsiMailKeywords()),
                DocValues.integer(document, "chsiMailWaitMinutes", defaults.chsiMailWaitMinutes())
        );
    }

    private String stringOrDefault(Map<String, Object> document, String key, String defaultValue) {
        String value = DocValues.string(document, key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private java.util.List<String> stringList(Map<String, Object> document, String key, java.util.List<String> defaultValue) {
        Object value = document.get(key);
        if (!(value instanceof java.util.List<?> list)) {
            return defaultValue;
        }
        java.util.List<String> result = list.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        return result.isEmpty() ? defaultValue : result;
    }

    private java.util.List<VerifySettings.NotifyGroupTarget> notifyGroups(Map<String, Object> document) {
        return DocValues.mapList(document, "manualNotifyGroups").stream()
                .map(item -> new VerifySettings.NotifyGroupTarget(
                        DocValues.string(item, "connectionId"), DocValues.string(item, "groupId")))
                .filter(VerifySettings.NotifyGroupTarget::complete)
                .toList();
    }
}
