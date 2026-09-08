package online.yudream.base.plugin.eduverify.interfaces.request;

import online.yudream.base.plugin.eduverify.domain.aggregate.VerifySettings;

import java.util.List;
import java.util.Map;

public record SettingsSaveRequest(
        Boolean emailEnabled,
        Boolean chsiEnabled,
        Boolean manualEnabled,
        Integer codeTtlMinutes,
        Integer codeResendSeconds,
        Integer codeDailyLimit,
        Integer validityDays,
        Integer retentionDays,
        Integer chsiDailyLimit,
        String chsiReportUrlTemplate,
        Map<String, String> chsiSelectors,
        String emailTutorialMarkdown,
        String chsiTutorialMarkdown,
        String manualTutorialMarkdown,
        Boolean manualNotifyEnabled,
        List<VerifySettings.NotifyGroupTarget> manualNotifyGroups,
        String manualNotifyTemplate,
        Boolean chsiMailConfirmationEnabled,
        String chsiMailboxId,
        List<String> chsiAllowedFromDomains,
        List<String> chsiMailKeywords,
        Integer chsiMailWaitMinutes
) {
}
