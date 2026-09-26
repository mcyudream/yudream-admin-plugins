package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.function.Supplier;

/** 鉴权/错误映射共享工具（各 facade 复用，避免 Controller 内业务）。 */
public final class HttpGuards {

    private HttpGuards() {
    }

    public static PluginHttpResponse guarded(PluginHttpRequest request, PluginSecurityService security,
                                             String permission, Supplier<PluginHttpResponse> action) {
        PluginPrincipal principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return error(new McpanelBusinessException("unauthenticated", 401, "请先登录"));
        }
        if (permission != null && (security == null || !security.hasPermission(principal, permission))) {
            return error(new McpanelBusinessException("forbidden", 403, "缺少权限：" + permission));
        }
        try {
            return action.get();
        } catch (McpanelBusinessException error) {
            return error(error);
        } catch (IllegalArgumentException error) {
            // 保留具体原因（解析失败/参数校验），不再笼统吞成「请求参数不正确」。
            return error(new McpanelBusinessException("invalid-request", 400,
                    error.getMessage() == null ? "请求参数不正确" : error.getMessage()));
        } catch (LinkageError error) {
            // dev 目录加载缺 shade 依赖，或软依赖提供方晚于本插件加载（Class 链未接通）：
            // 返回明确信息而非宿主裸 500。
            System.err.println("[mcpanel] linkage error on " + request.method() + " " + request.path()
                    + ": " + error);
            return error(new McpanelBusinessException("internal-error", 500,
                    "面板类库缺失（dev 目录加载缺内置依赖，或软依赖提供方未就绪；可在开发者工具重载面板插件，或用打包 JAR 部署）："
                            + error.getClass().getSimpleName()));
        } catch (RuntimeException error) {
            // 宿主对插件端点未捕获异常只回 500 不打日志：这里自留堆栈，便于定位。
            System.err.println("[mcpanel] unhandled error on " + request.method() + " " + request.path()
                    + ": " + error);
            error.printStackTrace();
            return error(new McpanelBusinessException("internal-error", 500,
                    "面板内部错误：" + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : "（" + error.getMessage() + "）")));
        }
    }

    /** 玩家面（/me）：只要求登录态，数据范围在应用层按 principal 收口。 */
    public static PluginHttpResponse me(PluginHttpRequest request, Supplier<PluginHttpResponse> action) {
        PluginPrincipal principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return error(new McpanelBusinessException("unauthenticated", 401, "请先登录"));
        }
        try {
            return action.get();
        } catch (McpanelBusinessException error) {
            return error(error);
        } catch (IllegalArgumentException error) {
            return error(new McpanelBusinessException("invalid-request", 400,
                    error.getMessage() == null ? "请求参数不正确" : error.getMessage()));
        } catch (LinkageError error) {
            System.err.println("[mcpanel] linkage error on " + request.method() + " " + request.path()
                    + ": " + error);
            return error(new McpanelBusinessException("internal-error", 500,
                    "面板类库缺失（dev 目录加载缺内置依赖，或软依赖提供方未就绪；可在开发者工具重载面板插件，或用打包 JAR 部署）："
                            + error.getClass().getSimpleName()));
        } catch (RuntimeException error) {
            System.err.println("[mcpanel] unhandled error on " + request.method() + " " + request.path()
                    + ": " + error);
            error.printStackTrace();
            return error(new McpanelBusinessException("internal-error", 500,
                    "面板内部错误：" + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : "（" + error.getMessage() + "）")));
        }
    }

    public static PluginHttpResponse error(McpanelBusinessException error) {
        return PluginHttpResponse.rawJson(error.httpStatus(), java.util.Map.of(
                "code", error.code(),
                "message", error.getMessage() == null ? "" : error.getMessage()));
    }

    public static Long principalUserId(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        return principal == null ? null : principal.userId();
    }

    public static String actorOf(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        return principal == null || principal.userId() == null
                ? "anonymous" : "user:" + principal.userId();
    }

    public static String managePermission() {
        return McpanelPlugin.MANAGE_PERMISSION;
    }
}
