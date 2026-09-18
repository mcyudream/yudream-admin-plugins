package online.yudream.base.plugin.activityproof.application.dto;

/**
 * 一台下游子服，供编辑页的子服选择使用。
 *
 * <p>{@code defaultServer} 是「新玩家落在哪台」的标记，界面用它把默认入口标出来；
 * {@code sensor} 表示该子服上是否已确认传感器——没装传感器就收不到这台子服的时长，
 * 选它之前值得先让操作者知道。
 */
public record ActivityProofSubServerDTO(
        String name,
        String address,
        int online,
        boolean sensor,
        boolean defaultServer,
        int sort
) {
}
