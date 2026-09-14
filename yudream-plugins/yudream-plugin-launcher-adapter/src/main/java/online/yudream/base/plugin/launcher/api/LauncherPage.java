package online.yudream.base.plugin.launcher.api;

import java.util.Map;

/**
 * 启动器侧页面贡献描述。
 * <p>
 * type 决定启动器使用哪种内置渲染器（协议 v2 称之为 renderer，字段名保持 type 不变）：
 * <ul>
 *     <li>{@code server-list}：服务器卡片网格（由 minecraft-server 以 LauncherProvider 贡献，适配器不依赖该插件）</li>
 *     <li>{@code article-list}：资讯/公告流（timeline、news）</li>
 *     <li>{@code card-grid}：通用卡片网格（项目、皮肤库；旧值 {@code grid} 作为别名保留）</li>
 *     <li>{@code activity}：活动中心（timeline）</li>
 *     <li>{@code skin-closet}：皮肤衣柜（yudream-skin，强交互走 extension-type）</li>
 *     <li>{@code pack-catalog}：整合包目录（launcher-adapter 自身贡献）</li>
 *     <li>{@code rich-text} / {@code iframe}：静态内容页</li>
 *     <li>{@code extension-type}：复杂交互，由启动器按 extensionPackage 加载 YMCL 扩展包</li>
 * </ul>
 * 未识别的 type 由启动器渲染占位页，禁止崩溃。
 *
 * @param code        页面 code，全局稳定
 * @param title       显示标题
 * @param icon        图标（与宿主插件 icon 命名一致，i-ri:xxx 或 i-mdi:xxx）
 * @param navOrder    导航顺序，升序
 * @param type        渲染器类型
 * @param path        启动器内的路径（如 "/domain/activity"）
 * @param dataSourceCode 关联的数据源 code（{@link LauncherDataSource#code()}），渲染时拉取
 * @param extensionPackageId  当 type=extension-type 时，指向 launcher-adapter 下发的 YMCL 扩展包 id
 * @param providerCode 贡献该页的 {@link LauncherProvider#providerCode()}，启动器拉 pageData 时必须用它，禁止从 page/dataSource code 猜测
 * @param props       渲染器专属配置（协议 v2 {@code view.props}），启动器透传不解释
 * @param requiresPermission 访问该页所需的权限码（如 {@code plugin:minecraft-server:view}）；
 *                    空表示公开。适配器在构建 manifest 时按当前用户权限裁剪不满足的页面
 */
public record LauncherPage(
        String code,
        String title,
        String icon,
        int navOrder,
        String type,
        String path,
        String dataSourceCode,
        String extensionPackageId,
        String providerCode,
        Map<String, Object> props,
        String requiresPermission
) {
    public LauncherPage {
        props = props == null ? Map.of() : Map.copyOf(props);
        requiresPermission = requiresPermission == null ? "" : requiresPermission.trim();
    }

    /**
     * 协议 v1 签名：无 props / requiresPermission。
     */
    public LauncherPage(String code, String title, String icon, int navOrder, String type, String path,
                        String dataSourceCode, String extensionPackageId, String providerCode) {
        this(code, title, icon, navOrder, type, path, dataSourceCode, extensionPackageId, providerCode,
                Map.of(), "");
    }

    /**
     * 兼容未携带 {@code providerCode} 的旧消费方字节码。
     * 新代码必须传贡献方编码；聚合器会把空值补成 {@link LauncherProvider#providerCode()}。
     */
    public LauncherPage(String code, String title, String icon, int navOrder, String type, String path,
                        String dataSourceCode, String extensionPackageId) {
        this(code, title, icon, navOrder, type, path, dataSourceCode, extensionPackageId, "");
    }
}
