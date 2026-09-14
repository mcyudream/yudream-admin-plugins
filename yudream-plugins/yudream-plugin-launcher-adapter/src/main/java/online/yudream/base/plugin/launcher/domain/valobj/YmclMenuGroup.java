package online.yudream.base.plugin.launcher.domain.valobj;

import java.util.List;

/**
 * 启动器合并导航里的一级分组。pageCodes 指向 {@code LauncherPage.code}。
 */
public record YmclMenuGroup(
        String code,
        String title,
        String icon,
        List<String> pageCodes
) {
}
