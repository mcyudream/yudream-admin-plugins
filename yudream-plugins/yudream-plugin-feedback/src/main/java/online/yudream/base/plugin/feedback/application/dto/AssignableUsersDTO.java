package online.yudream.base.plugin.feedback.application.dto;

import java.util.List;

/**
 * 「可指派处理人」候选页。
 *
 * <p>宿主 SPI 不提供按权限反查用户，候选来自账号目录、未按管理权限预筛；{@code notice} 会提醒
 * 管理员确认所选成员可访问反馈管理页。{@code available=false} 表示账号目录不可用，此时
 * {@code records} 恒为空，调用方必须拒绝指派，而不是静默放开全员。
 */
public record AssignableUsersDTO(
        List<FeedbackUserOptionDTO> records,
        boolean available,
        String notice
) {
}
