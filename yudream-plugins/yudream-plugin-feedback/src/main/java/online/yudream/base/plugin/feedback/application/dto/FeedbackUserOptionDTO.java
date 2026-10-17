package online.yudream.base.plugin.feedback.application.dto;

/** 人员候选项：管理端指派处理人时的选择器数据源。 */
public record FeedbackUserOptionDTO(
        String id,
        String username,
        String nickname,
        String email,
        String avatar,
        String status
) {

    public String displayName() {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return username == null ? id : username;
    }
}
