package online.yudream.base.plugin.eduverify.domain.aggregate;

import java.util.List;
import java.util.Map;

/** 渠道与策略配置（单例文档，id = global）。 */
public record VerifySettings(
        boolean emailEnabled,
        boolean chsiEnabled,
        boolean manualEnabled,
        int codeTtlMinutes,
        int codeResendSeconds,
        int codeDailyLimit,
        int validityDays,
        int retentionDays,
        int chsiDailyLimit,
        String chsiReportUrlTemplate,
        Map<String, String> chsiSelectors,
        String emailTutorialMarkdown,
        String chsiTutorialMarkdown,
        String manualTutorialMarkdown,
        boolean manualNotifyEnabled,
        List<NotifyGroupTarget> manualNotifyGroups,
        String manualNotifyTemplate,
        boolean chsiMailConfirmationEnabled,
        String chsiMailboxId,
        List<String> chsiAllowedFromDomains,
        List<String> chsiMailKeywords,
        int chsiMailWaitMinutes
) {

    public record NotifyGroupTarget(String connectionId, String groupId) {
        public NotifyGroupTarget {
            connectionId = connectionId == null ? "" : connectionId.trim();
            groupId = groupId == null ? "" : groupId.trim();
        }

        public boolean complete() {
            return !connectionId.isBlank() && !groupId.isBlank();
        }
    }

    public static VerifySettings defaults() {
        return new VerifySettings(true, true, true, 10, 60, 6, 365, 30, 5, null, Map.of(),
                "# 教育邮箱核验\n\n填写学校提供的教育邮箱。邮箱域名命中白名单后即可完成核验，并使用同一邮箱注册。\n\n若域名未收录，请改用学信网或人工审核。",
                "# 学信网在线验证码\n\n1. 在学信网申请《学籍在线验证报告》或《学历证书电子注册备案表》。\n2. 填写报告中的 16 位在线验证码、真实姓名和学校。系统会读取官方报告页核验。\n3. 若管理员开启了邮件二次确认：请在学信网报告页使用官方「发送到邮箱」按钮，把报告发到站点指定的收件邮箱。不要让他人代发，也不要自行转发。系统会核对发件域、验证码和关键词，两步都通过后才可注册。\n4. 官方报告页读取失败时可转入人工审核。",
                "# 人工审核\n\n填写真实姓名和学校，并上传学信网报告、学生证、校园卡、录取通知书或毕业证等证明材料。\n\n审核通过后，请使用同一邮箱完成注册。",
                false, List.of(),
                "【学历认证待审核】\n邮箱：{email}\n姓名：{realName}\n学校：{schoolName}\n材料：{materialCount} 份\n请在学历认证管理中处理。",
                false, "", List.of("chsi.com.cn"), List.of("在线验证报告"), 15
        );
    }
}
