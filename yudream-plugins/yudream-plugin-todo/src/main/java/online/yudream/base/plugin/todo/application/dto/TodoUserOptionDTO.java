package online.yudream.base.plugin.todo.application.dto;

/** 人员候选项：管理员派发任务时的人员选择器数据源。 */
public record TodoUserOptionDTO(
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
