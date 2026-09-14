package online.yudream.base.plugin.launcher.api;

import java.util.List;

/**
 * 启动器侧内容贡献扩展点。
 * <p>
 * 宿主任意插件实现本接口并通过 {@code PluginContext.registerExtension(LauncherProvider.class, this)}
 * 注册；launcher-adapter 会在构建 manifest 时按优先级聚合所有实现的页面与数据源。
 * <p>
 * 缺失或禁用时启动器侧对应页面/数据降级为空状态，不阻断整体启动。
 */
public interface LauncherProvider {

    /**
     * 当前实现的归属插件 code（{@code plugin.yml} 中的 code）。
     * <p>
     * 启动器在拉取页面数据时通过这个 code 路由到具体 provider；建议显式覆盖以与宿主 plugin code 对齐。
     * 默认实现按类名包名推断：当且仅当 provider 类位于 {@code online.yudream.base.plugin.<pluginCode>...}
     * 包下时返回第二段（kebab-case 风格 plugin code 与包名一致时有效）；其余情况必须显式覆盖。
     */
    default String providerCode() {
        String pkg = getClass().getPackageName();
        int idx = pkg.indexOf(".plugin.");
        if (idx < 0) return getClass().getSimpleName();
        String suffix = pkg.substring(idx + ".plugin.".length());
        int next = suffix.indexOf('.');
        return next < 0 ? suffix : suffix.substring(0, next);
    }

    /**
     * 该贡献在 YMCL 公开客户端上需要的 OAuth 范围。
     * <p>
     * 默认 {@code plugin:{providerCode}:view} 与 {@code plugin:{providerCode}:manage}。
     * 适配器在自身启用、以及各贡献插件启用时把这些范围合并进 ymcl 客户端，禁止在适配器里写死其它插件的权限码。
     */
    default List<String> oauthScopes() {
        return YmclPublicOAuth.pluginScopes(providerCode());
    }

    /**
     * 当前实现声明的页面列表。每个页面在启动器导航中占据一个槽位，按 {@link LauncherPage#navOrder()} 升序排列。
     */
    List<LauncherPage> pages();

    /**
     * 当前实现声明的数据源列表。数据源绑定到某个页面，由启动器在渲染时按需拉取。
     */
    List<LauncherDataSource> dataSources();

    /**
     * 启动器拉取该实现贡献的页面数据。
     *
     * @param dataSourceCode 数据源 code（{@link LauncherDataSource#code()}）
     * @param context        启动器侧的请求上下文（当前用户、域、参数等）
     * @return 任意可序列化为 JSON 的数据；推荐使用 Map 或 List，保持 schema 稳定
     */
    Object fetchData(String dataSourceCode, LauncherContextView context);

    /**
     * 执行启动器数据信封里声明的 {@code remote} 动作（协议 v2 §4）。
     * <p>
     * 动作 code 由 provider 自定义，通常与页面渲染数据一同下发在信封的 {@code actions}/{@code itemActions}
     * 中。适配器统一路由 {@code /v2/action/{providerCode}/{actionCode}} 到本方法；鉴权在调用前完成，
     * provider 可按 {@link LauncherActionContext#permissions()} 再做数据级判断。
     *
     * @param actionCode 动作 code
     * @param context    动作调用上下文（当前用户、权限、参数）
     * @return 动作响应（协议约定 {@code {toast, refresh}} 二键均可选；返回 null 视为静默成功）
     */
    default Object executeAction(String actionCode, LauncherActionContext context) {
        throw new UnsupportedOperationException("动作不支持：" + actionCode);
    }
}