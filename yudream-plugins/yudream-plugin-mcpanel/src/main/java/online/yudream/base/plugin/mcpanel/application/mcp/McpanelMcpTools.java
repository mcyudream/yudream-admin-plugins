package online.yudream.base.plugin.mcpanel.application.mcp;

import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.application.service.ServerConfigService;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiTool;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolRisk;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * mcpanel 的 MCP/AI 工具集：把实例管理、文件、配置、备份能力注册为宿主 AI 工具，
 * 经平台 MCP 服务器能力（mcp-server）按调用者权限暴露为 MCP 工具。
 * 约定：工具名带 mcpanel_ 前缀防跨模块撞名；allowedTriggers 声明 MCP 通道；
 * 权限码复用插件既有 view/manage；actor/scope 一律从执行上下文的 userId 推导。
 */
public final class McpanelMcpTools {

    private static final String VIEW = "plugin:mcpanel:view";
    private static final String MANAGE = "plugin:mcpanel:manage";
    private static final Set<String> TRIGGERS = Set.of("MENTION", "MCP");
    private static final int MAX_WRITE_BYTES = 8 * 1024 * 1024;
    private static final List<String> LIST_KEYS = List.of(
            "id", "nodeId", "name", "kind", "mcVersion", "state", "lastExitCode",
            "memoryMb", "cpuMillis", "diskMb", "ports", "remark", "autoRestart", "autoStart");

    private McpanelMcpTools() {
    }

    /** 工具定义：descriptor 字段 + (ctx, args) -> result 的执行体。 */
    private record Tool(String name, String title, String description, String permission,
                        PluginAiToolRisk risk, Map<String, Object> inputSchema,
                        BiFunction<PluginAiExecutionContext, Map<String, Object>, PluginAiToolResult> executor)
            implements PluginAiTool {

        @Override
        public PluginAiToolDescriptor descriptor() {
            return new PluginAiToolDescriptor(name, title, description, permission, risk, false, TRIGGERS, inputSchema);
        }

        @Override
        public PluginAiToolResult execute(PluginAiExecutionContext context, PluginAiToolCall call) {
            return executor.apply(context, call.arguments());
        }
    }

