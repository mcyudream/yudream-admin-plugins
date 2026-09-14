package online.yudream.base.plugin.launcher.api;

/**
 * 启动器侧数据源描述。由 provider 声明，启动器按数据源 code 拉取数据并渲染到对应页面。
 *
 * @param code      数据源 code（全局稳定）
 * @param title     数据源显示名（启动器调试/错误信息使用）
 * @param schemaVersion schema 版本，启动器按版本解析数据；provider 变更字段需升 MAJOR
 * @param pageCode  绑定的页面 code（{@link LauncherPage#code()}）
 */
public record LauncherDataSource(
        String code,
        String title,
        int schemaVersion,
        String pageCode
) {
}