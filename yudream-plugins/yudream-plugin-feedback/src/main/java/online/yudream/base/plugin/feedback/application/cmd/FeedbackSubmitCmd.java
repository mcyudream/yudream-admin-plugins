package online.yudream.base.plugin.feedback.application.cmd;

import java.util.List;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackType;

/**
 * 反馈内容命令（提交与修改共用）。
 *
 * <p>{@code type} / {@code severity} 在组装阶段解析成枚举：空值走枚举默认值，非法取值直接拒绝，
 * 避免带着脏值进入领域后产生难以定位的 400。
 */
public record FeedbackSubmitCmd(
        String title,
        String description,
        FeedbackType type,
        FeedbackSeverity severity,
        List<String> attachments
) {

    public static FeedbackSubmitCmd of(String title, String description, String type, String severity,
                                       List<String> attachments) {
        return new FeedbackSubmitCmd(
                title,
                description,
                FeedbackType.of(type),
                FeedbackSeverity.of(severity),
                attachments == null ? List.of() : attachments
        );
    }
}