    public static List<PluginAiTool> create(McpanelInstanceAppService instances, ServerConfigService serverConfig) {
        List<PluginAiTool> tools = new ArrayList<>();

        // ---- 实例管理 ----
        tools.add(new Tool("mcpanel_instance_list", "实例列表",
                "列出 MC 实例（id/名称/节点/状态/资源/端口），支持关键字与状态筛选",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("page", "integer", "size", "integer", "keyword", "string",
                        "status", "string", "nodeId", "string")),
                (ctx, args) -> {
                    int page = (int) longOf(args, "page", 1);
                    int size = (int) longOf(args, "size", 20);
                    Map<String, Object> result = instances.page(scope(ctx), Math.max(page, 1),
                            Math.min(Math.max(size, 1), 100), str(args, "keyword"), str(args, "status"), str(args, "nodeId"));
                    return done("共 " + result.get("total") + " 个实例", trimList(result));
                }));
        tools.add(new Tool("mcpanel_instance_detail", "实例详情",
                "查看单个实例的完整规格与状态（含启动命令、环境变量、端口、资源）",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("instanceId", "string")),
                (ctx, args) -> done("实例详情", instances.detail(scope(ctx), require(args, "instanceId")))));
        for (String action : List.of("start", "stop", "restart", "kill")) {
            tools.add(new Tool("mcpanel_instance_" + action, "实例" + action,
                    "对实例执行 " + action + "（启停类操作，节点侧执行）",
                    MANAGE, PluginAiToolRisk.WRITE,
                    schema(Map.of("instanceId", "string", "timeoutSec", "integer")),
                    (ctx, args) -> {
                        String id = require(args, "instanceId");
                        Integer timeout = args.get("timeoutSec") == null ? null
                                : (int) longOf(args, "timeoutSec", 30);
                        return done("实例 " + id + " 已下发 " + action,
                                instances.action(actor(ctx), scope(ctx), id, action, timeout));
                    }));
        }
        tools.add(new Tool("mcpanel_instance_command", "发送控制台命令",
                "向运行中的实例控制台发送命令（如 list、say、op 玩家名）",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "command", "string")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    String command = require(args, "command");
                    instances.command(actor(ctx), scope(ctx), id, command);
                    return done("已发送命令：" + command, Map.of("instanceId", id, "command", command));
                }));
        tools.add(new Tool("mcpanel_instance_logs", "控制台日志",
                "读取实例最近的控制台输出（tail 行数，可传 since 游标续读）",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("instanceId", "string", "tail", "integer", "since", "string")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    int tail = (int) longOf(args, "tail", 100);
                    Map<String, Object> result = instances.output(scope(ctx), id, str(args, "since"), tail);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("instanceId", id);
                    payload.put("text", result.get("text"));
                    payload.put("nextCursor", result.get("nextCursor"));
                    payload.put("truncated", result.get("truncated"));
                    return done("控制台输出如下", payload);
                }));

        // ---- 文件管理 ----
        tools.add(new Tool("mcpanel_file_list", "实例文件列表",
                "列出实例数据目录下的文件与子目录",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("instanceId", "string", "path", "string", "page", "integer",
                        "size", "integer", "keyword", "string")),
                (ctx, args) -> {
                    Map<String, Object> payload = filesArgs(args);
                    payload.put("page", (int) longOf(args, "page", 1));
                    payload.put("size", (int) longOf(args, "size", 100));
                    putIfPresent(payload, "keyword", str(args, "keyword"));
                    return done("文件列表", instances.files(scope(ctx), require(args, "instanceId"), "list", payload));
                }));
        tools.add(new Tool("mcpanel_file_read", "读取实例文件",
                "读取实例文件内容（文本；默认最多 64KB，可调 maxBytes）",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("instanceId", "string", "path", "string", "maxBytes", "integer")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    String path = require(args, "path");
                    long maxBytes = longOf(args, "maxBytes", 64 * 1024);
                    Map<String, Object> result = instances.files(scope(ctx), id, "read",
                            Map.of("path", path, "offset", 0, "length", maxBytes));
                    String content = decode(result.get("content"));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("path", path);
                    payload.put("content", content);
                    payload.put("bytes", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    return done("文件内容如下", payload);
                }));
        tools.add(new Tool("mcpanel_file_write", "写入实例文件",
                "写入/创建实例文件（encoding=text 传明文，base64 传二进制；上限 8MB，更大请用面板上传）",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "path", "string", "content", "string",
                        "encoding", "string", "charset", "string")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    String path = require(args, "path");
                    String content = require(args, "content");
                    String encoding = str(args, "encoding") == null ? "text" : str(args, "encoding");
                    String charset = str(args, "charset") == null ? "UTF-8" : str(args, "charset");
                    byte[] bytes = "base64".equalsIgnoreCase(encoding)
                            ? Base64.getDecoder().decode(content)
                            : content.getBytes(java.nio.charset.Charset.forName(charset));
                    if (bytes.length > MAX_WRITE_BYTES) {
                        throw new IllegalArgumentException("内容超过 8MB 上限（" + bytes.length + " 字节），请用面板分片上传");
                    }
                    instances.files(scope(ctx), id, "write", new LinkedHashMap<>(Map.of(
                            "path", path, "content", Base64.getEncoder().encodeToString(bytes),
                            "encoding", "base64", "charset", charset)));
                    return done("已写入 " + path + "（" + bytes.length + " 字节）",
                            Map.of("instanceId", id, "path", path, "bytes", bytes.length));
                }));
        tools.add(new Tool("mcpanel_file_delete", "删除实例文件",
                "删除实例内的文件或目录",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "path", "string")),
                (ctx, args) -> {
                    String path = require(args, "path");
                    instances.files(scope(ctx), require(args, "instanceId"), "delete", Map.of("path", path));
                    return done("已删除：" + path, Map.of("path", path));
                }));
        tools.add(new Tool("mcpanel_file_move", "移动/重命名实例文件",
                "移动或重命名实例内的文件/目录",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "from", "string", "to", "string")),
                (ctx, args) -> {
                    String from = require(args, "from");
                    String to = require(args, "to");
                    instances.files(scope(ctx), require(args, "instanceId"), "rename",
                            Map.of("from", from, "to", to));
                    return done("已移动 " + from + " → " + to, Map.of("from", from, "to", to));
                }));
        tools.add(new Tool("mcpanel_file_mkdir", "创建实例目录",
                "在实例数据目录内创建目录",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "path", "string")),
                (ctx, args) -> {
                    String path = require(args, "path");
                    instances.files(scope(ctx), require(args, "instanceId"), "mkdir", Map.of("path", path));
                    return done("已创建目录：" + path, Map.of("path", path));
                }));

        // ---- 备份 ----
        tools.add(new Tool("mcpanel_backup_list", "备份列表",
                "列出实例的备份文件（含创建中状态）",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("instanceId", "string")),
                (ctx, args) -> done("备份列表", instances.backup(actor(ctx), scope(ctx),
                        require(args, "instanceId"), "list", Map.of()))));
        tools.add(new Tool("mcpanel_backup_create", "创建备份",
                "为实例创建备份（异步受理，稍后用 mcpanel_backup_list 查询进度）",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    Map<String, Object> result = instances.backup(actor(ctx), scope(ctx), id, "create", Map.of());
                    return done("备份已受理，可用 mcpanel_backup_list 轮询进度", result);
                }));
        tools.add(new Tool("mcpanel_backup_restore", "恢复备份",
                "把实例恢复到指定备份（会覆盖当前数据，通常要求实例停止）",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "file", "string")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    String file = require(args, "file");
                    Map<String, Object> result = instances.backup(actor(ctx), scope(ctx), id, "restore",
                            Map.of("file", file));
                    return done("已下发恢复：" + file, result);
                }));

        // ---- 配置文件 ----
        tools.add(new Tool("mcpanel_config_view", "查看配置文件",
                "查看实例配置文件（默认 server.properties；支持 yml/toml 点路径扁平化），返回解析键值与原文",
                VIEW, PluginAiToolRisk.READ,
                schema(Map.of("instanceId", "string", "path", "string")),
                (ctx, args) -> done("配置内容", serverConfig.view(scope(ctx),
                        require(args, "instanceId"), str(args, "path")))));
        tools.add(new Tool("mcpanel_config_set", "修改配置文件",
                "修改实例配置文件：传 properties 键值（推荐，如 gamemode=survival）或 raw 整档原文；"
                        + "yml/toml 用点路径键。修改后需重启实例生效",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "path", "string",
                        "properties", "object", "raw", "string", "preferRaw", "boolean")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    Map<String, String> properties = stringMap(args.get("properties"));
                    String raw = str(args, "raw");
                    if ((properties == null || properties.isEmpty()) && (raw == null || raw.isBlank())) {
                        throw new IllegalArgumentException("需要提供 properties 键值或 raw 原文之一");
                    }
                    String path = str(args, "path") == null ? ServerConfigService.DEFAULT_PATH : str(args, "path");
                    Map<String, Object> result = serverConfig.save(scope(ctx), id, path, properties, raw,
                            Boolean.TRUE.equals(args.get("preferRaw")));
                    return done("配置已保存（重启实例后生效）", result);
                }));

        // ---- 规格调整（JVM/资源参数优化）----
        tools.add(new Tool("mcpanel_spec_update", "调整实例规格",
                "修改实例资源与启动参数（内存/CPU/磁盘/启动命令=含 JVM 参数/startDetect/名称/备注）；"
                        + "实例须停止，未提供的字段保持不变",
                MANAGE, PluginAiToolRisk.WRITE,
                schema(Map.of("instanceId", "string", "name", "string", "memoryMb", "integer",
                        "cpuMillis", "integer", "diskMb", "integer", "command", "string",
                        "startDetect", "string", "remark", "string")),
                (ctx, args) -> {
                    String id = require(args, "instanceId");
                    Map<String, Object> current = instances.detail(scope(ctx), id);
                    Map<String, Object> merged = new LinkedHashMap<>(current);
                    putIfPresent(merged, "name", str(args, "name"));
                    putIfPresent(merged, "remark", str(args, "remark"));
                    putIfPresent(merged, "startDetect", str(args, "startDetect"));
                    if (args.get("memoryMb") != null) merged.put("memoryMb", longOf(args, "memoryMb", 0));
                    if (args.get("cpuMillis") != null) merged.put("cpuMillis", longOf(args, "cpuMillis", 0));
                    if (args.get("diskMb") != null) merged.put("diskMb", longOf(args, "diskMb", 0));
                    String command = str(args, "command");
                    if (command != null && !command.isBlank()) {
                        merged.put("command", List.of(command.trim().split("\\s+")));
                    }
                    McpanelInstance spec;
                    try {
                        spec = McpanelJson.read(McpanelJson.mapper().writeValueAsString(merged), McpanelInstance.class);
                    } catch (Exception error) {
                        throw new IllegalArgumentException("实例规格序列化失败：" + error.getMessage(), error);
                    }
                    Map<String, Object> result = instances.update(actor(ctx), scope(ctx), id, spec);
                    return done("实例 " + id + " 规格已更新（停止态生效；运行中会提示先停止）", result);
                }));

        return List.copyOf(tools);
    }

    // ---- 执行辅助 ----

    private static PluginAiToolResult done(String message, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new PluginAiToolResult("ok", message, Map.of());
        }
        // 应用服务 DTO 允许 null 值，PluginAiToolResult 的 Map.copyOf 不接受：浅层剥离
        Map<String, Object> clean = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (value != null) {
                clean.put(key, value);
            }
        });
        return new PluginAiToolResult("ok", message, clean);
    }

    private static String actor(PluginAiExecutionContext context) {
        return "user:" + context.userId();
    }

    private static String scope(PluginAiExecutionContext context) {
        return actor(context);
    }

    private static String require(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数：" + key);
        }
        return value;
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Map<String, Object> args, String key, long fallback) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filesArgs(Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", str(args, "path") == null ? "/" : str(args, "path"));
        return payload;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trimList(Map<String, Object> pageResult) {
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("total", pageResult.get("total"));
        trimmed.put("page", pageResult.get("page"));
        trimmed.put("size", pageResult.get("size"));
        Object records = pageResult.get("records");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (records instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String key : LIST_KEYS) {
                        Object value = map.get(key);
                        if (value != null) {
                            row.put(key, value);
                        }
                    }
                    rows.add(row);
                }
            }
        }
        trimmed.put("records", rows);
        return trimmed;
    }

    private static String decode(Object base64Content) {
        if (base64Content == null) {
            return "";
        }
        String encoded = String.valueOf(base64Content);
        if (encoded.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException error) {
            return encoded;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()),
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> schema(Map<String, String> properties) {
        Map<String, Object> fields = new LinkedHashMap<>();
        properties.forEach((key, type) -> fields.put(key, Map.of("type", type)));
        return Map.of("type", "object", "properties", fields);
    }
}
