package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAP §6.4 会话与上下文端点。
 *
 * - GET /v1/session：把请求携带的域 token（Sa-Token 裸 token 或 OAuth
 *   access token，宿主统一解析为 principal）换取规范化的 SessionInfo；
 * - POST /v1/context/switch：切换当前用户的部门或角色上下文。SPI 不暴露
 *   宿主会话级切换服务，适配器自持当前上下文（按用户持久化到 documents），
 *   选项合法性对照宿主 listDepartments/listRoles 校验。
 *
 * 字段名与启动器侧 Rust serde 结构（YmclSessionInfo / YmclSessionContext）
 * 逐字一致（snake_case）。
 */
public class YmclSessionController {

    private static final String CONTEXT_COLLECTION = "ymcl_context";

    private final PluginUserService users;
    private final PluginDocumentStore documents;

    public YmclSessionController(PluginUserService users, PluginDocumentStore documents) {
        this.users = users;
        this.documents = documents;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/session", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse session(PluginHttpRequest request) {
        var principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return PluginHttpResponse.rawJson(401, error("unauthenticated",
                    "Sign in to this domain to obtain a session"));
        }

        Long userId = principal.userId();
        PluginUserProfile profile = users.findById(userId).orElse(null);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("user_id", String.valueOf(userId));
        session.put("username", profile != null ? profile.username() : String.valueOf(userId));
        session.put("nickname", profile != null ? emptyToNull(profile.nickname()) : null);
        session.put("avatar", profile != null ? emptyToNull(profile.avatar()) : null);
        session.put("permissions", principal.permissions());
        // The host principal does not expose how the token was issued; the
        // launcher treats this field as informational only.
        session.put("issued_via", null);
        session.put("context", buildContext(userId));

        return PluginHttpResponse.rawJson(200, session);
    }

    /**
     * POST /v1/context/switch：切换当前用户的部门或角色上下文（body
     * {@code {"deptId": "..."}} 或 {@code {"roleId": "..."}}，二选一）。
     * 选项对照宿主用户服务校验；切换结果按用户持久化，此后 /v1/session
     * 与本端点响应都携带新上下文。
     */
    @PluginHttpEndpoint(method = "POST", path = "/v1/context/switch",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse contextSwitch(PluginHttpRequest request) {
        var principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return PluginHttpResponse.rawJson(401, error("unauthenticated",
                    "Sign in to this domain to switch context"));
        }
        Long userId = principal.userId();

        Map<String, Object> body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.rawJson(400, error("invalid_body",
                    "Request body must be JSON"));
        }
        String deptId = textOrNull(body.get("deptId"));
        String roleId = textOrNull(body.get("roleId"));
        if ((deptId == null) == (roleId == null)) {
            return PluginHttpResponse.rawJson(400, error("invalid_body",
                    "Exactly one of deptId or roleId is required"));
        }

        Map<String, Object> selection = persistedSelection(userId);

        if (deptId != null) {
            var match = users.listDepartments(userId).stream()
                    .filter(dept -> String.valueOf(dept.id()).equals(deptId))
                    .findFirst();
            if (match.isEmpty()) {
                return PluginHttpResponse.rawJson(400, error("unknown_department",
                        "dept " + deptId + " is not available for this user"));
            }
            selection.put("deptId", String.valueOf(match.get().id()));
            selection.put("deptName", match.get().name());
        } else {
            var match = users.listRoles(userId).stream()
                    .filter(role -> String.valueOf(role.id()).equals(roleId))
                    .findFirst();
            if (match.isEmpty()) {
                return PluginHttpResponse.rawJson(400, error("unknown_role",
                        "role " + roleId + " is not available for this user"));
            }
            selection.put("roleId", String.valueOf(match.get().id()));
            selection.put("roleName", match.get().name());
        }
        documents.save(CONTEXT_COLLECTION, String.valueOf(userId), selection);

        PluginUserProfile profile = users.findById(userId).orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", String.valueOf(userId));
        payload.put("username", profile != null ? profile.username() : String.valueOf(userId));
        payload.put("nickname", profile != null ? emptyToNull(profile.nickname()) : null);
        payload.put("avatar", profile != null ? emptyToNull(profile.avatar()) : null);
        payload.put("permissions", principal.permissions());
        payload.put("issued_via", null);
        payload.put("context", buildContext(userId));
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * Department/role context for multi-context users (YAP §6.4 context
     * capability). Available options come from the host user service; the
     * current selection is whatever was last chosen via /v1/context/switch
     * (persisted per user, since the SPI does not expose the host-side
     * session context).
     */
    private Map<String, Object> buildContext(Long userId) {
        List<Map<String, Object>> depts = new ArrayList<>();
        for (var dept : users.listDepartments(userId)) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", String.valueOf(dept.id()));
            option.put("name", dept.name());
            depts.add(option);
        }
        List<Map<String, Object>> roles = new ArrayList<>();
        for (var role : users.listRoles(userId)) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", String.valueOf(role.id()));
            option.put("name", role.name());
            roles.add(option);
        }
        if (depts.isEmpty() && roles.isEmpty()) {
            return null;
        }
        Map<String, Object> selection = persistedSelection(userId);
        String selectedDeptId = textOrNull(selection.get("deptId"));
        String selectedRoleId = textOrNull(selection.get("roleId"));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("dept", findOption(depts, selectedDeptId));
        context.put("role", findOption(roles, selectedRoleId));
        context.put("available_depts", depts);
        context.put("available_roles", roles);
        return context;
    }

    private Map<String, Object> persistedSelection(Long userId) {
        return documents.findById(CONTEXT_COLLECTION, String.valueOf(userId))
                .orElseGet(LinkedHashMap::new);
    }

    private static Map<String, Object> findOption(List<Map<String, Object>> options, String id) {
        if (id == null) {
            return null;
        }
        for (Map<String, Object> option : options) {
            if (id.equals(option.get("id"))) {
                return option;
            }
        }
        return null;
    }

    private static String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static Map<String, Object> error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return payload;
    }

    /** 裸 JSON 错误响应（供各 controller 复用，YAP §6.2 错误信封）。 */
    static PluginHttpResponse errorResponse(int status, String code, String message) {
        return PluginHttpResponse.rawJson(status, error(code, message));
    }
}
